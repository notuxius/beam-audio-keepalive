package local.xreal.beamaudiokeepalive;

final class SwitchReadiness {
    static final long SETTLE_MS = 4500;
    // shortcut: allow USB video to settle after Pause until the Beam exposes a frame-ready signal.
    private static final long USB_PLAYER_SETTLE_MS = 2500;

    private final int initial;
    private int observed;
    private long since;
    private long pausedSince = -1;

    SwitchReadiness(int initial) {
        this.initial = initial;
    }

    boolean ready(int routeAndWidth, long now) {
        if (observed != routeAndWidth) {
            observed = routeAndWidth;
            since = now;
            pausedSince = -1;
        }
        return observed != 0 && observed != initial && now - since >= SETTLE_MS;
    }

    long playerSettleMillis(int output) {
        return (initial & 1) == 0 && (output & 1) == 1 ? USB_PLAYER_SETTLE_MS : 0;
    }

    boolean playerReady(int output, boolean pausedInForeground, long now) {
        if (!pausedInForeground) {
            pausedSince = -1;
            return false;
        }
        if (pausedSince < 0) pausedSince = now;
        return now - pausedSince >= playerSettleMillis(output);
    }
}
