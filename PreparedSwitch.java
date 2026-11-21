package local.xreal.beamaudiokeepalive;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

final class PreparedSwitch {
    private static final String TAG = "BeamPreparedSwitch";
    private static final long POLL_MS = 500;
    private static final long ARM_MS = 40000;
    private static final long RETURN_MS = 30000;
    // shortcut: OS 384 stops task switches for five seconds after EVA returns; revisit for another OS.
    private static final long EVA_SWITCH_DELAY_MS = 5200;
    // Wait for the player to reopen its media session after the task comes forward.
    private static final long APP_REOPEN_MS = 2500;
    private static final long PLAYER_READY_MS = 5500;
    private static final long HOME_HANDOFF_MS = 20000;
    private static final long PAUSE_CHECK_MS = 200;
    private static final long PAUSE_RETRY_MS = 750;
    private static final long USB_ENUMERATION_MS = 2000;
    private static final long DP_HANDOFF_POLL_MS = 350;
    // shortcut: match the Beam's Android display fade-out until the glasses' optical fade is measured.
    private static final long FADE_MS = 400;
    private final PlaybackProtection service;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("Beam switch checks");
    private final Handler reader;
    private final AudioManager audio;
    private final AudioFocusRequest pauseFocus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .build();
    private final DisplayManager displays;
    private final ForegroundTasks tasks = new ForegroundTasks();
    private Process activityLog;
    private Process usbLog;
    private Thread logThread;
    private Thread usbLogThread;
    private volatile boolean closed;
    private int generation;
    private long deadline;
    private int stableOutput;
    private boolean armed;
    private boolean paused;
    private boolean awaitingTap;
    private int expectedUsb = -1;
    private ForegroundTasks.Task savedApp;
    private SwitchReadiness readiness;
    private View blackScreen;
    private boolean waitForPlayer;
    private long pauseRequestedAt;
    private MediaSession.Token pauseRequestedTo;
    private boolean pauseKeySent;
    private boolean pauseFocusTried;
    private boolean dpHandoffAvailable;
    private long homeHandoffStartedAt;
    private ForegroundTasks.Task enumeratingPlayer;
    private final Runnable clearUnconfirmedUsbCover = () -> {
        enumeratingPlayer = null;
        if (!armed) clearScreen();
    };

    PreparedSwitch(PlaybackProtection service) {
        this.service = service;
        this.audio = service.getSystemService(AudioManager.class);
        this.displays = service.getSystemService(DisplayManager.class);
        thread.start();
        reader = new Handler(thread.getLooper());
        startLogMonitor();
        reader.post(this::watchDpHandoff);
    }

    boolean armed() { return armed; }
    boolean paused() { return paused; }
    boolean automaticAudioSwitch() { return expectedUsb != -1; }
    boolean awaitingTap() { return awaitingTap; }

    private void coverEarlyUsb(ForegroundTasks.Task target) {
        if (armed || closed || target == null || target != tasks.current()
                || !service.getSystemService(PowerManager.class).isInteractive()
                || !SilencePlayback.GLASSES_PORT_PARTNER.exists()) return;
        if (!hasPlayer(target)) return;
        if (enumeratingPlayer != target || blackScreen == null) {
            enumeratingPlayer = target;
            coverScreen(false);
            if (blackScreen != null) Log.i(TAG, "Covering the player while USB enumerates");
        }
        main.removeCallbacks(clearUnconfirmedUsbCover);
        main.postDelayed(clearUnconfirmedUsbCover, USB_ENUMERATION_MS);
    }

    private void onAudioSwitch(int route, ForegroundTasks.Task atChange, boolean wasHome) {
        if (closed || !service.getSystemService(PowerManager.class).isInteractive()
                || !SilencePlayback.GLASSES_PORT_PARTNER.exists()) return;
        if (homeHandoffStartedAt != 0) {
            if (route == 1) finish("USB audio returned.");
            return;
        }
        if (armed && automaticAudioSwitch()) {
            if (route == expectedUsb) return;
            expectedUsb = route;
            stableOutput = 0;
            awaitingTap = false;
            beginSwitch(savedApp, new SwitchReadiness(0), ++generation);
            return;
        }
        if (armed) {
            if (route == 1) acknowledgeUsbCover(generation);
            return;
        }
        if (route == 0 && wasHome) {
            beginHomeHandoff();
            return;
        }
        if (route == 1 && atChange == null && tasks.evaForegroundAt() >= 0) atChange = enumeratingPlayer;
        if (atChange == null || !tasks.canReturnTo(atChange)) return;
        expectedUsb = route;
        Log.i(TAG, "Detected " + (route == 1 ? "USB" : "DP") + " audio switch in " + atChange.packageName);
        beginSwitch(atChange, new SwitchReadiness(0), ++generation);
    }

    private void watchDpHandoff() {
        if (closed) return;
        ForegroundTasks.Task player = tasks.current();
        boolean home = tasks.evaForegroundAt() >= 0;
        if ((home || (player != null && hasPlayer(player)))
                && Settings.canDrawOverlays(service) && service.getSystemService(PowerManager.class).isInteractive()) {
            Long token = GlassesCover.pendingDp(service, !home);
            if (token != null) {
                if (!dpHandoffAvailable) Log.i(TAG, "Glasses support the DP cover handoff");
                if (token >= 0) main.post(() -> onDpHandoff(player, home, token.intValue()));
            }
            dpHandoffAvailable = token != null;
        } else {
            dpHandoffAvailable = false;
        }
        if (!closed) reader.postDelayed(this::watchDpHandoff, DP_HANDOFF_POLL_MS);
    }

    private void onDpHandoff(ForegroundTasks.Task player, boolean home, int token) {
        if (closed || player != tasks.current() || !checkDisplay()) return;
        if (home) {
            if (armed || tasks.evaForegroundAt() < 0) return;
            if (!beginHomeHandoff()) return;
        } else if (armed) {
            if (expectedUsb >= 0 || !paused || savedApp != player) return;
        } else {
            expectedUsb = 0;
            Log.i(TAG, "Glasses requested DP audio before dropping USB in " + player.packageName);
            beginSwitch(player, new SwitchReadiness(0), ++generation);
        }
        afterCoverCommit(generation, "DP", () -> GlassesCover.acknowledgeDp(service, token, !home));
    }

    private boolean beginHomeHandoff() {
        coverScreen(false);
        if (blackScreen == null) return false;
        int request = ++generation;
        armed = true;
        homeHandoffStartedAt = System.currentTimeMillis();
        expectedUsb = 0;
        readiness = new SwitchReadiness(0);
        Log.i(TAG, "Covering stock Beam Home for the DP switch");
        waitForSwitch(request);
        return true;
    }

    private void waitForHome(int request) {
        if (request != generation || !checkDisplay()) return;
        if (tasks.evaDrawnSince(homeHandoffStartedAt)) {
            finish("Beam Home is ready.", true, true);
        } else if (SystemClock.elapsedRealtime() >= deadline) {
            finish("Timed out waiting for Beam Home.", true, false);
        } else {
            main.postDelayed(() -> waitForHome(request), PAUSE_CHECK_MS);
        }
    }

    void prepare() {
        if (armed) return;
        if (service.checkSelfPermission(Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
            service.showStatus("Preparing a switch requires Beam log access.", true);
            return;
        }
        armed = true;
        int request = ++generation;
        service.showStatus("Checking the current app and audio route…", false);
        startLogMonitor();
        reader.post(() -> {
            ForegroundTasks.Task current = tasks.current();
            int initial = output();
            main.post(() -> {
                if (request != generation) return;
                if (current == null || current != tasks.current() || initial == 0) {
                    finish("Open an app with the glasses connected before preparing a switch.");
                    return;
                }
                beginSwitch(current, new SwitchReadiness(initial), request);
            });
        });
    }

    private void beginSwitch(ForegroundTasks.Task target, SwitchReadiness change, int request) {
        armed = true;
        paused = false;
        if (savedApp != target) {
            waitForPlayer = target == enumeratingPlayer || hasPlayer(target);
        }
        enumeratingPlayer = null;
        main.removeCallbacks(clearUnconfirmedUsbCover);
        pauseRequestedAt = -PAUSE_RETRY_MS;
        pauseRequestedTo = null;
        pauseKeySent = false;
        pauseFocusTried = false;
        savedApp = target;
        readiness = change;
        boolean automatic = automaticAudioSwitch();
        if (automatic) {
            if (waitForPlayer) {
                coverScreen(false);
                if (expectedUsb == 1) acknowledgeUsbCover(request);
            } else {
                clearScreen();
            }
            pausePlayback(target);
        }
        service.stopService(new Intent(service, SilencePlayback.class));
        main.postDelayed(() -> {
            if (request != generation) return;
            if (!automatic) {
                if (target != tasks.current()) {
                    finish("The foreground app changed. Prepare the switch again in that app.");
                    return;
                }
                if (pausePlayback(target)) {
                    waitForPause(request, SystemClock.elapsedRealtime() + 2500);
                    return;
                }
            }
            waitForSwitch(request);
        }, automatic ? 100 : 350);
    }

    private void waitForPause(int request, long pauseDeadline) {
        if (request != generation) return;
        int state = playerState(savedApp);
        if (state == PlaybackState.STATE_PAUSED) {
            waitForSwitch(request);
        } else if (SystemClock.elapsedRealtime() >= pauseDeadline) {
            finish("Playback is still active. Pause it in the player before switching.");
        } else {
            if (state == PlaybackState.STATE_PLAYING) pausePlayback(savedApp);
            main.postDelayed(() -> waitForPause(request, pauseDeadline), PAUSE_CHECK_MS);
        }
    }

    private void waitForSwitch(int request) {
        paused = true;
        deadline = SystemClock.elapsedRealtime() + (homeHandoffStartedAt != 0 ? HOME_HANDOFF_MS : ARM_MS);
        if (!automaticAudioSwitch() && waitForPlayer) coverScreen(true);
        service.showStatus(automaticAudioSwitch() ? "Waiting for the new audio route to settle."
                : "Ready. Use the glasses button to switch now.", blackScreen == null);
        poll(request);
    }

    private void poll(int request) {
        if (request != generation) return;
        if (!checkDisplay()) return;
        if (SystemClock.elapsedRealtime() >= deadline) {
            finish("Switch wait ended.");
            return;
        }
        boolean pausePending = expectedUsb == 1 && waitForPlayer && !pauseFocusTried
                && savedApp == tasks.current() && playerState(savedApp) == PlaybackState.STATE_PLAYING;
        if (pausePending) pausePlayback(savedApp);
        reader.post(() -> {
            int currentOutput = output();
            main.post(() -> {
                if (request != generation) return;
                int candidate = expectedUsb == -1 || (currentOutput & 1) == expectedUsb ? currentOutput : 0;
                if (readiness.ready(candidate, SystemClock.elapsedRealtime())) {
                    stableOutput = currentOutput;
                    Log.i(TAG, "New display mode and audio route settled");
                    if (homeHandoffStartedAt != 0) waitForHome(request);
                    else returnToApp(null);
                } else {
                    main.postDelayed(() -> poll(request), pausePending ? PAUSE_CHECK_MS : POLL_MS);
                }
            });
        });
    }

    void returnToApp(Activity activity) {
        if (!armed || stableOutput == 0 || (activity != null && !awaitingTap)) {
            if (activity != null) activity.finish();
            return;
        }
        if (!checkDisplay()) {
            if (activity != null) activity.finish();
            return;
        }
        int request = generation;
        awaitingTap = false;
        service.showStatus("Checking the saved app…", false);
        reader.post(() -> {
            int route = output();
            main.post(() -> {
                if (request != generation) {
                    if (activity != null) activity.finish();
                    return;
                }
                long evaAt = tasks.evaForegroundAt();
                long readyBy = SystemClock.elapsedRealtime() + PLAYER_READY_MS
                        + readiness.playerSettleMillis(stableOutput);
                if (!tasks.canReturnTo(savedApp)) {
                    finish("The saved app changed.");
                } else if (route != stableOutput) {
                    settleAgain(request);
                } else if (savedApp == tasks.current()) {
                    if (waitForPlayer) {
                        confirmReturn(request, activity, readyBy);
                        return;
                    }
                    finishSettled();
                } else if (activity != null || evaAt >= 0) {
                    if (activity == null) {
                        if (!Settings.canDrawOverlays(service)) {
                            offerReturn(request);
                            return;
                        }
                        long wait = EVA_SWITCH_DELAY_MS - (System.currentTimeMillis() - evaAt);
                        if (wait > 0 && wait <= EVA_SWITCH_DELAY_MS) {
                            if (SystemClock.elapsedRealtime() >= deadline) {
                                offerReturn(request);
                                return;
                            }
                            main.postDelayed(() -> {
                                if (request == generation) returnToApp(null);
                            }, Math.min(wait, POLL_MS));
                            return;
                        }
                    }
                    if (waitForPlayer) coverScreen(false);
                    try {
                        service.getSystemService(ActivityManager.class).moveTaskToFront(savedApp.id, 0);
                    } catch (RuntimeException error) {
                        Log.w(TAG, "Android did not allow returning to the saved app", error);
                    }
                    main.postDelayed(() -> confirmReturn(request, activity, readyBy),
                            waitForPlayer ? PAUSE_CHECK_MS : APP_REOPEN_MS);
                    return;
                } else {
                    finish("Another app is open.");
                }
                if (activity != null) activity.finish();
            });
        });
    }

    private void confirmReturn(int request, Activity activity, long readyBy) {
        if (request != generation || !checkDisplay()) {
            if (activity != null) activity.finish();
            return;
        }
        reader.post(() -> {
            int route = output();
            main.post(() -> {
                if (request == generation) {
                    long now = SystemClock.elapsedRealtime();
                    long foregroundAt = tasks.foregroundSince(savedApp);
                    if (!tasks.canReturnTo(savedApp)) {
                        finish("The saved app changed.");
                    } else if (route != stableOutput) {
                        settleAgain(request);
                    } else if (waitForPlayer ? playerReady(foregroundAt, now) : foregroundAt >= 0) {
                        if (!waitForPlayer) pausePlayback(savedApp);
                        finishSettled();
                    } else if (waitForPlayer && now < readyBy) {
                        main.postDelayed(() -> confirmReturn(request, activity, readyBy), PAUSE_CHECK_MS);
                        return;
                    } else if (activity == null && tasks.evaForegroundAt() >= 0) {
                        offerReturn(request);
                    } else if (foregroundAt >= 0) {
                        finish("Could not confirm the player was paused. Check it before resuming.");
                    } else {
                        finish("Open the saved app when ready.");
                    }
                }
                if (activity != null) activity.finish();
            });
        });
    }

    private void settleAgain(int request) {
        stableOutput = 0;
        readiness.ready(0, SystemClock.elapsedRealtime());
        poll(request);
    }

    private void offerReturn(int request) {
        clearScreen();
        awaitingTap = true;
        main.postDelayed(() -> {
            if (request == generation && awaitingTap) finish("Open the saved app when ready.");
        }, RETURN_MS);
        service.showStatus("Switch settled. Tap Return to app.", true);
    }

    private void finishSettled() {
        Log.i(TAG, "Switch settled in " + savedApp.packageName);
        finish("Switch settled. Continue in the app when ready.", (stableOutput & 1) == 0, true);
    }

    void finish(String status) {
        finish(status, false, false);
    }

    private void finish(String status, boolean displayPortSettled, boolean reveal) {
        if (!armed) return;
        generation++;
        main.removeCallbacksAndMessages(null);
        armed = false;
        paused = false;
        awaitingTap = false;
        stableOutput = 0;
        boolean home = homeHandoffStartedAt != 0;
        homeHandoffStartedAt = 0;
        expectedUsb = -1;
        savedApp = null;
        enumeratingPlayer = null;
        Log.i(TAG, status);
        boolean covered = blackScreen != null;
        if (reveal && covered) {
            Log.i(TAG, (home ? "Beam Home fully drawn." : "Player paused.") + " Fading in over " + FADE_MS + " ms");
            View cover = blackScreen;
            cover.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> cover.postOnAnimation(() -> {
                if (blackScreen == cover && cover.getAlpha() == 0f) clearScreen();
            }));
        } else {
            clearScreen();
        }
        service.showStatus(status, !reveal || !covered);
        SilencePlayback.startIfEnabled(service, displayPortSettled);
    }

    void close() {
        generation++;
        closed = true;
        main.removeCallbacksAndMessages(null);
        clearScreen();
        reader.removeCallbacksAndMessages(null);
        thread.quitSafely();
        synchronized (tasks) {
            if (activityLog != null) activityLog.destroy();
            if (usbLog != null) usbLog.destroy();
        }
    }

    private boolean checkDisplay() {
        if (service.getSystemService(PowerManager.class).isInteractive()
                && SilencePlayback.GLASSES_PORT_PARTNER.exists()) return true;
        finish("Display unavailable.");
        return false;
    }

    private void acknowledgeUsbCover(int request) {
        afterCoverCommit(request, "USB", () -> GlassesCover.acknowledge(service));
    }

    private void afterCoverCommit(int request, String route, Runnable acknowledgement) {
        View cover = blackScreen;
        if (cover == null || Build.VERSION.SDK_INT < 29) return;
        cover.post(() -> {
            if (request != generation || cover != blackScreen || cover.getAlpha() != 1f) return;
            cover.getViewTreeObserver().registerFrameCommitCallback(() -> cover.postOnAnimation(() -> {
                if (request == generation && cover == blackScreen && cover.getAlpha() == 1f) {
                    Log.i(TAG, "Black cover frame committed for " + route + " handoff");
                    reader.post(acknowledgement);
                }
            }));
            cover.invalidate();
        });
    }

    private void coverScreen(boolean fade) {
        if (blackScreen != null) {
            blackScreen.animate().cancel();
            blackScreen.setAlpha(1f);
            return;
        }
        if (!Settings.canDrawOverlays(service)) return;
        View view = new View(service);
        view.setBackgroundColor(Color.BLACK);
        view.setAlpha(fade ? 0f : 1f);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.setTitle("Beam switch blackout");
        try {
            service.getSystemService(WindowManager.class).addView(view, params);
            blackScreen = view;
            if (fade) view.animate().alpha(1f).setDuration(FADE_MS);
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not cover the glasses display", error);
        }
    }

    private void clearScreen() {
        if (blackScreen == null) return;
        View view = blackScreen;
        blackScreen = null;
        view.animate().cancel();
        service.getSystemService(WindowManager.class).removeViewImmediate(view);
    }

    private boolean hasPlayer(ForegroundTasks.Task target) {
        return ForegroundTasks.VLC.equals(target.component)
                || (PlayerAccess.granted(service) && PlayerAccess.hasSession(service, target.packageName));
    }

    private int playerState(ForegroundTasks.Task target) {
        if (PlayerAccess.granted(service)) {
            MediaController player = PlayerAccess.session(service, target.packageName, false);
            PlaybackState state = player == null ? null : player.getPlaybackState();
            if (state != null) return state.getState();
        }
        return ForegroundTasks.VLC.equals(target.component) ? tasks.vlcPlaybackState() : -1;
    }

    private boolean playerReady(long foregroundAt, long now) {
        int state = foregroundAt >= 0 && System.currentTimeMillis() - foregroundAt >= APP_REOPEN_MS
                ? playerState(savedApp) : -1;
        if (state == PlaybackState.STATE_PLAYING) pausePlayback(savedApp);
        return readiness.playerReady(stableOutput, state == PlaybackState.STATE_PAUSED, now);
    }

    private int output() {
        int width = ModeAudioRecovery.modeWidth(displays);
        if (width == 0 || !SilencePlayback.GLASSES_PORT_PARTNER.exists() || !SilencePlayback.readDpLink()) return 0;
        boolean usb = false;
        boolean speaker = false;
        for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (device.getType()) {
                case AudioDeviceInfo.TYPE_USB_DEVICE:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                    if (!device.getProductName().toString().contains("XREAL")) return 0;
                    usb = true;
                    break;
                case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                case AudioDeviceInfo.TYPE_HEARING_AID:
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                    return 0;
                case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                    speaker = true;
                    break;
            }
        }
        return usb ? width + 1 : speaker ? width : 0;
    }

    private boolean pausePlayback(ForegroundTasks.Task target) {
        long now = SystemClock.elapsedRealtime();
        if (target != tasks.current()) return false;
        if (PlayerAccess.granted(service)) {
            MediaController player = PlayerAccess.playing(service, target.packageName);
            if (player == null) return false;
            MediaSession.Token session = player.getSessionToken();
            boolean sameSession = session.equals(pauseRequestedTo);
            boolean fallback = sameSession && (!pauseKeySent || (expectedUsb == 1 && !pauseFocusTried))
                    && PlayerAccess.isOnlySession(service, session);
            if (now - pauseRequestedAt < (fallback ? PAUSE_CHECK_MS : PAUSE_RETRY_MS)) return false;
            if (fallback && !pauseKeySent) {
                pauseKeySent = true;
                dispatchPauseKey();
                Log.i(TAG, "Sent fallback media Pause key to " + target.packageName);
            } else if (fallback) {
                pauseFocusTried = true;
                // A transient focus loss lets the player resume when we release focus.
                if (audio.requestAudioFocus(pauseFocus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    audio.abandonAudioFocusRequest(pauseFocus);
                    Log.i(TAG, "Requested Pause via audio focus in " + target.packageName);
                }
            } else {
                if (!sameSession) {
                    pauseKeySent = false;
                    pauseFocusTried = false;
                }
                player.getTransportControls().pause();
                pauseRequestedTo = session;
                Log.i(TAG, "Requested Pause in " + target.packageName);
            }
        } else {
            if (now - pauseRequestedAt < PAUSE_RETRY_MS || !ForegroundTasks.VLC.equals(target.component)
                    || tasks.vlcPlaybackState() != PlaybackState.STATE_PLAYING) return false;
            dispatchPauseKey();
            Log.i(TAG, "Requested Pause in " + target.packageName);
        }
        pauseRequestedAt = now;
        return true;
    }

    private void dispatchPauseKey() {
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE));
    }

    private void startLogMonitor() {
        if (service.checkSelfPermission(Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) return;
        synchronized (tasks) {
            if (closed) return;
            if (logThread == null || !logThread.isAlive()) {
                logThread = new Thread(() -> monitorLog(false), "Beam foreground app log");
                logThread.start();
            }
            if (usbLogThread == null || !usbLogThread.isAlive()) {
                usbLogThread = new Thread(() -> monitorLog(true), "Beam USB enumeration log");
                usbLogThread.start();
            }
        }
    }

    private void monitorLog(boolean kernel) {
        Process log = null;
        try {
            log = kernel ? ForegroundTasks.openUsbLog() : ForegroundTasks.openLog();
            synchronized (tasks) {
                if (closed) return;
                if (kernel) usbLog = log;
                else activityLog = log;
            }
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(log.getInputStream()))) {
                for (String line; (line = lines.readLine()) != null; ) {
                    int route = tasks.accept(line);
                    if (route >= 0) {
                        long elapsed = System.currentTimeMillis() - ForegroundTasks.epochMillis(line);
                        if (elapsed < 0 || elapsed > 2000) continue;
                        ForegroundTasks.Task atChange = tasks.current();
                        boolean wasHome = tasks.evaForegroundAt() >= 0;
                        main.post(() -> {
                            if (route == ForegroundTasks.USB_ENUMERATING) coverEarlyUsb(atChange);
                            else onAudioSwitch(route, atChange, wasHome);
                        });
                    }
                }
            }
        } catch (IOException error) {
            Log.w(TAG, kernel ? "Could not follow USB enumeration" : "Could not follow foreground app events", error);
        } finally {
            if (log != null) log.destroy();
            synchronized (tasks) {
                if (kernel) usbLog = null;
                else activityLog = null;
            }
        }
    }
}
