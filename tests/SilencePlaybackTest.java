package local.xreal.beamaudiokeepalive;

public final class SilencePlaybackTest {
    public static void main(String[] args) {
        assert SilencePlayback.linkPollDelay(4499, 4500, -1, false) == 500;
        assert SilencePlayback.linkPollDelay(4500, 4500, -1, false) == 2000;
        assert SilencePlayback.linkPollDelay(4999, 0, 500, false) == 500;
        assert SilencePlayback.linkPollDelay(5000, 0, 500, false) == 2000;
        assert SilencePlayback.linkPollDelay(9000, 4500, 500, true) == 500;
        assert SilencePlayback.linkPollDelay(9000, 4500, 500, false) == 2000;
    }
}
