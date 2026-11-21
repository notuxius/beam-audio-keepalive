package local.xreal.beamaudiokeepalive;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import android.view.KeyEvent;

import java.util.Collections;
import java.util.List;

public final class PlayerAccess extends NotificationListenerService {
    static ComponentName component(Context context) {
        return new ComponentName(context, PlayerAccess.class);
    }

    static boolean granted(Context context) {
        return context.getSystemService(NotificationManager.class).isNotificationListenerAccessGranted(component(context));
    }

    static boolean hasSession(Context context, String packageName) {
        for (MediaController controller : sessions(context)) {
            if (packageName.equals(controller.getPackageName())) return true;
        }
        return false;
    }

    static boolean otherPlayerActive(Context context, MediaSession.Token target) {
        for (MediaController controller : sessions(context)) {
            if (controller.getSessionToken().equals(target)) continue;
            PlaybackState state = controller.getPlaybackState();
            if (state != null && playing(state.getState())) return true;
        }
        return false;
    }

    static boolean isOnlySession(Context context, MediaSession.Token token) {
        List<MediaController> active = sessions(context);
        return active.size() == 1 && token.equals(active.get(0).getSessionToken());
    }

    static MediaController session(Context context, String packageName, MediaSession.Token preferred) {
        MediaController player = null;
        boolean ambiguous = false;
        for (MediaController controller : sessions(context)) {
            if (!packageName.equals(controller.getPackageName())) continue;
            if (controller.getSessionToken().equals(preferred)) return controller;
            PlaybackState state = controller.getPlaybackState();
            if (state == null || (!playing(state.getState()) && state.getState() != PlaybackState.STATE_PAUSED)) continue;
            ambiguous |= player != null;
            player = controller;
        }
        return ambiguous ? null : player;
    }

    static int state(MediaController player) {
        PlaybackState state = player == null ? null : player.getPlaybackState();
        return state == null ? -1 : state.getState();
    }

    static String mediaIdentity(MediaController player) {
        MediaMetadata metadata = player.getMetadata();
        if (metadata == null) return null;
        String uri = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI);
        if (uri != null && !uri.isEmpty()) return "uri:" + uri;
        String id = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        if (id != null && !id.isEmpty()) return "id:" + id;
        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        return title != null && !title.isEmpty() && duration > 0 ? "title:" + title + ":" + duration : null;
    }

    static boolean sameMedia(boolean sameSession, String before, String after) {
        if (before == null || after == null) return sameSession && before == null && after == null;
        return before.equals(after);
    }

    static void sendKey(MediaController player, int key) {
        player.dispatchMediaButtonEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
        player.dispatchMediaButtonEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
    }

    private static List<MediaController> sessions(Context context) {
        try {
            return context.getSystemService(MediaSessionManager.class).getActiveSessions(component(context));
        } catch (SecurityException accessUnavailable) {
            Log.d("BeamPlayerAccess", "Media session access unavailable");
            return Collections.emptyList();
        }
    }

    static boolean playing(int state) {
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
                || state == PlaybackState.STATE_FAST_FORWARDING || state == PlaybackState.STATE_REWINDING;
    }
}
