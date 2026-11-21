package local.xreal.beamaudiokeepalive;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

final class PreparedSwitch implements DisplayManager.DisplayListener {
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
    private static final long USB_ENUMERATION_MS = 2000;
    private static final long DP_HANDOFF_POLL_MS = 350;
    // shortcut: match the Beam's Android display fade-out until the glasses' optical fade is measured.
    private static final long FADE_MS = 400;
    private final PlaybackProtection service;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("Beam switch checks");
    private final Handler reader;
    private final AudioManager audio;
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
    private boolean revealing;
    private int expectedUsb = -1;
    private int expectedWidth;
    private int lastWidth;
    private ForegroundTasks.Task savedApp;
    private SwitchReadiness readiness;
    private View blackScreen;
    private boolean waitForPlayer;
    private SwitchPlayer switchPlayer;
    private boolean dpHandoffAvailable;
    private long homeHandoffStartedAt;
    private boolean usbUnconfirmed;
    private final Runnable endUnconfirmedUsb = () -> {
        if (!usbUnconfirmed) return;
        usbUnconfirmed = false;
        finish("USB audio did not connect.", false, true);
    };

    PreparedSwitch(PlaybackProtection service) {
        this.service = service;
        this.audio = service.getSystemService(AudioManager.class);
        this.displays = service.getSystemService(DisplayManager.class);
        lastWidth = ModeAudioRecovery.modeWidth(displays);
        displays.registerDisplayListener(this, main);
        thread.start();
        reader = new Handler(thread.getLooper());
        startLogMonitor();
        reader.post(this::watchDpHandoff);
    }

    boolean armed() { return armed; }
    boolean paused() { return paused; }
    boolean automaticSwitch() { return expectedUsb != -1 || expectedWidth != 0; }
    boolean awaitingTap() { return awaitingTap; }

    private void coverEarlyUsb(ForegroundTasks.Task target) {
        if (armed || closed || target == null || target != tasks.current()
                || !service.getSystemService(PowerManager.class).isInteractive()
                || !SilencePlayback.GLASSES_PORT_PARTNER.exists()) return;
        if (!audio.isMusicActive() && !hasPlayer(target)) return;
        expectedUsb = 1;
        usbUnconfirmed = true;
        Log.i(TAG, "Preparing the player while USB enumerates");
        beginSwitch(target, new SwitchReadiness(0), ++generation);
        main.postDelayed(endUnconfirmedUsb, USB_ENUMERATION_MS);
    }

    @Override public void onDisplayAdded(int id) {}
    @Override public void onDisplayRemoved(int id) {}

    boolean handlesModeSwitch() {
        return !closed && (armed || (tasks.current() != null && Settings.canDrawOverlays(service)
                && service.getSystemService(PowerManager.class).isInteractive()
                && SilencePlayback.GLASSES_PORT_PARTNER.exists()));
    }

    @Override
    public void onDisplayChanged(int id) {
        if (id != Display.DEFAULT_DISPLAY) return;
        int width = ModeAudioRecovery.modeWidth(displays);
        if (width == 0 || width == lastWidth) return;
        int previous = lastWidth;
        lastWidth = width;
        if (previous == 0 || !handlesModeSwitch() || homeHandoffStartedAt != 0) return;
        if (armed && !automaticSwitch() && !revealing) return;
        ForegroundTasks.Task target = armed ? savedApp : tasks.current();
        int route = audioRoute();
        if (target == null || !tasks.canReturnTo(target)
                || (!armed && (route < 0 || (!hasPlayer(target) && !audio.isMusicActive())))) return;
        expectedWidth = width;
        Log.i(TAG, "Detected display mode change from " + previous + " to " + width);
        int initial = expectedUsb == -1 ? previous | Math.max(route, 0) : 0;
        beginSwitch(target, new SwitchReadiness(initial), ++generation);
    }

    private void onAudioSwitch(int route, ForegroundTasks.Task atChange, boolean wasHome) {
        if (closed || !service.getSystemService(PowerManager.class).isInteractive()
                || !SilencePlayback.GLASSES_PORT_PARTNER.exists()) return;
        if (homeHandoffStartedAt != 0) {
            if (route == 1) finish("USB audio returned.");
            return;
        }
        if (armed && savedApp != null && (automaticSwitch() || revealing)) {
            if (route == expectedUsb) {
                if (usbUnconfirmed) confirmUsb(generation);
                return;
            }
            usbUnconfirmed = false;
            main.removeCallbacks(endUnconfirmedUsb);
            expectedUsb = route;
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
        if (atChange == null || !tasks.canReturnTo(atChange)) return;
        expectedUsb = route;
        Log.i(TAG, "Detected " + (route == 1 ? "USB" : "DP") + " audio switch in " + atChange.packageName);
        beginSwitch(atChange, new SwitchReadiness(0), ++generation);
    }

    private void watchDpHandoff() {
        if (closed) return;
        ForegroundTasks.Task player = tasks.current();
        boolean home = tasks.evaForegroundAt() >= 0;
        if ((home || (player != null && (hasPlayer(player) || audio.isMusicActive())))
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
        revealing = false;
        awaitingTap = false;
        stableOutput = 0;
        if (savedApp != target) {
            if (switchPlayer != null) switchPlayer.releaseFocus();
            switchPlayer = new SwitchPlayer(service, audio, tasks, target);
            waitForPlayer = switchPlayer.recognized();
        }
        savedApp = target;
        readiness = change;
        boolean automatic = automaticSwitch();
        service.stopService(new Intent(service, SilencePlayback.class));
        if (automatic) {
            if (waitForPlayer) {
                coverScreen(false);
                if (expectedUsb == 1 && !usbUnconfirmed) acknowledgeUsbCover(request);
            } else {
                clearScreen();
            }
            if (waitForPlayer) switchPlayer.pause(true);
        }
        main.postDelayed(() -> {
            if (request != generation) return;
            if (!automatic) {
                if (target != tasks.current()) {
                    finish("The foreground app changed. Prepare the switch again in that app.");
                    return;
                }
                if (waitForPlayer || switchPlayer.active()) {
                    waitForPlayer = true;
                    coverScreen(true);
                    main.postDelayed(() -> waitForPause(request, SystemClock.elapsedRealtime() + 2500),
                            blackScreen == null ? 0 : FADE_MS);
                    return;
                }
            }
            waitForSwitch(request);
        }, automatic ? 100 : 350);
    }

    private void waitForPause(int request, long pauseDeadline) {
        if (request != generation) return;
        if (savedApp != tasks.current()) {
            finish("The foreground app changed. Prepare the switch again in that app.");
        } else if (!switchPlayer.active() || switchPlayer.paused()) {
            waitForSwitch(request);
        } else if (SystemClock.elapsedRealtime() >= pauseDeadline) {
            finish("Playback is still active. Pause it in the player before switching.", false, true);
        } else {
            switchPlayer.pause(false);
            main.postDelayed(() -> waitForPause(request, pauseDeadline), PAUSE_CHECK_MS);
        }
    }

    private void waitForSwitch(int request) {
        paused = true;
        deadline = SystemClock.elapsedRealtime() + (homeHandoffStartedAt != 0 ? HOME_HANDOFF_MS : ARM_MS);
        service.showStatus(automaticSwitch() ? "Waiting for the display and audio route to settle."
                : "Ready. Use the glasses button to switch now.", blackScreen == null);
        poll(request);
    }

    private void poll(int request) {
        if (request != generation) return;
        if (!checkDisplay()) return;
        if (savedApp != null && !tasks.canReturnTo(savedApp)) {
            finish("The saved app changed.");
            return;
        }
        if (SystemClock.elapsedRealtime() >= deadline) {
            finish("Switch wait ended.", false, true);
            return;
        }
        boolean pausePending = pauseIfPlaying(request);
        reader.post(() -> {
            int currentOutput = output();
            main.post(() -> {
                if (request != generation) return;
                int candidate = (expectedUsb == -1 || (currentOutput & 1) == expectedUsb)
                        && (expectedWidth == 0 || (currentOutput & ~1) == expectedWidth) ? currentOutput : 0;
                if (usbUnconfirmed && candidate != 0) confirmUsb(request);
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

    private boolean pauseIfPlaying(int request) {
        if (switchPlayer == null || !switchPlayer.active()) return false;
        if (!waitForPlayer) {
            waitForPlayer = true;
            coverScreen(false);
            if (expectedUsb == 1 && !usbUnconfirmed) acknowledgeUsbCover(request);
        }
        switchPlayer.pause(automaticSwitch());
        return true;
    }

    private void confirmUsb(int request) {
        usbUnconfirmed = false;
        main.removeCallbacks(endUnconfirmedUsb);
        acknowledgeUsbCover(request);
    }

    void returnToApp(Activity activity) {
        if (!armed || revealing || stableOutput == 0 || (activity != null && !awaitingTap)) {
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
                    } else if (waitForPlayer ? playerReady(request, foregroundAt, now) : foregroundAt >= 0) {
                        finishSettled();
                    } else if (waitForPlayer && now < readyBy) {
                        main.postDelayed(() -> confirmReturn(request, activity, readyBy), PAUSE_CHECK_MS);
                        return;
                    } else if (activity == null && tasks.evaForegroundAt() >= 0) {
                        offerReturn(request);
                    } else if (foregroundAt >= 0) {
                        finish("Could not confirm the player was paused.", (stableOutput & 1) == 0, true);
                    } else {
                        finish("Open the saved app when ready.");
                    }
                }
                if (activity != null) activity.finish();
            });
        });
    }

    private void settleAgain(int request) {
        revealing = false;
        stableOutput = 0;
        if (waitForPlayer) coverScreen(false);
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
        finish("Switch settled.", (stableOutput & 1) == 0, true);
    }

    void cancel() {
        if (revealing) complete("Switch cancelled.", false, false);
        else finish("Switch cancelled.", false, true);
    }

    private void finish(String status) {
        finish(status, false, false);
    }

    private void finish(String status, boolean displayPortSettled, boolean reveal) {
        if (!armed) return;
        if (!reveal) {
            complete(status, displayPortSettled, false);
            return;
        }
        if (revealing) return;
        revealing = true;
        int request = generation;
        int expectedOutput = stableOutput;
        Runnable completeFade = () -> {
            if (request != generation || !checkDisplay()) return;
            reader.post(() -> {
                int route = output();
                main.post(() -> {
                    if (request != generation || !checkDisplay()) return;
                    if (expectedOutput != 0 && expectedOutput != route && SystemClock.elapsedRealtime() < deadline) {
                        settleAgain(request);
                    } else {
                        complete(status, displayPortSettled, route != 0 && (expectedOutput == 0 || expectedOutput == route));
                    }
                });
            });
        };
        View cover = blackScreen;
        if (cover != null) {
            Log.i(TAG, "Fading out the black cover over " + FADE_MS + " ms");
            cover.animate().alpha(0f).setDuration(FADE_MS)
                    .withEndAction(() -> afterCoverFrame(request, 0f, completeFade));
        } else {
            completeFade.run();
        }
    }

    private void complete(String status, boolean displayPortSettled, boolean resume) {
        int request = ++generation;
        main.removeCallbacks(endUnconfirmedUsb);
        armed = false;
        paused = false;
        revealing = false;
        awaitingTap = false;
        stableOutput = 0;
        homeHandoffStartedAt = 0;
        expectedUsb = -1;
        expectedWidth = 0;
        usbUnconfirmed = false;
        boolean covered = blackScreen != null;
        clearScreen();
        if (switchPlayer != null) {
            if (resume && tasks.current() == savedApp) {
                switchPlayer.resume(main, () -> request == generation && checkDisplay());
            } else {
                switchPlayer.releaseFocus();
            }
        }
        switchPlayer = null;
        savedApp = null;
        Log.i(TAG, status);
        service.showStatus(status, !covered);
        SilencePlayback.startIfEnabled(service, displayPortSettled);
    }

    void close() {
        generation++;
        closed = true;
        displays.unregisterDisplayListener(this);
        main.removeCallbacksAndMessages(null);
        clearScreen();
        if (switchPlayer != null) switchPlayer.releaseFocus();
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
        if (Build.VERSION.SDK_INT < 29) return;
        afterCoverFrame(request, 1f, () -> {
            Log.i(TAG, "Black cover frame committed for " + route + " handoff");
            reader.post(acknowledgement);
        });
    }

    private void afterCoverFrame(int request, float alpha, Runnable action) {
        View cover = blackScreen;
        if (cover == null) return;
        cover.post(() -> {
            if (request != generation || cover != blackScreen || cover.getAlpha() != alpha) return;
            Runnable afterFrame = () -> cover.postOnAnimation(() -> {
                if (request == generation && cover == blackScreen && cover.getAlpha() == alpha) action.run();
            });
            if (Build.VERSION.SDK_INT >= 29) {
                cover.getViewTreeObserver().registerFrameCommitCallback(afterFrame);
                cover.invalidate();
            } else {
                afterFrame.run();
            }
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

    private boolean playerReady(int request, long foregroundAt, long now) {
        pauseIfPlaying(request);
        boolean ready = foregroundAt >= 0 && System.currentTimeMillis() - foregroundAt >= APP_REOPEN_MS;
        return readiness.playerReady(stableOutput, ready && switchPlayer.paused(), now);
    }

    private int output() {
        int width = ModeAudioRecovery.modeWidth(displays);
        if (width == 0 || !SilencePlayback.GLASSES_PORT_PARTNER.exists() || !SilencePlayback.readDpLink()) return 0;
        int route = audioRoute();
        return route < 0 ? 0 : width | route;
    }

    private int audioRoute() {
        boolean usb = false;
        boolean speaker = false;
        for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (device.getType()) {
                case AudioDeviceInfo.TYPE_USB_DEVICE:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                    if (!device.getProductName().toString().contains("XREAL")) return -1;
                    usb = true;
                    break;
                case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                case AudioDeviceInfo.TYPE_HEARING_AID:
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                    return -1;
                case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                    speaker = true;
                    break;
            }
        }
        return usb ? 1 : speaker ? 0 : -1;
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
