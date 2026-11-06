package com.noteblockaddons.client;

/** Current track, replaced as a whole whenever the media source reports a change. */
public record NowPlaying(String title, String artist, String album, String artKey, String source,
                         long progressMs, long durationMs, boolean playing, long sampledAtMs) {

    public static final NowPlaying IDLE = message("Nothing playing", "");
    public static final NowPlaying UNSUPPORTED = message("Not supported on this OS yet", "Windows only for now");
    public static final NowPlaying UNAVAILABLE = message("Can't read media info", "See the game log");

    private static NowPlaying message(String title, String artist) {
        return new NowPlaying(title, artist, "", "", "", 0L, 0L, false, 0L);
    }

    /** True for real media, false for the placeholder states above. */
    public boolean hasMedia() {
        return !artKey.isEmpty();
    }

    /** Extrapolated from the last update so the bar keeps moving in between. */
    public long progressNow() {
        long progress = progressMs;
        if (playing && sampledAtMs > 0L) progress += System.currentTimeMillis() - sampledAtMs;
        return Math.clamp(progress, 0L, Math.max(0L, durationMs));
    }

    public float progressRatio() {
        return durationMs <= 0L ? 0f : (float) progressNow() / durationMs;
    }
}
