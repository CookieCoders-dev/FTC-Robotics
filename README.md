# FTC-Robotics
Tools for FIRST Robotics members.

## FTC Reconnect (Android)

Source: `android/ftc-reconnect`

FTC Reconnect is a student-facing helper for phone-based FTC Wi-Fi Direct
connections. It is an unofficial utility, not an FTC Driver Station or Robot
Controller replacement. REV Control Hub hotspots are not supported.

- **Device Names** saves the exact, case-sensitive Driver Station and Robot
  Controller Wi-Fi Direct names. The initial defaults are `39092-DS` and
  `39092-RC`; saving names does not rename either phone.
- **Fix Connection** checks the saved identities, clears the old FTC link,
  discovers and pairs with the saved controller, checks its local web service,
  and opens Driver Station.
- **What's Wrong?** inspects the current link without disconnecting or pairing
  and explains whether DS settings or RC-side recovery may need attention.
  It cannot verify the Driver Station's FTC handshake.

**Safety:** Park and stop the robot before using Fix Connection. Do not use it
during a match. The helper never presses INIT or START, starts an OpMode, changes
robot code, or drives the robot.

### Requirements

- A Driver Station phone running Android 11 or newer, with FTC Driver Station
  installed.
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
