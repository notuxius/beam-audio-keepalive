package local.xreal.beamaudiokeepalive;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

public final class PlaybackProtection extends Service {
    private static final String TAG = "BeamPlaybackProtection";
    private static final String CHANNEL = "playback_protection";
    static final String PREPARE = "prepare_switch";
    static final String CANCEL = "cancel_switch";
    static PreparedSwitch preparation;
    private VirtualDisplay display;

    static boolean switchArmed() {
        return preparation != null && preparation.armed();
    }

    static boolean enabled(Context context) {
        return SilencePlayback.OS_384.equals(Build.FINGERPRINT)
                && context.getSharedPreferences("keepalive", MODE_PRIVATE).getBoolean(CHANNEL, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("keepalive", MODE_PRIVATE).edit().putBoolean(CHANNEL, enabled).apply();
    }

    static void startIfEnabled(Context context) {
        if (enabled(context)) context.startForegroundService(new Intent(context, PlaybackProtection.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!enabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (display == null) {
            getSystemService(NotificationManager.class).createNotificationChannel(
                    new NotificationChannel(CHANNEL, "Playback Protection", NotificationManager.IMPORTANCE_LOW));
            startForeground(3, notification("Apps stay open when EVA resumes."));
            try {
                // A private virtual display keeps Beam OS 384 from force-stopping other tasks when EVA resumes.
                display = getSystemService(DisplayManager.class).createVirtualDisplay(
                        "Beam Playback Protection", 64, 64, 160, null,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
                if (display == null) throw new IllegalStateException("Display creation returned null");
                preparation = new PreparedSwitch(this);
                PrepareSwitchTile.refresh(this);
                Log.i(TAG, "Playback protection active");
            } catch (RuntimeException error) {
                Log.e(TAG, "Could not enable playback protection", error);
                Toast.makeText(this, "Could not enable Playback Protection", Toast.LENGTH_LONG).show();
                setEnabled(this, false);
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        String action = intent == null ? null : intent.getAction();
        if (PREPARE.equals(action)) preparation.prepare();
        if (CANCEL.equals(action)) preparation.finish("Switch cancelled.");
        return START_STICKY;
    }

    void showStatus(String status, boolean announce) {
        getSystemService(NotificationManager.class).notify(3, notification(status));
        PrepareSwitchTile.refresh(this);
        if (announce) Toast.makeText(this, status, Toast.LENGTH_LONG).show();
    }

    private Notification notification(String status) {
        PendingIntent controls = PendingIntent.getActivity(this, 0, new Intent(this, Controls.class),
                PendingIntent.FLAG_IMMUTABLE);
        boolean armed = switchArmed();
        PendingIntent action = PendingIntent.getService(this, 0,
                new Intent(this, PlaybackProtection.class).setAction(armed ? CANCEL : PREPARE),
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_audio_notification)
                .setContentTitle("Beam Playback Protection")
                .setStyle(new Notification.BigTextStyle().bigText(status))
                .setContentText(status).setContentIntent(controls).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, armed ? "Cancel" : "Prepare switch", action).build());
        if (armed && preparation.awaitingTap()) {
            PendingIntent resume = PendingIntent.getActivity(this, 0, new Intent(this, ReturnToApp.class),
                    PendingIntent.FLAG_IMMUTABLE);
            notification.addAction(new Notification.Action.Builder(null, "Return to app", resume).build());
        }
        return notification.build();
    }

    public static final class ReturnToApp extends Activity {
        @Override
        public void onCreate(Bundle state) {
            super.onCreate(state);
            if (preparation == null) finish();
            else preparation.returnToApp(this);
            getWindow().getDecorView().postDelayed(this::finish, 5000);
        }
    }

    @Override
    public void onDestroy() {
        boolean wasArmed = switchArmed();
        if (preparation != null) preparation.close();
        preparation = null;
        PrepareSwitchTile.refresh(this);
        if (display != null) display.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
        if (wasArmed) SilencePlayback.startIfEnabled(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
