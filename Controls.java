package local.xreal.beamaudiokeepalive;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

public final class Controls extends Activity {
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);

        LinearLayout content = new LinearLayout(this);
        content.setGravity(Gravity.CENTER);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);

        boolean recovering = ModeAudioRecovery.isRunning();
        Switch automatic = new Switch(this);
        automatic.setText("Auto Keepalive");
        automatic.setTextSize(18);
        automatic.setSwitchPadding(padding / 2);
        automatic.setMinHeight(padding * 2);
        automatic.setChecked(SilencePlayback.autoEnabled(this));
        automatic.setOnCheckedChangeListener((view, enabled) -> {
            SilencePlayback.setAutoEnabled(this, enabled);
            if (enabled) {
                SilencePlayback.startIfEnabled(this);
            } else {
                stopService(new Intent(this, SilencePlayback.class));
            }
            finishAndRemoveTask();
        });
        content.addView(automatic, LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        if (recovering && automatic.isChecked()) {
            TextView status = new TextView(this);
            status.setText("Auto Keepalive is paused during 2D/3D recovery.");
            status.setGravity(Gravity.CENTER);
            content.addView(status);
        }

        Button troubleshootingButton = new Button(this, null, android.R.attr.borderlessButtonStyle);
        troubleshootingButton.setText("Show troubleshooting");
        content.addView(troubleshootingButton, LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        LinearLayout troubleshooting = new LinearLayout(this);
        troubleshooting.setOrientation(LinearLayout.VERTICAL);
        troubleshooting.setVisibility(View.GONE);
        content.addView(troubleshooting, LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        troubleshootingButton.setOnClickListener(view -> {
            boolean expanding = troubleshooting.getVisibility() != View.VISIBLE;
            troubleshooting.setVisibility(expanding ? View.VISIBLE : View.GONE);
            troubleshootingButton.setText(expanding ? "Hide troubleshooting" : "Show troubleshooting");
        });

        Button recovery = new Button(this);
        recovery.setText(recovering ? "Stop 2D/3D audio recovery" : "Start 2D/3D audio recovery");
        recovery.setOnClickListener(view -> {
            Intent service = new Intent(this, ModeAudioRecovery.class);
            if (recovering) {
                stopService(service);
            } else {
                startForegroundService(service);
            }
            finishAndRemoveTask();
        });
        troubleshooting.addView(recovery);

        if (SilencePlayback.OS_384.equals(Build.FINGERPRINT)) {
            Button videoRecovery = new Button(this);
            DevicePolicyManager policy = getSystemService(DevicePolicyManager.class);
            ComponentName admin = new ComponentName(this, BootVideoRecovery.class);
            boolean adminActive = policy.isAdminActive(admin);
            videoRecovery.setText(adminActive ? "Disable boot video recovery" : "Enable boot video recovery");
            videoRecovery.setOnClickListener(view -> {
                if (policy.isAdminActive(admin)) {
                    policy.removeActiveAdmin(admin);
                } else {
                    startActivity(new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin));
                }
                finishAndRemoveTask();
            });
            troubleshooting.addView(videoRecovery);
            if (adminActive && !BootVideoRecovery.enabled(this)) {
                TextView status = new TextView(this);
                status.setText("Boot video recovery needs log access.");
                status.setGravity(Gravity.CENTER);
                troubleshooting.addView(status);
            }
        }
        Button done = new Button(this, null, android.R.attr.borderlessButtonStyle);
        done.setText("Done");
        done.setOnClickListener(view -> finishAndRemoveTask());
        content.addView(done, LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);

        TextView version = new TextView(this);
        try {
            version.setText("Version " + getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName);
        } catch (PackageManager.NameNotFoundException ignored) {
            version.setText("Version unavailable");
        }
        version.setTextSize(14);
        version.setGravity(Gravity.CENTER);
        version.setPadding(0, padding / 4, 0, 0);
        content.addView(version);
        setContentView(content);
        SilencePlayback.startIfEnabled(this);
    }
}
