[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else {
    Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}
$jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else {
    Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
}
if (-not (Test-Path (Join-Path $sdk 'platforms\android-36\android.jar'))) {
    throw 'Install Android SDK platform 36 before building.'
}
if (-not (Test-Path (Join-Path $jdk 'bin\java.exe'))) {
    throw 'Set JAVA_HOME to a JDK 17 or newer before building.'
}

$privateRoot = Join-Path $env:USERPROFILE '.android\ftc-reconnect-signing'
$keyStore = Join-Path $privateRoot 'ftc-reconnect.p12'
$passwordFile = Join-Path $privateRoot 'password.dpapi.xml'
New-Item -ItemType Directory -Path $privateRoot -Force | Out-Null

if ((Test-Path $keyStore) -ne (Test-Path $passwordFile)) {
    throw 'Signing key and protected password must both exist. Do not regenerate a key for an installed app.'
}
if (Test-Path $passwordFile) {
    $securePassword = Import-Clixml -LiteralPath $passwordFile
    $password = ([System.Net.NetworkCredential]::new('', $securePassword)).Password
} else {
    $password = [Convert]::ToBase64String(
        [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $securePassword = ConvertTo-SecureString -String $password -AsPlainText -Force
}

$env:JAVA_HOME = $jdk
$env:ANDROID_HOME = $sdk
$env:FTC_RECONNECT_KEYSTORE = $keyStore
$env:FTC_RECONNECT_SIGN_PASSWORD = $password
try {
    if (-not (Test-Path $keyStore)) {
        & (Join-Path $jdk 'bin\keytool.exe') -genkeypair -storetype PKCS12 `
            -keystore $keyStore -alias ftcreconnect -keyalg RSA -keysize 3072 `
            -validity 10000 -dname 'CN=Cookie Coders FTC Reconnect' `
            -storepass:env FTC_RECONNECT_SIGN_PASSWORD `
            -keypass:env FTC_RECONNECT_SIGN_PASSWORD
        if ($LASTEXITCODE -ne 0) {
            throw 'Could not generate the private APK signing key.'
        }
        $securePassword | Export-Clixml -LiteralPath $passwordFile
    }
    & (Join-Path $PSScriptRoot 'gradlew.bat') --project-dir $PSScriptRoot `
        :app:assembleRelease :app:lintRelease `
        --no-daemon --console=plain --quiet
    if ($LASTEXITCODE -ne 0) {
        throw 'FTC Reconnect release build or lint failed.'
    }
    $distribution = Join-Path $PSScriptRoot 'dist'
    New-Item -ItemType Directory -Path $distribution -Force | Out-Null
    $apk = Join-Path $distribution 'FTC-Reconnect.apk'
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'app\build\outputs\apk\release\app-release.apk') `
        -Destination $apk -Force
    Write-Output $apk
} finally {
    Remove-Item Env:\FTC_RECONNECT_SIGN_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:\FTC_RECONNECT_KEYSTORE -ErrorAction SilentlyContinue
    $password = $null
}
