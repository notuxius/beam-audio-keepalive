package local.xreal.beamaudiokeepalive;

import android.media.session.PlaybackState;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ForegroundTasks {
    static final String VLC = "org.videolan.vlc/.gui.video.VideoPlayerActivity";
    static final String MX_PLAYER = "com.mxtech.videoplayer.ad/.ActivityScreen";
    static final String EVA = "ai.nreal.evalauncher/ai.nreal.cast.UnityCastActivity";
    static final int USB_ENUMERATING = 2;
    private static final String EVA_FULLY_DRAWN = "ActivityTaskManager: Fully drawn " + EVA + ":";
    private static final Pattern RESUMED = Pattern.compile("wm_set_resumed_activity.*\\[0,([^,]+),");
    private static final Pattern ACTIVITY = Pattern.compile(
            "wm_(restart|resume|finish)_activity.*\\[0,(\\d+),(\\d+),([^,\\]]+)");
    private static final Pattern DIED = Pattern.compile("am_proc_died.*\\[0,\\d+,([^,\\]]+)");
    private static final Pattern VLC_PLAYBACK = Pattern.compile(
            "AvrcpMediaPlayerWrapper: onPlaybackStateChanged\\(\\): org\\.videolan\\.vlc : PlaybackState \\{state=(\\d+),");
    // shortcut: MX Player 3.5.3 leaves its media session state empty. Recheck these tags if MX changes its logs.
    private static final Pattern MX_SCREEN = Pattern.compile(
            "^\\s*\\d+\\.\\d+\\s+(\\d+)\\s+\\d+ V MX\\.Screen: on(?:Start|Resume|Pause|Stop) "
            + "\\(com\\.mxtech\\.videoplayer\\.ad\\.ActivityScreen@[^)]+\\): (?:video-)?uri=(.+)$");
    private static final Pattern MX_PLAYBACK = Pattern.compile(
            "^\\s*\\d+\\.\\d+\\s+(\\d+)\\s+\\d+ V MX\\.Player\\.Joint: (Start|Pause) \\[\\d+\\]\\..* ex=null$");
    private static final String USB_START = "new full-speed USB device number [0-9]+ using xhci-hcd";
    // shortcut: Beam OS 384 routes the glasses to this host port, recheck on another OS build.
    private static final Pattern GLASSES_USB_START = Pattern.compile("usb 1-1\\s*: " + USB_START);
    private static final Pattern TIME = Pattern.compile("^\\s*(\\d+)\\.(\\d{3})\\s");
    private final Map<String, Task> activities = new HashMap<>();
    private String foreground = "";
    private String lastApp = "";
    private long foregroundAt;
    private long evaDrawnAt;
    private int vlcPlaybackState = -1;

    static Process openLog() throws IOException {
        return new ProcessBuilder("/system/bin/logcat", "-b", "events", "-b", "main", "-b", "system",
                "-b", "kernel", "-v", "epoch", "-s",
                "wm_set_resumed_activity", "wm_restart_activity", "wm_resume_activity", "wm_finish_activity",
                "am_proc_died", "ActivityTaskManager:I", "AvrcpMediaPlayerWrapper:V",
                "MX.Screen:V", "MX.Player.Joint:V", "UsbHostManager:D", "UsbAlsaManager:I", "input:I")
                .redirectErrorStream(true).start();
    }

    static Process openUsbLog() throws IOException {
        return new ProcessBuilder("/system/bin/logcat", "-b", "kernel", "-v", "epoch", "--regex", USB_START)
                .redirectErrorStream(true).start();
    }

    synchronized int accept(String line) {
        if (line.contains(EVA_FULLY_DRAWN)) evaDrawnAt = epochMillis(line);
        Matcher playback = VLC_PLAYBACK.matcher(line);
        if (playback.find()) vlcPlaybackState = Integer.parseInt(playback.group(1));
        Matcher died = DIED.matcher(line);
        if (died.find()) {
            String name = died.group(1);
            activities.values().removeIf(task -> task.packageName.equals(name));
            if (VLC.startsWith(name + "/")) vlcPlaybackState = -1;
        }
        Matcher resumed = RESUMED.matcher(line);
        if (resumed.find()) {
            foreground = resumed.group(1);
            foregroundAt = epochMillis(line);
            if (eligible(foreground)) lastApp = foreground;
        }
        Matcher activity = ACTIVITY.matcher(line);
        if (activity.find() && eligible(activity.group(4))) {
            String component = activity.group(4);
            int instance = Integer.parseInt(activity.group(2));
            int id = Integer.parseInt(activity.group(3));
            Task task = activities.get(component);
            boolean same = task != null && task.instance == instance && task.id == id;
            if ("finish".equals(activity.group(1))) {
                if (same) activities.remove(component);
            } else {
                if (!same) {
                    task = new Task(component, instance, id);
                    activities.put(component, task);
                }
                task.resumedAt = epochMillis(line);
            }
        }
        Task mx = activities.get(MX_PLAYER);
        if (mx != null && line.contains("MX.")) {
            Matcher screen = MX_SCREEN.matcher(line);
            Matcher mxPlayback = MX_PLAYBACK.matcher(line);
            if (screen.matches()) {
                if (!screen.group(1).equals(mx.mxPid) || !screen.group(2).equals(mx.mxMedia)) {
                    mx.mxPlaybackState = -1;
                }
                mx.mxPid = screen.group(1);
                mx.mxMedia = screen.group(2);
            } else if (mxPlayback.matches() && mxPlayback.group(1).equals(mx.mxPid)) {
                mx.mxPlaybackState = "Start".equals(mxPlayback.group(2))
                        ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
            }
        }
        if (GLASSES_USB_START.matcher(line).find()
                || (line.contains("I input   : XREAL XREAL Air 2 Pro as ") && line.contains("/0003:3318:0432."))) {
            return USB_ENUMERATING;
        }
        if (line.contains("UsbHostManager: USB device attached: vidpid 3318:0432")) return 1;
        if (line.contains("UsbAlsaManager: USB Audio Device Removed:")
                && line.contains("USB-Audio - XREAL Air 2 Pro")) return 0;
        return -1;
    }

    synchronized Task current() { return activities.get(foreground); }

    synchronized boolean canReturnTo(Task task) {
        return lastApp.equals(task.component) && activities.get(task.component) == task;
    }

    synchronized int vlcPlaybackState() { return vlcPlaybackState; }

    synchronized String mxMedia(Task task) { return task.mxMedia; }

    synchronized int mxPlaybackState(Task task, String media) {
        return media != null && media.equals(task.mxMedia) ? task.mxPlaybackState : -1;
    }

    synchronized long foregroundSince(Task task) {
        return current() == task ? Math.max(foregroundAt, task.resumedAt) : -1;
    }

    synchronized long evaForegroundAt() { return EVA.equals(foreground) ? foregroundAt : -1; }

    synchronized boolean evaDrawnSince(long start) {
        return EVA.equals(foreground) && evaDrawnAt >= start && evaDrawnAt >= foregroundAt;
    }

    private static boolean eligible(String component) {
        return component.indexOf('/') > 0 && !component.startsWith("ai.nreal.evalauncher/")
                && !component.startsWith("android/") && !component.startsWith("com.android.systemui/")
                && !component.startsWith("local.xreal.beamaudiokeepalive/");
    }

    static long epochMillis(String line) {
        Matcher time = TIME.matcher(line);
        return time.find() ? Long.parseLong(time.group(1)) * 1000 + Long.parseLong(time.group(2)) : 0;
    }

    static final class Task {
        final String component;
        final String packageName;
        final int instance;
        final int id;
        private long resumedAt;
        private String mxPid;
        private String mxMedia;
        private int mxPlaybackState = -1;

        Task(String component, int instance, int id) {
            this.component = component;
            this.packageName = component.substring(0, component.indexOf('/'));
            this.instance = instance;
            this.id = id;
        }
    }
}
