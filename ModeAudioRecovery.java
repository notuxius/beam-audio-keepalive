package local.xreal.beamaudiokeepalive;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Display;

public final class ModeAudioRecovery extends Service implements DisplayManager.DisplayListener {
    private static final String TAG = "BeamModeAudioRecovery";
    private static final String CHANNEL = "mode_audio_recovery";
    private static final String STOP = "stop";
    // shortcut: wait past the measured Beam standby delay, recheck if the HAL timeout changes.
    private static final long PAUSE_MS = 4500;
    private static boolean running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable releaseFocus = this::releaseFocus;
    private DisplayManager displays;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private boolean holdingFocus;
    private int lastWidth;

    static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        displays = getSystemService(DisplayManager.class);
        audio = getSystemService(AudioManager.class);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA).build())
                .build();
        lastWidth = modeWidth(displays);
        displays.registerDisplayListener(this, handler);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(2, notification());
        running = true;
        stopService(new Intent(this, SilencePlayback.class));
        return START_NOT_STICKY;
    }

    @Override
    public void onDisplayChanged(int displayId) {
        if (displayId != Display.DEFAULT_DISPLAY) return;
        int width = modeWidth(displays);
        if (width == 0 || width == lastWidth) return;
        int previousWidth = lastWidth;
        lastWidth = width;
        if (previousWidth == 0 || PlaybackProtection.switchArmed()) return;

        for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (device.getType() == AudioDeviceInfo.TYPE_USB_HEADSET
                    || device.getType() == AudioDeviceInfo.TYPE_USB_DEVICE) return;
        }
        if (!holdingFocus) {
            if (!audio.isMusicActive()) return;
            holdingFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (!holdingFocus) return;
        }
        Log.i(TAG, "Pausing for audio standby after " + previousWidth + " to " + width);
        handler.removeCallbacks(releaseFocus);
        handler.postDelayed(releaseFocus, PAUSE_MS);
    }

    static int modeWidth(DisplayManager displays) {
        Display display = displays.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) return 0;
        Display.Mode mode = display.getMode();
        int width = mode.getPhysicalWidth();
        return mode.getPhysicalHeight() == 1080 && (width == 1920 || width == 3840) ? width : 0;
    }

    private void releaseFocus() {
        if (!holdingFocus) return;
        audio.abandonAudioFocusRequest(focus);
        holdingFocus = false;
        Log.i(TAG, "Released transient audio focus");
    }

    private Notification notification() {
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "2D/3D audio recovery", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, ModeAudioRecovery.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_audio_notification)
                .setContentTitle("2D/3D audio recovery is running")
                .setContentText("Playback pauses briefly after a mode switch.")
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDisplayAdded(int displayId) {}

    @Override
    public void onDisplayRemoved(int displayId) {}

    @Override
    public void onDestroy() {
        boolean wasRunning = running;
        running = false;
        displays.unregisterDisplayListener(this);
        handler.removeCallbacks(releaseFocus);
        releaseFocus();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
        if (wasRunning) SilencePlayback.startIfEnabled(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
