package local.xreal.beamaudiokeepalive;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

import java.util.function.BooleanSupplier;

final class SwitchPlayer {
    private static final String TAG = "BeamSwitchPlayer";
    private static final long COMMAND_WAIT_MS = 350;
    private static final long RESUME_CHECK_MS = 600;
    private static final long ACCESSIBILITY_RESUME_CHECK_MS = 2000;
    private final Context context;
    private final AudioManager audio;
    private final ForegroundTasks tasks;
    private final ForegroundTasks.Task target;
    private final boolean vlc;
    private final String mxMedia;
    private final MediaSession.Token originalSession;
    private final String originalMedia;
    private final boolean wasPlaying;
    private final AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .build();
    private MediaSession.Token pauseSession;
    private long nextCommandAt;
    private boolean pauseKeySent;
    private boolean focusTried;
    private boolean holdingFocus;

    SwitchPlayer(Context context, AudioManager audio, ForegroundTasks tasks, ForegroundTasks.Task target) {
        this.context = context;
        this.audio = audio;
        this.tasks = tasks;
        this.target = target;
        vlc = ForegroundTasks.VLC.equals(target.component);
        MediaController controller = PlayerAccess.granted(context)
                ? PlayerAccess.session(context, target.packageName, null) : null;
        originalSession = controller == null ? null : controller.getSessionToken();
        originalMedia = controller == null ? null : PlayerAccess.mediaIdentity(controller);
        mxMedia = originalSession == null && ForegroundTasks.MX_PLAYER.equals(target.component)
                ? tasks.mxMedia(target) : null;
        wasPlaying = PlayerAccess.playing(mxMedia == null ? PlayerAccess.state(controller)
                : tasks.mxPlaybackState(target, mxMedia));
        nextCommandAt = SystemClock.elapsedRealtime() + (controller == null ? COMMAND_WAIT_MS : 0);
    }

    private MediaController controller() {
        if (!PlayerAccess.granted(context)) return null;
        MediaController player = PlayerAccess.session(context, target.packageName, originalSession);
        if (player == null || originalSession == null) return player;
        return PlayerAccess.sameMedia(originalSession.equals(player.getSessionToken()),
                originalMedia, PlayerAccess.mediaIdentity(player)) ? player : null;
    }

    boolean recognized() {
        return originalSession != null || vlc || mxMedia != null
                || (PlayerAccess.granted(context) && PlayerAccess.hasSession(context, target.packageName));
    }

    boolean canPauseWithFocus() {
        return originalSession == null && tasks.current() == target && audio.isMusicActive()
                && !PlayerAccess.otherPlayerActive(context, null);
    }

    boolean paused() {
        int state = state(controller());
        return state == PlaybackState.STATE_PAUSED || (state < 0 && holdingFocus && !audio.isMusicActive());
    }

    boolean active() {
        int state = state(controller());
        return PlayerAccess.playing(state) || (state < 0 && SystemClock.elapsedRealtime() >= nextCommandAt
                && canPauseWithFocus());
    }

    private int state(MediaController player) {
        int state = PlayerAccess.state(player);
        if (state >= 0 || originalSession != null) return state;
        return vlc ? tasks.vlcPlaybackState() : tasks.mxPlaybackState(target, mxMedia);
    }

    void pause(boolean preferFocus) {
        if (!tasks.canReturnTo(target)
                || (tasks.current() != target && tasks.evaForegroundAt() < 0)) return;
        MediaController player = controller();
        int state = state(player);
        if (state == PlaybackState.STATE_PAUSED) return;
        MediaSession.Token token = player == null ? null : player.getSessionToken();
        long now = SystemClock.elapsedRealtime();
        if (now < nextCommandAt) return;
        boolean focusEligible = !focusTried && (token != null ? PlayerAccess.playing(state) : canPauseWithFocus())
                && !PlayerAccess.otherPlayerActive(context, token);
        if (preferFocus && focusEligible && holdFocus(now)) return;
        if (PlayerAccess.playing(state) && token != null && !token.equals(pauseSession)) {
            player.getTransportControls().pause();
            pauseSession = token;
            pauseKeySent = false;
            nextCommandAt = now + COMMAND_WAIT_MS;
            Log.i(TAG, "Requested Pause in " + target.packageName);
        } else if (PlayerAccess.playing(state) && token != null && !pauseKeySent) {
            PlayerAccess.sendKey(player, KeyEvent.KEYCODE_MEDIA_PAUSE);
            pauseKeySent = true;
            nextCommandAt = now + COMMAND_WAIT_MS;
            Log.i(TAG, "Sent media Pause key to " + target.packageName);
        } else if (focusEligible && !focusTried) {
            holdFocus(now);
        }
    }

    private boolean holdFocus(long now) {
        focusTried = true;
        holdingFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (holdingFocus) {
            nextCommandAt = now + COMMAND_WAIT_MS;
            Log.i(TAG, "Holding transient audio focus for " + target.packageName);
        }
        return holdingFocus;
    }

    void resume(Handler main, BooleanSupplier sameSwitch) {
        boolean releasedFocus = holdingFocus;
        releaseFocus();
        if (!wasPlaying || tasks.current() != target) return;
        if (mxMedia != null) {
            main.postDelayed(() -> {
                BooleanSupplier stillPaused = () -> sameSwitch.getAsBoolean() && tasks.current() == target
                        && tasks.mxPlaybackState(target, mxMedia) == PlaybackState.STATE_PAUSED;
                if (stillPaused.getAsBoolean()) accessibilityPlay(main, sameSwitch, stillPaused, true);
            }, RESUME_CHECK_MS);
            return;
        }
        MediaController player = controller();
        PlaybackState state = player == null ? null : player.getPlaybackState();
        if (state == null || state.getState() != PlaybackState.STATE_PAUSED) return;
        long pausedAt = state.getLastPositionUpdateTime();
        Runnable play = () -> {
            if (!stillPaused(sameSwitch, player, pausedAt)) return;
            player.getTransportControls().play();
            Log.i(TAG, "Requested Play after the fade in " + target.packageName);
            main.postDelayed(() -> {
                if (!stillPaused(sameSwitch, player, pausedAt)) return;
                PlayerAccess.sendKey(player, KeyEvent.KEYCODE_MEDIA_PLAY);
                Log.i(TAG, "Sent media Play key to " + target.packageName);
                main.postDelayed(() -> {
                    BooleanSupplier stillPaused = () -> stillPaused(sameSwitch, player, pausedAt);
                    if (!stillPaused.getAsBoolean() || accessibilityPlay(main, sameSwitch, stillPaused, false)
                            || !stillPaused.getAsBoolean()) return;
                    if (!PlayerAccess.isOnlySession(context, player.getSessionToken())) return;
                    audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY));
                    audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY));
                    Log.i(TAG, "Sent system Play key for the only active session in " + target.packageName);
                }, RESUME_CHECK_MS);
            }, RESUME_CHECK_MS);
        };
        if (releasedFocus) main.postDelayed(play, RESUME_CHECK_MS);
        else play.run();
    }

    private boolean accessibilityPlay(Handler main, BooleanSupplier sameSwitch,
            BooleanSupplier stillPaused, boolean mx) {
        if (!PlayerControls.play(target.packageName, stillPaused, mx)) return false;
        Log.i(TAG, "Pressed the visible Play button in " + target.packageName);
        main.postDelayed(() -> {
            if (!sameSwitch.getAsBoolean() || tasks.current() != target) return;
            int playback = mx ? tasks.mxPlaybackState(target, mxMedia) : PlayerAccess.state(controller());
            if (PlayerAccess.playing(playback)) {
                Log.i(TAG, "Confirmed playback resumed in " + target.packageName);
                return;
            }
            String message = playback < 0
                    ? "Accessibility fallback couldn't confirm playback resumed"
                    : "Accessibility fallback pressed Play but playback didn't resume";
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
        }, ACCESSIBILITY_RESUME_CHECK_MS);
        return true;
    }

    private boolean stillPaused(BooleanSupplier sameSwitch, MediaController player, long pausedAt) {
        if (!sameSwitch.getAsBoolean() || tasks.current() != target) return false;
        MediaController current = controller();
        if (current == null || !player.getSessionToken().equals(current.getSessionToken())) return false;
        PlaybackState state = current.getPlaybackState();
        return state != null && state.getState() == PlaybackState.STATE_PAUSED
                && state.getLastPositionUpdateTime() == pausedAt;
    }

    void releaseFocus() {
        if (!holdingFocus) return;
        holdingFocus = false;
        audio.abandonAudioFocusRequest(focus);
        Log.i(TAG, "Released switch audio focus");
    }
}
