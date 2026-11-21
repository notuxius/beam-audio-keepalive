package local.xreal.beamaudiokeepalive;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.util.Collections;
import java.util.List;

public final class PlayerAccess extends NotificationListenerService {
    static ComponentName component(Context context) {
        return new ComponentName(context, PlayerAccess.class);
    }

    static boolean granted(Context context) {
        return context.getSystemService(NotificationManager.class).isNotificationListenerAccessGranted(component(context));
    }

    static MediaController playing(Context context, String packageName) {
        return session(context, packageName, true);
    }

    static boolean hasSession(Context context, String packageName) {
        for (MediaController controller : sessions(context)) {
            if (packageName.equals(controller.getPackageName())) return true;
        }
        return false;
    }

    static boolean isOnlySession(Context context, MediaSession.Token token) {
        List<MediaController> active = sessions(context);
        return active.size() == 1 && token.equals(active.get(0).getSessionToken());
    }

    static MediaController session(Context context, String packageName, boolean playingOnly) {
        MediaController player = null;
        for (MediaController controller : sessions(context)) {
            if (!packageName.equals(controller.getPackageName())) continue;
            PlaybackState state = controller.getPlaybackState();
            if (state == null || (!playing(state.getState())
                    && (playingOnly || state.getState() != PlaybackState.STATE_PAUSED))) continue;
            if (player != null) return null;
            player = controller;
        }
        return player;
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
