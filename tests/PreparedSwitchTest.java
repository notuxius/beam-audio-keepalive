package local.xreal.beamaudiokeepalive;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class PreparedSwitchTest {
    public static void main(String[] args) {
        byte[] coverAck = GlassesCover.coverPacket();
        assert coverAck.length == 64 && coverAck[0] == (byte) 0xFD && coverAck[5] == 20;
        assert coverAck[15] == 0x2B && coverAck[22] == (byte) 0xA7 && coverAck[23] == (byte) 0xAD
                && coverAck[24] == 4;
        assert ByteBuffer.wrap(coverAck).order(ByteOrder.LITTLE_ENDIAN).getInt(1) == 0xD6B06E8E;
        byte[] reply = new byte[64];
        reply[0] = (byte) 0xFD;
        reply[15] = 0x18;
        assert GlassesCover.acknowledgementStatus(reply, 64) == -1;
        reply[15] = 0x2B;
        System.arraycopy(new byte[] {'B', 'H', 'A', '1', 1}, 0, reply, 23, 5);
        assert GlassesCover.acknowledgementStatus(reply, 64) == 1;
        reply[27] = 0;
        assert GlassesCover.acknowledgementStatus(reply, 64) == 0;
        reply[25] = 'D';
        reply[27] = 1;
        ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN).putInt(28, 0xFE123456);
        assert GlassesCover.pendingDpToken(reply, 32) == 0xFE123456L;
        assert GlassesCover.pendingDpToken(reply, 31) == -1;
        byte[] dpAck = GlassesCover.coverPacket(6, 0xFE123456);
        assert dpAck[5] == 24 && dpAck[24] == 6;
        assert ByteBuffer.wrap(dpAck).order(ByteOrder.LITTLE_ENDIAN).getInt(25) == 0xFE123456;

        verifySwitch(1920, 3840);
        verifySwitch(3840, 1920);
        verifySwitch(1920, 1921);
        verifySwitch(1921, 1920);

        SwitchReadiness unstable = new SwitchReadiness(1920);
        assert !unstable.ready(1921, 100);
        assert !unstable.ready(0, 4000);
        assert !unstable.ready(1921, 4600);
        assert !unstable.ready(1921, 9099);
        assert unstable.ready(1921, 9100);
        assert !unstable.playerReady(1921, true, 9100);
        assert !unstable.playerReady(1921, false, 11599);
        assert !unstable.playerReady(1921, true, 11600);
        assert !unstable.playerReady(1921, true, 14099);
        assert unstable.playerReady(1921, true, 14100);
        assert !unstable.ready(0, 14101);
        assert !unstable.playerReady(1921, true, 14102);
        assert new SwitchReadiness(1921).playerReady(1920, true, 100);
        assert new SwitchReadiness(1921).playerReady(3841, true, 100);
        assert !new SwitchReadiness(0).playerReady(1921, true, 100);

        verifyTask(ForegroundTasks.VLC);
        verifyTask("org.example.player/.Playback");
        ForegroundTasks tasks = new ForegroundTasks();
        tasks.accept("1000.000 442 568 I wm_set_resumed_activity: [0," + ForegroundTasks.EVA + ",start]");
        tasks.accept("1000.200 442 568 I ActivityTaskManager: Displayed " + ForegroundTasks.EVA + ": +200ms");
        assert !tasks.evaDrawnSince(999999);
        tasks.accept("1007.000 442 568 I ActivityTaskManager: Fully drawn " + ForegroundTasks.EVA + ": +7s");
        assert tasks.evaDrawnSince(1000001);
        assert !tasks.evaDrawnSince(1007001);
        tasks.accept("1008.000 442 568 I wm_set_resumed_activity: [0,org.example.player/.Activity,start]");
        assert !tasks.evaDrawnSince(1000001);
        assert tasks.accept("1000.000 0 0 I usb 1-1 : new full-speed USB device number 15 using xhci-hcd")
                == ForegroundTasks.USB_ENUMERATING;
        assert tasks.accept("1000.001 0 0 I usb 1-2 : new full-speed USB device number 15 using xhci-hcd") == -1;
        assert tasks.accept("1000.002 0 0 I usb 1-1 : new high-speed USB device number 15 using xhci-hcd") == -1;
        assert tasks.accept("1000.042 0 0 I input   : XREAL XREAL Air 2 Pro as "
                + "/devices/platform/usbhost/1-1/1-1:1.6/0003:3318:0432.002C/input/input13")
                == ForegroundTasks.USB_ENUMERATING;
        assert tasks.accept("1000.043 0 0 I input   : XREAL XREAL Air 2 Pro as "
                + "/devices/platform/usbhost/1-1/0003:3318:9999.0001/input/input13") == -1;
        assert tasks.accept("1000.100 442 568 D UsbHostManager: USB device attached: vidpid 3318:0432") == 1;
        assert tasks.accept("1010.200 442 568 I UsbAlsaManager: USB Audio Device Removed: USB-Audio - XREAL Air 2 Pro") == 0;
        assert tasks.vlcPlaybackState() == -1;
        playback(tasks, 3);
        assert tasks.vlcPlaybackState() == 3;
        playback(tasks, 1);
        assert tasks.vlcPlaybackState() == 1;
        playback(tasks, 2);
        assert tasks.vlcPlaybackState() == 2;
        tasks.accept("I/am_proc_died(442): [0,8218,org.videolan.vlc,500,10]");
        assert tasks.vlcPlaybackState() == -1;
    }

    private static void verifyTask(String component) {
        ForegroundTasks tasks = new ForegroundTasks();
        foreground(tasks, component);
        tasks.accept("I/wm_resume_activity(442): [0,25000899,31," + component + "]");
        ForegroundTasks.Task saved = tasks.current();
        assert saved != null && saved.id == 31 && tasks.canReturnTo(saved);
        assert tasks.foregroundSince(saved) == 1012300;

        foreground(tasks, ForegroundTasks.EVA);
        assert tasks.foregroundSince(saved) == -1;
        assert tasks.evaForegroundAt() == 1012300 && tasks.current() == null && tasks.canReturnTo(saved);
        foreground(tasks, "org.example.other/.Activity");
        foreground(tasks, ForegroundTasks.EVA);
        assert !tasks.canReturnTo(saved);

        foreground(tasks, component);
        tasks.accept("1012.800 442 568 I wm_resume_activity: [0,25000899,31," + component + "]");
        assert tasks.current() == saved;
        assert tasks.foregroundSince(saved) == 1012800;
        tasks.accept("I/am_proc_died(442): [0,8218," + saved.packageName + ",500,10]");
        tasks.accept("I/wm_restart_activity(442): [0,25000899,31," + component + "]");
        assert !tasks.canReturnTo(saved);
        ForegroundTasks.Task replacement = tasks.current();
        assert replacement != null && tasks.canReturnTo(replacement);
        tasks.accept("I/wm_finish_activity(442): [0,25000899,31," + component + ",app-request]");
        assert !tasks.canReturnTo(replacement);
    }

    private static void foreground(ForegroundTasks tasks, String component) {
        tasks.accept("  1012.300 442 568 I wm_set_resumed_activity: [0," + component + ",start]");
    }

    private static void playback(ForegroundTasks task, int state) {
        task.accept("  1000.000 10748 10748 V AvrcpMediaPlayerWrapper: onPlaybackStateChanged(): "
                + "org.videolan.vlc : PlaybackState {state=" + state + ", position=1000}");
    }

    private static void verifySwitch(int initial, int destination) {
        SwitchReadiness readiness = new SwitchReadiness(initial);
        assert !readiness.ready(initial, 0);
        assert !readiness.ready(initial, 10000);
        assert !readiness.ready(destination, 10100);
        assert !readiness.ready(destination, 14599);
        assert readiness.ready(destination, 14600);
    }

}
