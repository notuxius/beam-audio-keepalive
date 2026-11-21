package local.xreal.beamaudiokeepalive;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public final class PrepareSwitchTile extends TileService {
    static void refresh(Context context) {
        TileService.requestListeningState(context, new ComponentName(context, PrepareSwitchTile.class));
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile == null) return;
        PreparedSwitch preparation = PlaybackProtection.preparation;
        boolean armed = preparation != null && preparation.armed();
        boolean returning = armed && preparation.awaitingTap();
        tile.setState(!PlaybackProtection.enabled(this) ? Tile.STATE_UNAVAILABLE
                : armed ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(returning ? "Return to app" : armed ? "Cancel switch" : "Prepare switch");
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            String paused = armed && preparation.automaticSwitch() ? "Waiting for switch" : "Ready. Switch now.";
            tile.setSubtitle(armed && !returning ? (preparation.paused() ? paused : "Preparing…") : null);
        }
        tile.updateTile();
    }

    @Override
    public void onClick() {
        if (!PlaybackProtection.enabled(this)) return;
        PreparedSwitch preparation = PlaybackProtection.preparation;
        if (preparation != null && preparation.awaitingTap()) {
            startActivityAndCollapse(new Intent(this, PlaybackProtection.ReturnToApp.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else {
            startForegroundService(new Intent(this, PlaybackProtection.class).setAction(
                    PlaybackProtection.switchArmed() ? PlaybackProtection.CANCEL : PlaybackProtection.PREPARE));
        }
    }
}
