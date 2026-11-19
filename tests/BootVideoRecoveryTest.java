package local.xreal.beamaudiokeepalive;

import java.io.BufferedReader;
import java.io.StringReader;

public final class BootVideoRecoveryTest {
    public static void main(String[] args) throws Exception {
        String driver = "E/rockchip-dp fe0c0000.edp(    0): ";
        String failure = driver + "Timeout of slave video streamclk ok\n";
        String success = driver + "start video!!!\n";
        assert !needsRetry("");
        assert !needsRetry(success);
        assert needsRetry(failure);
        assert needsRetry(success + failure);
        assert !needsRetry(failure + success);
        assert !needsRetry(failure.replace("fe0c0000.edp", "fe000000.edp"));
    }

    private static boolean needsRetry(String log) throws Exception {
        return BootVideoRecovery.videoClockTimedOut(new BufferedReader(new StringReader(log)));
    }
}
