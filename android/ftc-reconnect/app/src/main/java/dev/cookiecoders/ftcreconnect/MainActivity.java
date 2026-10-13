package dev.cookiecoders.ftcreconnect;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// All Wi-Fi Direct calls use runP2p's permission gate and SecurityException handling.
@SuppressLint("MissingPermission")
public final class MainActivity extends Activity {
    private static final String TAG = "FtcReconnect";
    private static final String DEFAULT_DS_NAME = "39092-DS";
    private static final String DEFAULT_RC_NAME = "39092-RC";
    private static final String DS_PACKAGE = "com.qualcomm.ftcdriverstation";
    private static final String RC_ADDRESS = "192.168.49.1";
    private static final int PERMISSION_REQUEST = 39;
    private static final long TIMEOUT_MS = 60_000;

    private enum Phase { IDLE, CHECKING, DIAGNOSING, REMOVING, DISCOVERING, CONNECTING, VERIFYING, READY, ERROR }
    private enum Operation { RECONNECT, DIAGNOSE }
    private enum ProbeStatus { OK, WRONG_IDENTITY, NOT_ALIVE, HTTP_ERROR, NETWORK_ERROR, INVALID_RESPONSE }

    private static final class ProbeResult {
        final ProbeStatus status;
        final String detail;

        ProbeResult(ProbeStatus status, String detail) {
            this.status = status;
            this.detail = detail;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private TextView status;
    private Button fix;
    private Button open;
    private Button diagnose;
    private Button deviceNames;
    private TextView deviceSummary;
    private TextView diagnosticReport;
    private SharedPreferences preferences;
    private String dsName;
    private String rcName;
    private View permissions;
    private ProgressBar progress;
    private Phase phase = Phase.IDLE;
    private int attempt;
    private Operation pendingPermission;
    private String peerAddress;
    private boolean registered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)
                    && phase == Phase.DISCOVERING) {
                requestPeers(attempt);
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)
                    && phase == Phase.CONNECTING) {
                inspectConnection(attempt);
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)
                    && phase == Phase.DIAGNOSING) {
                fail(getString(R.string.diagnostic_link_changed));
            } else if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)
                    && busy() && intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                fail("Wi-Fi Direct turned off. Turn Wi-Fi on, then try again.");
            }
        }
    };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        status = findViewById(R.id.status);
        fix = findViewById(R.id.fix);
        open = findViewById(R.id.open_ds);
        diagnose = findViewById(R.id.diagnose);
        deviceNames = findViewById(R.id.device_names);
        deviceSummary = findViewById(R.id.device_summary);
        diagnosticReport = findViewById(R.id.diagnostic_report);
        preferences = getSharedPreferences("device_names", MODE_PRIVATE);
        dsName = preferences.getString("ds_name", DEFAULT_DS_NAME);
        rcName = preferences.getString("rc_name", DEFAULT_RC_NAME);
        updateDeviceSummary();
        progress = findViewById(R.id.progress);
        permissions = findViewById(R.id.app_settings);
        fix.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_title)
                .setMessage(getString(R.string.confirm_body, rcName))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.confirm_fix,
                        (dialog, which) -> beginWithPermission(Operation.RECONNECT))
                .show());
        diagnose.setOnClickListener(view -> beginWithPermission(Operation.DIAGNOSE));
        deviceNames.setOnClickListener(view -> editDeviceNames());
        open.setOnClickListener(view -> openDriverStation());
        findViewById(R.id.wifi_controls).setOnClickListener(view ->
                launchSettings(new Intent(Settings.Panel.ACTION_WIFI)));
        permissions.setOnClickListener(view -> launchSettings(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));

        manager = getSystemService(WifiP2pManager.class);
        if (manager == null) {
            fail("This phone does not support Wi-Fi Direct.");
            fix.setEnabled(false);
            return;
        }
        channel = manager.initialize(this, getMainLooper(), () -> {
            channel = null;
            fail("Android closed Wi-Fi Direct. Close this helper, reopen it, and try again.");
        });
        if (channel == null) {
            fail("Android could not open Wi-Fi Direct. Reopen this helper.");
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
        registered = true;
        if (state != null) {
            status.setText(R.string.helper_reopened);
        }
    }

    private String requiredPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.NEARBY_WIFI_DEVICES : Manifest.permission.ACCESS_FINE_LOCATION;
    }

    private void updateDeviceSummary() {
        deviceSummary.setText(getString(R.string.subtitle, dsName, rcName));
    }

    private void editDeviceNames() {
        View fields = getLayoutInflater().inflate(R.layout.device_names, null);
        EditText ds = fields.findViewById(R.id.ds_name);
        EditText rc = fields.findViewById(R.id.rc_name);
        ds.setText(dsName);
        rc.setText(rcName);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.device_names)
                .setView(fields)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.save_names, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String newDs = ds.getText().toString().trim();
                    String newRc = rc.getText().toString().trim();
                    ds.setError(null);
                    rc.setError(null);
                    if (!validDeviceName(newDs)) {
                        ds.setError(getString(R.string.invalid_name));
                        ds.requestFocus();
                    } else if (!validDeviceName(newRc)) {
                        rc.setError(getString(R.string.invalid_name));
                        rc.requestFocus();
                    } else if (newDs.equals(newRc)) {
                        rc.setError(getString(R.string.same_names));
                        rc.requestFocus();
                    } else if (!preferences.edit().putString("ds_name", newDs)
                            .putString("rc_name", newRc).commit()) {
                        rc.setError(getString(R.string.names_not_saved));
                    } else {
                        ++attempt;
                        handler.removeCallbacksAndMessages(null);
                        dsName = newDs;
                        rcName = newRc;
                        updateDeviceSummary();
                        diagnosticReport.setVisibility(View.GONE);
                        setPhase(Phase.IDLE, getString(R.string.names_saved));
                        dialog.dismiss();
                    }
                }));
        dialog.show();
    }

    private static boolean validDeviceName(String name) {
        return !name.isEmpty() && name.getBytes(StandardCharsets.UTF_8).length <= 32
                && name.codePoints().noneMatch(Character::isISOControl);
    }

    private void beginWithPermission(Operation operation) {
        if (busy()) {
            return;
        }
        diagnosticReport.setVisibility(View.GONE);
        setPhase(operation == Operation.DIAGNOSE ? Phase.DIAGNOSING : Phase.CHECKING,
                getString(operation == Operation.DIAGNOSE
                        ? R.string.diagnosing : R.string.waiting_permission));
        if (checkSelfPermission(requiredPermission()) != PackageManager.PERMISSION_GRANTED) {
            pendingPermission = operation;
            status.setText(R.string.waiting_permission);
            String[] names = Build.VERSION.SDK_INT >= 33
                    ? new String[]{requiredPermission()}
                    : new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION};
            requestPermissions(names, PERMISSION_REQUEST);
        } else {
            runOperation(operation);
        }
    }

    private void runOperation(Operation operation) {
        if (operation == Operation.DIAGNOSE) {
            beginDiagnostics();
        } else {
            begin();
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] names, int[] grants) {
        super.onRequestPermissionsResult(request, names, grants);
        if (request != PERMISSION_REQUEST) {
            return;
        }
        Operation resume = pendingPermission;
        pendingPermission = null;
        if (resume == null) {
            return;
        }
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            permissions.setVisibility(View.GONE);
            runOperation(resume);
        } else {
            fail(getString(R.string.permission_denied));
            permissions.setVisibility(View.VISIBLE);
        }
    }

    private void begin() {
        if (channel == null) {
            fail("Wi-Fi Direct is unavailable. Close and reopen this helper.");
            return;
        }
        if (getPackageManager().getLaunchIntentForPackage(DS_PACKAGE) == null) {
            fail("FTC Driver Station is not installed or its launcher is disabled. Ask a mentor.");
            return;
        }
        WifiManager wifi = getApplicationContext().getSystemService(WifiManager.class);
        if (wifi == null || !wifi.isWifiEnabled()) {
            fail("Turn Wi-Fi on using Wi-Fi Controls, then tap Fix Connection again.");
            return;
        }
        LocationManager location = getSystemService(LocationManager.class);
        if (Build.VERSION.SDK_INT <= 32 && (location == null || !location.isLocationEnabled())) {
            fail("Android requires Location on for Wi-Fi Direct discovery on this phone.");
            launchSettings(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }
        final int id = ++attempt;
        peerAddress = null;
        setPhase(Phase.CHECKING, "1 / 4  Checking this Driver Station...");
        handler.postDelayed(() -> {
            if (current(id) && busy()) {
                fail("Connection timed out. Keep both phones unlocked, open the Robot Controller app, and retry.");
            }
        }, TIMEOUT_MS);
        runP2p(() -> manager.requestDeviceInfo(channel, device -> {
            if (!current(id)) {
                return;
            }
            if (device == null || !dsName.equals(device.deviceName)) {
                fail("This phone's Wi-Fi Direct name does not match " + dsName
                        + ". Check Device Names and the phone's FTC settings with a mentor.");
                return;
            }
            inspectExistingGroup(id);
        }));
    }

    private void inspectExistingGroup(int id) {
        runP2p(() -> manager.requestGroupInfo(channel, group -> {
            if (!current(id)) {
                return;
            }
            if (group == null) {
                discover(id, 0);
                return;
            }
            WifiP2pDevice owner = group.getOwner();
            if (owner == null || (!rcName.equals(owner.deviceName)
                    && !dsName.equals(owner.deviceName))) {
                fail("A different Wi-Fi Direct group is connected. Ask a mentor; it was not disconnected.");
                return;
            }
            setPhase(Phase.REMOVING, "2 / 4  Clearing the old FTC link...");
            removeGroup(id, 0);
        }));
    }

    private void removeGroup(int id, int retry) {
        runP2p(() -> manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                if (current(id)) {
                    waitForRemoval(id);
                }
            }

            @Override
            public void onFailure(int reason) {
                if (!current(id)) {
                    return;
                }
                if (reason == WifiP2pManager.BUSY && retry < 3) {
                    handler.postDelayed(() -> {
                        if (current(id)) {
                            removeGroup(id, retry + 1);
                        }
                    }, 700);
                } else {
                    fail("Could not clear the FTC link: " + reasonText(reason));
                }
            }
        }));
    }

    private void waitForRemoval(int id) {
        runP2p(() -> manager.requestGroupInfo(channel, group -> {
            if (!current(id)) {
                return;
            }
            if (group == null) {
                handler.postDelayed(() -> {
                    if (current(id)) {
                        discover(id, 0);
                    }
                }, 600);
            } else {
                handler.postDelayed(() -> {
                    if (current(id)) {
                        waitForRemoval(id);
                    }
                }, 400);
            }
        }));
    }

    private void discover(int id, int retry) {
        if (!current(id)) {
            return;
        }
        setPhase(Phase.DISCOVERING, "3 / 4  Finding " + rcName + "...");
        runP2p(() -> manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                if (current(id)) {
                    requestPeers(id);
                    pollPeers(id);
                }
            }

            @Override
            public void onFailure(int reason) {
                if (!current(id)) {
                    return;
                }
                if (reason == WifiP2pManager.BUSY && retry < 3) {
                    handler.postDelayed(() -> discover(id, retry + 1), 700);
                } else {
                    fail("Could not find the controller: " + reasonText(reason)
                            + " Check Wi-Fi and Location settings.");
                }
            }
        }));
    }

    private void pollPeers(int id) {
        handler.postDelayed(() -> {
            if (current(id) && phase == Phase.DISCOVERING) {
                requestPeers(id);
                pollPeers(id);
            }
        }, 1_500);
    }

    private void requestPeers(int id) {
        if (!current(id) || phase != Phase.DISCOVERING) {
            return;
        }
        runP2p(() -> manager.requestPeers(channel, peers -> {
            if (!current(id) || phase != Phase.DISCOVERING) {
                return;
            }
            List<WifiP2pDevice> matches = new ArrayList<>();
            for (WifiP2pDevice device : peers.getDeviceList()) {
                if (rcName.equals(device.deviceName)) {
                    matches.add(device);
                }
            }
            if (matches.size() > 1) {
                fail("Two controllers are named " + rcName + ". Ask a mentor; neither was selected.");
            } else if (matches.size() == 1) {
                connect(id, matches.get(0));
            }
        }));
    }

    private void connect(int id, WifiP2pDevice device) {
        if (device.deviceAddress == null || device.deviceAddress.isEmpty()) {
            fail("Android did not provide the controller address. Check Nearby devices permission.");
            return;
        }
        peerAddress = device.deviceAddress;
        setPhase(Phase.CONNECTING, "4 / 4  Pairing with " + rcName + "...\nAccept pairing on the robot phone if asked.");
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = peerAddress;
        config.groupOwnerIntent = 0;
        config.wps.setup = WpsInfo.PBC;
        runP2p(() -> manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                if (current(id)) {
                    pollConnection(id);
                }
            }

            @Override
            public void onFailure(int reason) {
                if (current(id)) {
                    fail("Android could not pair with " + rcName + ": " + reasonText(reason));
                }
            }
        }));
    }

    private void pollConnection(int id) {
        handler.postDelayed(() -> {
            if (current(id) && phase == Phase.CONNECTING) {
                inspectConnection(id);
                pollConnection(id);
            }
        }, 1_000);
    }

    private void inspectConnection(int id) {
        runP2p(() -> manager.requestConnectionInfo(channel, info -> {
            if (!current(id) || phase != Phase.CONNECTING || info == null || !info.groupFormed) {
                return;
            }
            if (info.isGroupOwner || info.groupOwnerAddress == null
                    || !RC_ADDRESS.equals(info.groupOwnerAddress.getHostAddress())) {
                fail("The Robot Controller must own the FTC link at " + RC_ADDRESS + ". Ask a mentor, then retry.");
                return;
            }
            runP2p(() -> manager.requestGroupInfo(channel, group -> {
                if (!current(id) || phase != Phase.CONNECTING || group == null) {
                    return;
                }
                WifiP2pDevice owner = group.getOwner();
                boolean namedOwner = owner != null && rcName.equals(owner.deviceName);
                boolean discoveredOwner = owner != null && peerAddress.equals(owner.deviceAddress)
                        && (owner.deviceName == null || owner.deviceName.isEmpty());
                if (!namedOwner && !discoveredOwner) {
                    fail("Android joined a different controller. No Driver Station launch was attempted.");
                    return;
                }
                verifyController(id);
            }));
        }));
    }

    private void verifyController(int id) {
        setPhase(Phase.VERIFYING, "Wi-Fi Direct is paired. Checking the Robot Controller app...");
        String expectedName = rcName;
        executor.execute(() -> {
            ProbeResult outcome = probeController(expectedName);
            handler.post(() -> {
                if (!current(id) || phase != Phase.VERIFYING) {
                    return;
                }
                if (outcome.status != ProbeStatus.OK) {
                    fail(probeGuidance(outcome, expectedName));
                } else {
                    handler.removeCallbacksAndMessages(null);
                    setPhase(Phase.READY, "FTC Wi-Fi Direct link repaired.\nOpening Driver Station. Check that its app says connected.");
                    handler.postDelayed(() -> {
                        if (current(id) && phase == Phase.READY) {
                            openDriverStation();
                        }
                    }, 700);
                }
            });
        });
    }

    private ProbeResult probeController(String expectedName) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(
                    "http://" + RC_ADDRESS + ":8080/js/rcInfo.json").openConnection();
            connection.setConnectTimeout(4_000);
            connection.setReadTimeout(4_000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            long deadline = SystemClock.elapsedRealtime() + 8_000;
            int response = connection.getResponseCode();
            if (response != HttpURLConnection.HTTP_OK) {
                return new ProbeResult(ProbeStatus.HTTP_ERROR, getString(R.string.probe_http, response));
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[2_048];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 65_536 || SystemClock.elapsedRealtime() > deadline) {
                        throw new IOException("Controller reply exceeded check limits");
                    }
                    bytes.write(buffer, 0, count);
                }
            }
            // The response can contain a passphrase. Parse identity only; never save or log it.
            JSONObject info = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            if (!expectedName.equals(info.getString("deviceName"))) {
                return new ProbeResult(ProbeStatus.WRONG_IDENTITY, "");
            }
            if (!info.getBoolean("serverIsAlive")) {
                return new ProbeResult(ProbeStatus.NOT_ALIVE, getString(R.string.probe_stopped));
            }
            return new ProbeResult(ProbeStatus.OK, "");
        } catch (IOException error) {
            Log.w(TAG, "Controller identity check failed: " + error.getClass().getSimpleName());
            return new ProbeResult(ProbeStatus.NETWORK_ERROR,
                    getString(R.string.probe_network, error.getClass().getSimpleName()));
        } catch (JSONException error) {
            Log.w(TAG, "Controller identity response was invalid");
            return new ProbeResult(ProbeStatus.INVALID_RESPONSE, getString(R.string.probe_invalid));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String probeGuidance(ProbeResult outcome, String expectedName) {
        return switch (outcome.status) {
            case OK -> getString(R.string.diagnostic_healthy, expectedName);
            case WRONG_IDENTITY -> getString(R.string.diagnostic_identity, expectedName);
            default -> getString(R.string.diagnostic_rc_problem, outcome.detail, expectedName);
        };
    }

    private void beginDiagnostics() {
        String problem = diagnosticPrerequisite();
        if (problem != null) {
            finishDiagnostics("", problem);
            return;
        }
        final int id = ++attempt;
        handler.postDelayed(() -> {
            if (current(id) && phase == Phase.DIAGNOSING) {
                fail(getString(R.string.diagnostic_timeout));
            }
        }, 20_000);
        runP2p(() -> manager.requestDeviceInfo(channel, device -> {
            if (!diagnosing(id)) {
                return;
            }
            String reported = device == null || device.deviceName == null
                    ? getString(R.string.diagnostic_unknown) : device.deviceName;
            String evidence = getString(R.string.diagnostic_local, reported, dsName, rcName);
            if (device == null || !dsName.equals(device.deviceName)) {
                finishDiagnostics(evidence, device == null
                        ? getString(R.string.diagnostic_no_p2p)
                        : getString(R.string.diagnostic_name_mismatch));
                return;
            }
            inspectDiagnosticGroup(id, evidence);
        }));
    }

    private String diagnosticPrerequisite() {
        if (channel == null) {
            return getString(R.string.diagnostic_no_p2p);
        }
        if (getPackageManager().getLaunchIntentForPackage(DS_PACKAGE) == null) {
            return getString(R.string.diagnostic_no_ds);
        }
        WifiManager wifi = getApplicationContext().getSystemService(WifiManager.class);
        if (wifi == null || !wifi.isWifiEnabled()) {
            return getString(R.string.diagnostic_wifi_off);
        }
        LocationManager location = getSystemService(LocationManager.class);
        if (Build.VERSION.SDK_INT <= 32 && (location == null || !location.isLocationEnabled())) {
            return getString(R.string.diagnostic_location);
        }
        return null;
    }

    private void inspectDiagnosticGroup(int id, String evidence) {
        runP2p(() -> manager.requestGroupInfo(channel, group -> {
            if (!diagnosing(id)) {
                return;
            }
            if (group == null) {
                finishDiagnostics(evidence, getString(R.string.diagnostic_no_group, rcName));
                return;
            }
            WifiP2pDevice owner = group.getOwner();
            if (owner != null && dsName.equals(owner.deviceName)) {
                finishDiagnostics(evidence, getString(R.string.diagnostic_owner));
                return;
            }
            if (owner == null || !rcName.equals(owner.deviceName)) {
                String reported = owner == null || owner.deviceName == null || owner.deviceName.isEmpty()
                        ? getString(R.string.diagnostic_unknown) : owner.deviceName;
                finishDiagnostics(evidence, getString(R.string.diagnostic_wrong_group, reported));
                return;
            }
            runP2p(() -> manager.requestConnectionInfo(channel, info -> {
                if (!diagnosing(id)) {
                    return;
                }
                if (info == null || !info.groupFormed || info.groupOwnerAddress == null
                        || !RC_ADDRESS.equals(info.groupOwnerAddress.getHostAddress())) {
                    finishDiagnostics(evidence, getString(R.string.diagnostic_address));
                } else if (info.isGroupOwner) {
                    finishDiagnostics(evidence, getString(R.string.diagnostic_owner));
                } else {
                    probeDiagnosticController(id, evidence);
                }
            }));
        }));
    }

    private void probeDiagnosticController(int id, String evidence) {
        String expectedName = rcName;
        executor.execute(() -> {
            ProbeResult outcome = probeController(expectedName);
            handler.post(() -> {
                if (diagnosing(id)) {
                    finishDiagnostics(evidence, probeGuidance(outcome, expectedName));
                }
            });
        });
    }

    private boolean diagnosing(int id) {
        return current(id) && phase == Phase.DIAGNOSING;
    }

    private void finishDiagnostics(String evidence, String guidance) {
        ++attempt;
        handler.removeCallbacksAndMessages(null);
        setPhase(Phase.IDLE, getString(R.string.diagnostic_done));
        showDiagnosticReport(evidence, guidance);
    }

    private void showDiagnosticReport(String evidence, String guidance) {
        String scope = getString(R.string.diagnostic_scope);
        diagnosticReport.setText(evidence.isEmpty()
                ? getString(R.string.diagnostic_report_without_evidence, guidance, scope)
                : getString(R.string.diagnostic_report_with_evidence, evidence, guidance, scope));
        diagnosticReport.setVisibility(View.VISIBLE);
    }

    private void openDriverStation() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(DS_PACKAGE);
        if (intent == null) {
            fail("FTC Driver Station's launcher is unavailable. Ask a mentor.");
            return;
        }
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException error) {
            Log.e(TAG, "Driver Station launcher failed", error);
            fail("Android could not open Driver Station. Open it from the home screen.");
        }
    }

    private void launchSettings(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            Log.e(TAG, "Android settings panel unavailable", error);
            fail("Open Android Settings manually to adjust Wi-Fi or permissions.");
        }
    }

    private void runP2p(Runnable action) {
        if (checkSelfPermission(requiredPermission()) != PackageManager.PERMISSION_GRANTED) {
            fail(getString(R.string.permission_denied));
            permissions.setVisibility(View.VISIBLE);
            return;
        }
        try {
            action.run();
        } catch (SecurityException error) {
            Log.e(TAG, "Wi-Fi Direct permission rejected", error);
            fail("Android rejected Wi-Fi Direct access. Enable Nearby devices in App Permissions, then retry.");
            permissions.setVisibility(View.VISIBLE);
        }
    }

    private boolean current(int id) {
        return id == attempt && !isDestroyed();
    }

    private boolean busy() {
        return phase != Phase.IDLE && phase != Phase.ERROR && phase != Phase.READY;
    }

    private void setPhase(Phase next, String message) {
        phase = next;
        status.setText(message);
        Log.i(TAG, next.name() + ": " + message);
        progress.setVisibility(busy() ? View.VISIBLE : View.GONE);
        fix.setEnabled(!busy());
        open.setEnabled(!busy());
        diagnose.setEnabled(!busy());
        deviceNames.setEnabled(!busy());
        findViewById(R.id.wifi_controls).setEnabled(!busy());
        permissions.setEnabled(!busy());
    }

    private void fail(String message) {
        Phase previous = phase;
        pendingPermission = null;
        ++attempt;
        handler.removeCallbacksAndMessages(null);
        setPhase(Phase.ERROR, message);
        if (previous == Phase.DIAGNOSING) {
            showDiagnosticReport("", message);
        }
        if (channel != null && (previous == Phase.CONNECTING || previous == Phase.DISCOVERING)) {
            WifiP2pManager.ActionListener cleanup = new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.i(TAG, "Pending helper pairing/discovery stopped");
                }

                @Override
                public void onFailure(int reason) {
                    Log.w(TAG, "Could not stop pending helper operation: " + reasonText(reason));
                }
            };
            try {
                if (previous == Phase.CONNECTING) {
                    manager.cancelConnect(channel, cleanup);
                } else {
                    manager.stopPeerDiscovery(channel, cleanup);
                }
            } catch (SecurityException error) {
                Log.w(TAG, "Permission prevented pending-operation cleanup", error);
            }
        }
    }

    private static String reasonText(int reason) {
        return switch (reason) {
            case WifiP2pManager.BUSY -> "Wi-Fi Direct is busy. Wait a moment and retry.";
            case WifiP2pManager.P2P_UNSUPPORTED -> "This phone does not support Wi-Fi Direct.";
            default -> "Android Wi-Fi Direct error " + reason + ".";
        };
    }

    @Override
    protected void onDestroy() {
        if (busy()) {
            fail("Helper closed before pairing finished. Reopen it to retry.");
        }
        ++attempt;
        handler.removeCallbacksAndMessages(null);
        if (registered) {
            unregisterReceiver(receiver);
        }
        if (channel != null) {
            channel.close();
        }
        executor.shutdownNow();
        super.onDestroy();
    }
}
