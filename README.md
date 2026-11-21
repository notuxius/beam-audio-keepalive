# Beam Audio Keepalive

Android app for the original XREAL Beam. Auto Keepalive keeps the DisplayPort audio output open while the glasses are connected. Optional boot video recovery detects a DisplayPort clock timeout and briefly sleeps and wakes the Beam. Version 1.13 was tested on Beam OS 384 with Air 2 Pro glasses.

## Install

Download `beam-audio-keepalive.apk` from [Releases](../../releases/latest) and install it on the Beam with ADB.

```sh
adb install -r beam-audio-keepalive.apk
```

Open the app once, enable **Auto Keepalive**, and use **Done** to close settings. The app starts Auto Keepalive after reboot while the setting is enabled. If you deliberately force-stop the app, open it again to let Android resume delivering its boot event.

## Optional Playback Protection

On the original Beam running OS 384, open **Show troubleshooting → Turn on Playback Protection**. This setting is off by default, persists across reboots, and works with or without Auto Keepalive and 2D/3D audio recovery.

Protection keeps a private virtual display open so Beam OS leaves other apps running when EVA returns to the foreground. EVA can still appear during an audio switch. Stop playback before turning protection off because releasing the last virtual display can return the Beam to EVA and close a running player. If the standalone Beam Playback Protection app is installed, enable protection here first, then turn it off in the standalone app while on EVA.

In a Beam OS 384 hardware trial with version 1.15, the USB-to-DisplayPort switch brought EVA to the foreground and stopped playback. VLC's process and movie activity survived. Bringing the same task back to the foreground and pressing Play resumed the movie at its saved position. Automatic return to the movie is still unresolved.

The same behavior occurred when the Beam woke from standby. The glasses briefly appeared as a USB headset and then switched back to DisplayPort. EVA exited on the USB detach and took focus when it restarted. The saved VLC activity could be brought forward and resumed after the DP audio route reopened.

In a separate test, VLC was paused before switching from DisplayPort to USB and back. EVA still took focus on the return to DisplayPort. After the DP keepalive track started, the original VLC activity returned at the paused position and resumed in the same process.

### Pause and return to the foreground app

With Playback Protection enabled on OS 384, add **Prepare switch** to Android Quick Settings. Open an app, tap the tile, and wait for **Ready. Switch now.** before using the glasses button. The tile works for 2D/3D and USB/DisplayPort switches in either direction. Keepalive uses Beam log access to find the foreground app and verify that its original activity is still open.

Keepalive waits for the new display mode and audio route to stay stable for 4.5 seconds. If EVA takes focus, Keepalive brings the saved app back. If Android blocks the return, tap **Return to app** in Quick Settings. Press Play yourself when ready.

Playback Protection automatically handles Air 2 Pro USB/DisplayPort audio switches. To let Keepalive send Pause to the foreground player's Android media session, open **Show troubleshooting → Allow Pause in other players** and grant Android Notification access. If the player keeps playing after the Pause request and has the only active media session, Keepalive tries Android's media Pause key. Without the grant, Keepalive still returns apps to the foreground and can pause VLC using its existing fallback.

For automatic return after EVA takes focus, open **Show troubleshooting → Allow automatic return to apps** and grant **Display over other apps** to Keepalive. This lets Keepalive bring the saved task forward after the route and EVA settle.

With that grant, Keepalive can cover VLC and other players with an active Android media session when notification access is enabled. It keeps the cover up while a recognized player reopens its session. **Prepare switch** fades to black as the switch becomes ready. Automatic audio recovery covers the screen when the route change is detected. Once the saved player is back and reports **Paused**, Keepalive fades it in over 400 ms. Cancel, disconnect, or a timeout clears the cover.

When switching from DisplayPort to USB audio, Keepalive can cover the player during USB enumeration. If the media Pause key has no effect and the player has the only active session, Keepalive briefly requests audio focus to stop playback. After the route settles, it waits for the player to stay foreground and paused for 2.5 seconds before starting the fade.

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
