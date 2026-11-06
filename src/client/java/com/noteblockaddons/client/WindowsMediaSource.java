package com.noteblockaddons.client;

import com.noteblockaddons.NoteblockAddons;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Reads the current media session from Windows (the same info the volume-key media popup shows)
 * by calling the Windows Runtime API directly from Java. Works with any player that integrates
 * with Windows: Spotify, browsers, etc. No account, no setup, no extra process.
 *
 * Why not the Spotify API like v1.0? Since February 2026 a Spotify developer app allows five
 * users, total, and they all need Premium. Also it polled every 4 seconds, so pausing felt like
 * sending a letter. Windows already knows what's playing, instantly, for every app. So we ask it.
 *
 * Why not the PowerShell script like v1.1? It worked, but "mod quietly starts PowerShell" is
 * exactly what malware does, and it cost ~60 MB of RAM for a second runtime. Same API, same
 * calls, now straight from the game's own process.
 */
public final class WindowsMediaSource {
    private static final String MANAGER_CLASS = "Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager";
    private static final MemorySegment IID_MANAGER_STATICS = WinRt.guid("2050c4ee-11a0-57de-aed7-c97c70338245");

    // Function-table slots, from the windows-rs bindings (first own method of an interface = 6).
    private static final int STATICS_REQUEST_ASYNC = 6;
    private static final int MANAGER_GET_CURRENT_SESSION = 6;
    private static final int SESSION_APP_ID = 6;
    private static final int SESSION_TRY_GET_MEDIA_PROPERTIES = 7;
    private static final int SESSION_GET_TIMELINE = 8;
    private static final int SESSION_GET_PLAYBACK_INFO = 9;
    private static final int PROPERTIES_TITLE = 6;
    private static final int PROPERTIES_ARTIST = 9;
    private static final int PROPERTIES_ALBUM = 10;
    private static final int PROPERTIES_THUMBNAIL = 15;
    private static final int TIMELINE_START = 6;
    private static final int TIMELINE_END = 7;
    private static final int TIMELINE_POSITION = 10;
    private static final int TIMELINE_LAST_UPDATED = 11;
    private static final int PLAYBACK_STATUS = 7;
    private static final int STREAM_REFERENCE_OPEN_READ = 6;
    private static final int STATUS_PLAYING = 4;

    private static final long POLL_MS = 250L;
    private static final long PROPERTIES_TIMEOUT_MS = 2_000L;
    private static final long PICTURE_TIMEOUT_MS = 1_500L; // short: a slow cover must not hold up play/pause updates
    private static final int MAX_ART_TRIES = 20;            // some apps publish the picture a moment after the title
    private static final long RESTART_DELAY_MS = 3_000L;
    private static final int MAX_QUICK_FAILURES = 5;
    private static final int MAX_ERRORS_IN_A_ROW = 40; // ~10 seconds of failed reads
    private static final long TICKS_PER_MS = 10_000L;
    private static final long UNIX_EPOCH_TICKS = 116_444_736_000_000_000L; // 1601-01-01 to 1970-01-01

    private final Consumer<NowPlaying> onTrack;
    private final BiConsumer<String, byte[]> onArt;

    private Snapshot last;
    private String artKey = "";
    private int artTries;
    private String artError = "";

    public WindowsMediaSource(Consumer<NowPlaying> onTrack, BiConsumer<String, byte[]> onArt) {
        this.onTrack = onTrack;
        this.onArt = onArt;
    }

    public static boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    public void start() {
        Thread thread = new Thread(this::run, "NoteblockAddons media session");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        int quickFailures = 0;
        while (quickFailures < MAX_QUICK_FAILURES) {
            long started = System.currentTimeMillis();
            try {
                poll();
            } catch (Throwable t) {
                NoteblockAddons.LOGGER.warn("Media session reader stopped: {}", t.toString());
            }
            last = null;
            onTrack.accept(NowPlaying.IDLE);
            quickFailures = System.currentTimeMillis() - started < 30_000L ? quickFailures + 1 : 0;
            sleep(RESTART_DELAY_MS);
        }
        NoteblockAddons.LOGGER.error("Media session reader keeps failing; giving up. See the warnings above.");
        onTrack.accept(NowPlaying.UNAVAILABLE);
    }

    private void poll() {
        WinRt.initThread();
        MemorySegment manager;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment statics = WinRt.activationFactory(arena, MANAGER_CLASS, IID_MANAGER_STATICS);
            try {
                manager = WinRt.await(arena, statics, STATICS_REQUEST_ASYNC, "RequestAsync", 5_000L);
            } finally {
                WinRt.release(statics);
            }
        }
        if (manager.equals(MemorySegment.NULL)) throw new IllegalStateException("Windows media session manager is not available");
        try {
            int errorsInARow = 0;
            while (!Thread.currentThread().isInterrupted()) {
                try (Arena arena = Arena.ofConfined()) {
                    tick(arena, manager);
                    errorsInARow = 0;
                } catch (WinRt.WinRtException e) {
                    // A player closing mid-read answers with "disconnected" errors for a moment.
                    // Count it as nothing playing; only a long streak means something is really wrong.
                    if (++errorsInARow >= MAX_ERRORS_IN_A_ROW) throw e;
                    if (last != null) {
                        last = null;
                        onTrack.accept(NowPlaying.IDLE);
                    }
                }
                sleep(POLL_MS);
            }
        } finally {
            WinRt.release(manager);
        }
    }

    private void tick(Arena arena, MemorySegment manager) {
        MemorySegment session = WinRt.object(arena, manager, MANAGER_GET_CURRENT_SESSION, "GetCurrentSession");
        MemorySegment properties = MemorySegment.NULL;
        try {
            Snapshot now = null;
            if (!session.equals(MemorySegment.NULL)) {
                properties = WinRt.await(arena, session, SESSION_TRY_GET_MEDIA_PROPERTIES, "TryGetMediaPropertiesAsync", PROPERTIES_TIMEOUT_MS);
                if (!properties.equals(MemorySegment.NULL)) now = read(arena, session, properties);
            }
            if (!Objects.equals(now, last)) {
                last = now;
                onTrack.accept(now == null ? NowPlaying.IDLE : now.toNowPlaying());
            }
            if (now != null) fetchArtwork(arena, now, properties);
        } finally {
            WinRt.release(properties);
            WinRt.release(session);
        }
    }

    private static Snapshot read(Arena arena, MemorySegment session, MemorySegment properties) {
        String title = WinRt.string(arena, properties, PROPERTIES_TITLE, "Title");
        if (title.isEmpty()) return null;
        String app = WinRt.string(arena, session, SESSION_APP_ID, "SourceAppUserModelId");
        String artist = WinRt.string(arena, properties, PROPERTIES_ARTIST, "Artist");
        String album = WinRt.string(arena, properties, PROPERTIES_ALBUM, "AlbumTitle");

        MemorySegment timeline = WinRt.object(arena, session, SESSION_GET_TIMELINE, "GetTimelineProperties");
        long start, end, position, updated;
        try {
            start = WinRt.int64(arena, timeline, TIMELINE_START, "StartTime");
            end = WinRt.int64(arena, timeline, TIMELINE_END, "EndTime");
            position = WinRt.int64(arena, timeline, TIMELINE_POSITION, "Position");
            updated = WinRt.int64(arena, timeline, TIMELINE_LAST_UPDATED, "LastUpdatedTime");
        } finally {
            WinRt.release(timeline);
        }

        MemorySegment playback = WinRt.object(arena, session, SESSION_GET_PLAYBACK_INFO, "GetPlaybackInfo");
        boolean playing;
        try {
            playing = WinRt.int32(arena, playback, PLAYBACK_STATUS, "PlaybackStatus") == STATUS_PLAYING;
        } finally {
            WinRt.release(playback);
        }

        long updatedMs = updated > 0L ? (updated - UNIX_EPOCH_TICKS) / TICKS_PER_MS : 0L;
        return new Snapshot(app, title, artist, album, playing, position / TICKS_PER_MS, (end - start) / TICKS_PER_MS, updatedMs);
    }

    /** Cover art once per track, retried for a few seconds while the app hasn't published it yet. */
    private void fetchArtwork(Arena arena, Snapshot now, MemorySegment properties) {
        String key = now.artKey();
        if (!key.equals(artKey)) {
            artKey = key;
            artTries = 0;
            artError = "";
        }
        if (artTries < 0 || artTries >= MAX_ART_TRIES) return;
        artTries++;

        byte[] data = null;
        try {
            data = readThumbnail(arena, properties);
            if (data == null) artError = "the app sends no artwork";
        } catch (RuntimeException e) {
            artError = e.getMessage();
        }
        if (data != null || artTries >= MAX_ART_TRIES) {
            if (data == null) NoteblockAddons.LOGGER.warn("No cover for '{}' from {}: {}", now.title(), now.app(), artError);
            onArt.accept(key, data);
            artTries = -1;
        }
    }

    private static byte[] readThumbnail(Arena arena, MemorySegment properties) {
        MemorySegment reference = WinRt.object(arena, properties, PROPERTIES_THUMBNAIL, "Thumbnail");
        if (reference.equals(MemorySegment.NULL)) return null;
        try {
            MemorySegment stream = WinRt.await(arena, reference, STREAM_REFERENCE_OPEN_READ, "OpenReadAsync", PICTURE_TIMEOUT_MS);
            if (stream.equals(MemorySegment.NULL)) throw new IllegalStateException("timed out opening the picture");
            try {
                byte[] bytes = WinRt.readAll(arena, stream);
                return bytes.length > 0 ? bytes : null;
            } finally {
                WinRt.release(stream);
            }
        } finally {
            WinRt.release(reference);
        }
    }

    /** One reading of the session, compared against the last one so the HUD only hears about changes. */
    private record Snapshot(String app, String title, String artist, String album, boolean playing,
                            long positionMs, long durationMs, long updatedMs) {
        String artKey() {
            return app + "|" + title + "|" + artist + "|" + album;
        }

        NowPlaying toNowPlaying() {
            long now = System.currentTimeMillis();
            long position = positionMs;
            // Windows reports the position as of `updated`; move it forward to now while playing.
            if (playing && updatedMs > 0L && now >= updatedMs && now - updatedMs < Math.max(durationMs, 1L)) {
                position += now - updatedMs;
            }
            return new NowPlaying(title, artist, album, artKey(), appName(app), position, Math.max(0L, durationMs), playing, now);
        }
    }

    /** "Spotify.exe" -> "Spotify", "Microsoft.ZuneMusic_8wekyb3d8bbwe!Microsoft.ZuneMusic" -> "Microsoft.ZuneMusic". */
    static String appName(String appId) {
        String name = appId;
        int bang = name.indexOf('!');
        if (bang >= 0) name = name.substring(bang + 1);
        if (name.toLowerCase(Locale.ROOT).endsWith(".exe")) name = name.substring(0, name.length() - 4);
        return name;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
