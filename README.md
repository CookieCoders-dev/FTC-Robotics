# FTC-Robotics
Tools for FIRST Robotics members.

## FTC Reconnect (Android)

Source: `android/ftc-reconnect`

FTC Reconnect is a student-facing helper for phone-based FTC Wi-Fi Direct
connections. It is an unofficial utility, not an FTC Driver Station or Robot
Controller replacement. REV Control Hub hotspots are not supported.

**Install:** Download the [signed Android APK](https://github.com/CookieCoders-dev/FTC-Robotics/releases/latest/download/FTC-Reconnect.apk)
on both phones, then open it to install or update FTC Reconnect. Android may
ask you to allow this installation from your browser or file manager. Install
over the existing app to preserve saved names. Check the version after
**FTC CONNECTION HELPER** at the top of the home screen.

- **Device Names** saves the exact, case-sensitive Driver Station and Robot
  Controller Wi-Fi Direct names. New installs have no default team or device
  names; users must save both names before checking or fixing the connection.
  Updates preserve previously saved names. Saving names does not rename either
  phone.
- **Fix Connection** checks this phone's saved identity and clears its active
  FTC Wi-Fi Direct group. On the RC, it hands off to the official Robot
  Controller app. On the DS, it discovers and pairs with the saved RC, checks
  the RC's local web service, and opens Driver Station.
- **What's Wrong?** inspects the current link without disconnecting or pairing
  and explains whether DS settings or RC-side recovery may need attention.
  It cannot verify the Driver Station's FTC handshake.

**Safety:** Park and stop the robot before using Fix Connection. Do not use it
during a match. The helper never presses INIT or START, starts an OpMode, changes
robot code, or drives the robot.

### Reset the Robot Controller first, then the Driver Station

1. With the robot parked and stopped, install FTC Reconnect on both phones.
   On each phone, save the same exact DS and RC names under **Device Names**
   and grant Nearby devices access if prompted.
2. Leave the DS in FTC Reconnect. On the RC, tap **Fix Connection** and
   confirm the robot is stopped. If prompted to **Proceed with Full Reset**,
   continue only if it is safe to disconnect every device in this phone's
   active Wi-Fi Direct group. This does not erase saved Wi-Fi networks.
3. When the RC reset step finishes, tap **Open Robot Controller** and leave
   that app open.
4. On the DS, tap **Fix Connection**. Accept any pairing request on the RC,
   then check that the official Driver Station app says **Connected**.

Do not run both fixes or toggle Wi-Fi on both phones at the same time. If the
DS cannot find the RC, leave the DS idle and reopen Robot Controller on the RC.
If the RC is still stuck, have a mentor close Robot Controller, turn Wi-Fi off
and back on **on the RC only**, then reopen Robot Controller before retrying
on the DS.

### Requirements

- Two phones running Android 11 or newer, one with the official FTC Driver
  Station app installed and the other with the official FTC Robot Controller
  app installed.
- A phone-based Robot Controller using the FTC Wi-Fi Direct group-owner address
  `192.168.49.1`.
- Wi-Fi Direct permission: Nearby devices on Android 13+, or Location permission
  and enabled Location services on Android 11-12.

### Build on Windows

Install PowerShell 7, a JDK 17 or newer, and Android SDK platform 36. The build
script defaults to Android Studio's bundled JDK and
`%LOCALAPPDATA%\Android\Sdk`; set `JAVA_HOME` and `ANDROID_HOME` to use other
locations.

```powershell
Set-Location .\android\ftc-reconnect
.\build.ps1
```

This builds and lints the signed release and writes
`dist\FTC-Reconnect.apk`. On the first build, it creates a private signing key
and a Windows-user-protected password outside the repository, under
`%USERPROFILE%\.android\ftc-reconnect-signing`. Keep the original key for future
updates; an APK signed with a different key cannot normally update an existing
installation.

Signing keys, passwords, local SDK paths, build outputs, and APKs are not
included in this repository. Never commit signing credentials.

### Privacy and distribution

Device names are stored locally. The helper contacts the selected RC's local
web service to check identity and availability; it does not save or log the
controller response, which can contain credentials. There are no analytics,
ads, or user accounts.

The signed APK build is for sideloading. Google Play publication additionally
requires an Android App Bundle, Play App Signing setup, a privacy policy and
store declarations, and the applicable testing and review process.
