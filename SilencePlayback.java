package local.xreal.beamaudiokeepalive;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

public final class SilencePlayback extends Service {
    private static final String TAG = "BeamAudioKeepalive";
    private static final String CHANNEL = "playback";
    private static final String STOP = "stop";
    private static final String AUTO = "auto";
    private static final int SAMPLE_RATE = 48000;
    private static final long POLL_MS = 500;
    // shortcut: this stock Beam's mixer enters standby 3 s after its last track stops.
    private static final long AUDIO_IDLE_MS = 4000;
    // The Air 2 Pro can drop and retrain its first link while selecting DP audio.
    private static final long ATTACH_SETTLE_MS = 4500;
    private static final long RECOVERY_LIMIT_MS = 8000;
    static final String OS_384 =
            "XREAL/eva/eva:11/RQ3A.210705.001/EVA-202412091511-384:user/release-keys";
    private static final String BEAM_HOME = "ai.nreal.evalauncher";
    private static final String HOME_ACTIVITY = "ai.nreal.cast.UnityCastActivity";
    static final File GLASSES_PORT_PARTNER = new File("/sys/class/typec/port0-partner");
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::pollLink;
    private HandlerThread readerThread;
    private Handler reader;
    private AudioManager audio;
    private PowerManager power;
    private AudioFocusRequest focus;
    private boolean automatic;
    private boolean linkConnected;
    private boolean eligible;
    private boolean focusAttempted;
    private boolean homeResetAttempted;
    private boolean holdingFocus;
    private long focusDeadline;
    private long pluggedSince = -1;
    private long quietSince = -1;
    private int generation;
    private String status;
    private AudioTrack track;

    static boolean autoEnabled(Context context) {
        return context.getSharedPreferences("keepalive", MODE_PRIVATE).getBoolean(AUTO, false);
    }

    static void setAutoEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("keepalive", MODE_PRIVATE).edit().putBoolean(AUTO, enabled).apply();
    }

    static void startIfEnabled(Context context) {
        if (autoEnabled(context) && !ModeAudioRecovery.isRunning()) {
            context.startForegroundService(new Intent(context, SilencePlayback.class).setAction(AUTO));
        }
    }

    public static final class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                    || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
                startIfEnabled(context);
            }
            if (Intent.ACTION_BOOT_COMPLETED.equals(action) && BootVideoRecovery.enabled(context)) {
                PendingResult result = goAsync();
                new Thread(() -> {
                    try {
                        BootVideoRecovery.recover(context);
                    } finally {
                        result.finish();
                    }
                }, "BeamBootVideo").start();
            }
        }
    }

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            restartLinkCheck();
        }
    };

    private final AudioDeviceCallback devices = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            if (automatic) updateAutomaticPlayback();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            if (automatic) updateAutomaticPlayback();
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            setAutoEnabled(this, false);
        }
        if (!autoEnabled(this) || ModeAudioRecovery.isRunning()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!automatic) startAutomatic();
        return START_STICKY;
    }

    private void startAutomatic() {
        automatic = true;
        releaseTrack();
        showStatus("Waiting for DisplayPort audio");
        audio = getSystemService(AudioManager.class);
        power = getSystemService(PowerManager.class);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(mediaAttributes())
                .setOnAudioFocusChangeListener(change -> {
                    if (change == AudioManager.AUDIOFOCUS_LOSS
                            || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) releaseFocus();
                }, main)
                .build();
        readerThread = new HandlerThread("Beam DP connection");
        readerThread.start();
        reader = new Handler(readerThread.getLooper());
        IntentFilter screenEvents = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screenEvents.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screen, screenEvents);
        audio.registerAudioDeviceCallback(devices, main);
        restartLinkCheck();
    }

    private void restartLinkCheck() {
        if (!automatic) return;
        generation++;
        main.removeCallbacks(poll);
        linkConnected = false;
        pluggedSince = -1;
        quietSince = -1;
        updateAutomaticPlayback();
        if (power.isInteractive()) main.post(poll);
    }

    private void pollLink() {
        if (!automatic || !power.isInteractive()) return;
        int request = generation;
        reader.post(() -> {
            boolean plugged = GLASSES_PORT_PARTNER.exists();
            boolean connected = plugged && readDpLink();
            main.post(() -> {
                if (!automatic || request != generation) return;
                if (!plugged) pluggedSince = -1;
                else if (pluggedSince < 0) pluggedSince = SystemClock.elapsedRealtime();
                linkConnected = connected;
                updateAutomaticPlayback();
                main.postDelayed(poll, POLL_MS);
            });
        });
    }

    private static boolean readDpLink() {
        Process getprop = null;
        try {
            getprop = new ProcessBuilder("/system/bin/getprop", "vendor.hwc.device.display-0").start();
            try (BufferedReader output = new BufferedReader(new InputStreamReader(getprop.getInputStream()))) {
                String value = output.readLine();
                return value != null && value.startsWith("eDP-1:") && value.endsWith(":connected");
            }
        } catch (IOException error) {
            Log.w(TAG, "Could not read the Beam display connection", error);
            return false;
        } finally {
            if (getprop != null) getprop.destroy();
        }
    }

    private boolean usingDpOutput() {
        if (!linkConnected || !power.isInteractive()) return false;
        boolean speaker = false;
        for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (device.getType()) {
                case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                    speaker = true;
                    break;
                case AudioDeviceInfo.TYPE_USB_DEVICE:
                case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                case AudioDeviceInfo.TYPE_HEARING_AID:
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                    return false;
            }
        }
        return speaker;
    }

    private void updateAutomaticPlayback() {
        long now = SystemClock.elapsedRealtime();
        if (!usingDpOutput()) {
            if (eligible) {
                Log.i(TAG, "DP audio unavailable");
                focusAttempted = false;
                homeResetAttempted = false;
            }
            eligible = false;
            if (track != null) quietSince = -1;
            releaseTrack();
            releaseFocus();
            if (power.isInteractive()) observeIdleOutput(now);
            showStatus("Waiting for DisplayPort audio");
            return;
        }
        if (!eligible) {
            eligible = true;
            Log.i(TAG, "DP audio connected");
            showStatus("Preparing the DisplayPort audio output");
        }
        if (holdingFocus && now >= focusDeadline) releaseFocus();
        if (track != null) {
            if (track.getPlaybackHeadPosition() != 0) releaseFocus();
            return;
        }
        boolean idle = observeIdleOutput(now);
        if (now - pluggedSince < ATTACH_SETTLE_MS) return;
        if (!idle) {
            if (reopenHomeOutput(now)) {
                if (usingDpOutput() && startTrack()) {
                    showStatus("Keeping DisplayPort audio open");
                }
                return;
            }
            if (!focusAttempted) {
                focusAttempted = true;
                holdingFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                focusDeadline = now + RECOVERY_LIMIT_MS;
                if (holdingFocus) Log.i(TAG, "Requested a pause to reopen the DP output");
            }
            if (!holdingFocus) showStatus("Waiting for the audio output to become idle");
            return;
        }
        if (now - quietSince < AUDIO_IDLE_MS) return;
        if (startTrack()) {
            showStatus("Keeping DisplayPort audio open");
        } else {
            quietSince = now;
            releaseFocus();
            showStatus("Retrying the DisplayPort audio output");
        }
    }

    private boolean reopenHomeOutput(long now) {
        if (homeResetAttempted || !OS_384.equals(Build.FINGERPRINT)
                || (focusAttempted && now >= focusDeadline)
                || audio.getMode() != AudioManager.MODE_NORMAL) return false;
        AppOpsManager appOps = getSystemService(AppOpsManager.class);
        if (appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName()) != AppOpsManager.MODE_ALLOWED) return false;

        try {
            List<AudioPlaybackConfiguration> playing = audio.getActivePlaybackConfigurations();
            if (playing.size() != 1 || !audio.getActiveRecordingConfigurations().isEmpty()) return false;
            AudioAttributes playback = playing.get(0).getAudioAttributes();
            if (playback.getUsage() != AudioAttributes.USAGE_MEDIA
                    || playback.getContentType() != AudioAttributes.CONTENT_TYPE_MUSIC
                    || !isBeamHomeResumed()) return false;

            homeResetAttempted = true;
            // On this Beam, the mode transition reapplies the speaker route and reopens its PCM output.
            try {
                audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
                if (audio.getMode() != AudioManager.MODE_IN_COMMUNICATION) return false;
            } finally {
                audio.setMode(AudioManager.MODE_NORMAL);
            }
            Log.i(TAG, "Reapplied the DP audio route on the Beam home screen");
            return true;
        } catch (RuntimeException error) {
            homeResetAttempted = true;
            Log.w(TAG, "Could not reapply the Beam home audio route", error);
            return false;
        }
    }

    private boolean isBeamHomeResumed() {
        long now = System.currentTimeMillis();
        long sinceBoot = now - SystemClock.elapsedRealtime();
        UsageEvents events = getSystemService(UsageStatsManager.class).queryEvents(sinceBoot, now);
        if (events == null) return false;
        UsageEvents.Event event = new UsageEvents.Event();
        boolean home = false;
        while (events.getNextEvent(event)) {
            boolean launcher = BEAM_HOME.equals(event.getPackageName()) && HOME_ACTIVITY.equals(event.getClassName());
            if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                home = launcher;
            } else if (launcher && event.getEventType() == UsageEvents.Event.ACTIVITY_PAUSED) {
                home = false;
            }
        }
        return home;
    }

    private boolean observeIdleOutput(long now) {
        if (audio.isMusicActive()) {
            quietSince = -1;
            return false;
        }
        if (quietSince < 0) quietSince = now;
        return true;
    }

    private void stopAutomatic() {
        if (!automatic) return;
        automatic = false;
        generation++;
        main.removeCallbacks(poll);
        audio.unregisterAudioDeviceCallback(devices);
        unregisterReceiver(screen);
        readerThread.quitSafely();
        eligible = false;
        linkConnected = false;
        focusAttempted = false;
        homeResetAttempted = false;
        pluggedSince = -1;
        quietSince = -1;
        releaseFocus();
        releaseTrack();
    }

    private boolean startTrack() {
        try {
            int frames = SAMPLE_RATE / 2;
            short[] silence = new short[frames * 2];
            track = new AudioTrack.Builder()
                    .setAudioAttributes(mediaAttributes())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(silence.length * Short.BYTES)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            if (track.write(silence, 0, silence.length) != silence.length
                    || track.setLoopPoints(0, frames, -1) != AudioTrack.SUCCESS) {
                throw new IllegalStateException("Could not queue the silence loop");
            }
            track.play();
            Log.i(TAG, "DP keepalive track started");
            return true;
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not start audio", error);
            releaseTrack();
            return false;
        }
    }

    private static AudioAttributes mediaAttributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();
    }

    private void releaseTrack() {
        if (track != null) {
            track.release();
            track = null;
        }
    }

    private void releaseFocus() {
        if (holdingFocus) {
            holdingFocus = false;
            audio.abandonAudioFocusRequest(focus);
        }
    }

    private void showStatus(String message) {
        if (message.equals(status)) return;
        status = message;
        startForeground(1, notification());
    }

    private Notification notification() {
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Audio keepalive", NotificationManager.IMPORTANCE_LOW));
        PendingIntent controls = PendingIntent.getActivity(this, 0, new Intent(this, Controls.class),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 0, new Intent(this, SilencePlayback.class).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_audio_notification)
                .setContentTitle("Beam Auto Keepalive")
                .setContentText(status)
                .setContentIntent(controls)
                .addAction(new Notification.Action.Builder(null, "Turn off", stop).build())
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        stopAutomatic();
        releaseTrack();
        main.removeCallbacksAndMessages(null);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
