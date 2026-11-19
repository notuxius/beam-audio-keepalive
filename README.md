# Beam Audio Keepalive

Android app for the original XREAL Beam. Auto Keepalive keeps the DisplayPort audio output open while the glasses are connected. Optional boot video recovery detects a DisplayPort clock timeout and briefly sleeps and wakes the Beam. Version 1.13 was tested on Beam OS 384 with Air 2 Pro glasses.

## Install

Download `beam-audio-keepalive.apk` from [Releases](../../releases/latest) and install it on the Beam with ADB.

```sh
adb install -r beam-audio-keepalive.apk
```

Open the app once, enable **Auto Keepalive**, and use **Done** to close settings. The app starts Auto Keepalive after reboot while the setting is enabled. If you deliberately force-stop the app, open it again to let Android resume delivering its boot event.

## Optional boot video recovery

Boot video recovery is available on Beam OS 384. Grant kernel log access once over ADB, then open **Show troubleshooting → Enable boot video recovery** and approve the device administrator prompt. The device administrator declares only the lock policy, which the app uses to sleep the Beam during recovery. The app skips display recovery when the Beam has a secure lock screen.

```sh
adb shell pm grant local.xreal.beamaudiokeepalive android.permission.READ_LOGS
```

To disable it, open **Show troubleshooting → Disable boot video recovery**. To revoke log access too, run `adb shell pm revoke local.xreal.beamaudiokeepalive android.permission.READ_LOGS`.

## Build from source

On Windows, install JDK 17, Android SDK Platform 36, and Build Tools 36.0.0. Set `JAVA_HOME` and `ANDROID_HOME`, put the JDK on your PATH, and run these commands from the repository root in PowerShell. The result is an aligned, unsigned APK.

```powershell
$tools = Join-Path $env:ANDROID_HOME 'build-tools/36.0.0'
$platform = Join-Path $env:ANDROID_HOME 'platforms/android-36/android.jar'
New-Item -ItemType Directory -Force -Path build/classes,build/generated | Out-Null
& "$tools/aapt2.exe" compile --dir res -o build/resources.zip
& "$tools/aapt2.exe" link -I $platform --manifest AndroidManifest.xml --java build/generated -o build/unsigned.apk build/resources.zip
$sources = @((Get-ChildItem -Filter '*.java').FullName) + @((Get-ChildItem build/generated -Recurse -Filter '*.java').FullName)
javac --release 8 -classpath $platform -d build/classes @sources
jar cf build/keepalive.jar -C build/classes .
& "$tools/d8.bat" --lib $platform --min-api 26 --output build build/keepalive.jar
Push-Location build
try { & "$tools/aapt.exe" add unsigned.apk classes.dex } finally { Pop-Location }
& "$tools/zipalign.exe" -f 4 build/unsigned.apk build/aligned.apk
```

Sign `build/aligned.apk` with `apksigner` and your own Android signing key. Use the release APK for in-place updates to an installation signed with the release key.
