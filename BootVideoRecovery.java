package local.xreal.beamaudiokeepalive;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public final class BootVideoRecovery extends DeviceAdminReceiver {
    private static final String TAG = "BeamBootVideo";
    private static final String DRIVER = "rockchip-dp fe0c0000.edp";

    static boolean enabled(Context context) {
        return SilencePlayback.OS_384.equals(Build.FINGERPRINT)
                && context.getSystemService(DevicePolicyManager.class)
                        .isAdminActive(new ComponentName(context, BootVideoRecovery.class))
                && context.checkSelfPermission(Manifest.permission.READ_LOGS)
                        == PackageManager.PERMISSION_GRANTED;
    }

    static boolean videoClockTimedOut(BufferedReader kernelLog) throws IOException {
        boolean timedOut = false;
        String line;
        while ((line = kernelLog.readLine()) != null) {
            if (!line.startsWith(DRIVER + "(", 2)) continue;
            if (line.contains("Timeout of slave video streamclk ok")) {
                timedOut = true;
            } else if (line.contains("start video!!!")) {
                timedOut = false;
            }
        }
        return timedOut;
    }

    private static boolean failedToStartVideo() throws IOException {
        Process logcat = new ProcessBuilder("/system/bin/logcat", "-b", "kernel", "-d",
                "-v", "brief", "--regex", "Timeout of slave video streamclk ok|start video!!!")
                .redirectErrorStream(true).start();
        try (BufferedReader log = new BufferedReader(new InputStreamReader(logcat.getInputStream()))) {
            boolean timedOut = videoClockTimedOut(log);
            if (logcat.waitFor() != 0) throw new IOException("Could not read the kernel log");
            return timedOut;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while reading the kernel log", e);
        } finally {
            logcat.destroy();
        }
    }

    @SuppressWarnings("deprecation")
    static void recover(Context context) {
        PowerManager power = context.getSystemService(PowerManager.class);
        try {
            if (context.getSystemService(KeyguardManager.class).isDeviceSecure()
                    || !SilencePlayback.GLASSES_PORT_PARTNER.exists() || !failedToStartVideo()
                    || !power.isInteractive()) {
                return;
            }
            PowerManager.WakeLock cpu = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":cpu");
            PowerManager.WakeLock screen = power.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    TAG + ":screen");
            cpu.acquire(5000);
            try {
                Log.i(TAG, "Boot video clock timed out. Retrying the display once.");
                context.getSystemService(DevicePolicyManager.class).lockNow();
                try {
                    SystemClock.sleep(1500);
                } finally {
                    screen.acquire(2000);
                }
            } finally {
                if (cpu.isHeld()) cpu.release();
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not retry the boot display", e);
        }
    }
}
