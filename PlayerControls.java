package local.xreal.beamaudiokeepalive;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

public final class PlayerControls extends AccessibilityService {
    private static final String TAG = "BeamPlayerControls";
    private static final String NO_PLAY_BUTTON = "couldn't find a visible Play button";
    private static PlayerControls connected;

    static boolean enabled(Context context) {
        ComponentName component = new ComponentName(context, PlayerControls.class);
        AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
        for (AccessibilityServiceInfo service : manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (component.flattenToShortString().equals(service.getId())
                    || component.flattenToString().equals(service.getId())) return true;
        }
        return false;
    }

    static boolean play(String packageName, BooleanSupplier stillPaused, boolean mx) {
        PlayerControls service = connected;
        if (service == null) return false;
        Log.i(TAG, "Trying accessibility fallback in " + packageName);
        Toast.makeText(service, "Trying accessibility fallback", Toast.LENGTH_SHORT).show();
        String failure = service.clickPlay(packageName, stillPaused, mx);
        if (failure == null) return true;
        Log.i(TAG, "Accessibility fallback " + failure + " in " + packageName);
        Toast.makeText(service, "Accessibility fallback " + failure, Toast.LENGTH_LONG).show();
        return false;
    }

    @Override
    protected void onServiceConnected() {
        connected = this;
    }

    @Override
    public void onDestroy() {
        if (connected == this) connected = null;
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    private String clickPlay(String packageName, BooleanSupplier stillPaused, boolean mx) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return NO_PLAY_BUTTON;
        List<AccessibilityNodeInfo> buttons = new ArrayList<>(2);
        try {
            if (!TextUtils.equals(packageName, root.getPackageName())) return NO_PLAY_BUTTON;
            Pattern play = playLabelPattern(localizedPlayLabel(packageName));
            findPlayButtons(root, packageName, play, mx, buttons);
            if (buttons.isEmpty()) return NO_PLAY_BUTTON;
            if (buttons.size() > 1) return "found multiple Play buttons";
            AccessibilityNodeInfo button = buttons.get(0);
            if (button.refresh() && isPlayButton(button, packageName, play, mx) && stillPaused.getAsBoolean()
                    && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return null;
            }
            return "couldn't press Play";
        } finally {
            for (AccessibilityNodeInfo button : buttons) button.recycle();
            root.recycle();
        }
    }

    private String localizedPlayLabel(String packageName) {
        try {
            Resources resources = getPackageManager().getResourcesForApplication(packageName);
            int playId = resources.getIdentifier("exo_controls_play_description", "string", packageName);
            return playId == 0 ? null : resources.getString(playId);
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException unavailable) {
            return null;
        }
    }

    static Pattern playLabelPattern(String localizedPlay) {
        String labels = "(?:play|resume)(?:\\s+(?:video|playback))?";
        if (localizedPlay != null && !localizedPlay.trim().isEmpty()) {
            labels += "|" + Pattern.quote(localizedPlay.trim());
        }
        return Pattern.compile("\\s*(?:" + labels + ")\\s*", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    static boolean matchesPlayLabel(Pattern play, CharSequence description, CharSequence text) {
        CharSequence label = description == null || description.length() == 0 ? text : description;
        return label != null && play.matcher(label).matches();
    }

    private static boolean isPlayButton(AccessibilityNodeInfo node, String packageName, Pattern play, boolean mx) {
        return TextUtils.equals(packageName, node.getPackageName()) && node.isVisibleToUser()
                && node.isEnabled() && node.isClickable()
                && (matchesPlayLabel(play, node.getContentDescription(), node.getText())
                    || (mx && isUnlabeledMxPlayButton(node.getViewIdResourceName(),
                            node.getContentDescription(), node.getText())));
    }

    static boolean isUnlabeledMxPlayButton(String viewId, CharSequence description, CharSequence text) {
        return "com.mxtech.videoplayer.ad:id/playpause".equals(viewId)
                && (description == null || description.length() == 0) && (text == null || text.length() == 0);
    }

    private static void findPlayButtons(AccessibilityNodeInfo node, String packageName, Pattern play,
            boolean mx, List<AccessibilityNodeInfo> buttons) {
        if (!TextUtils.equals(packageName, node.getPackageName()) || !node.isVisibleToUser()) return;
        if (isPlayButton(node, packageName, play, mx)) buttons.add(AccessibilityNodeInfo.obtain(node));
        for (int i = 0, children = node.getChildCount(); i < children && buttons.size() < 2; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                findPlayButtons(child, packageName, play, mx, buttons);
            } finally {
                child.recycle();
            }
        }
    }
}
