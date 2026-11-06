package com.noteblockaddons.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.noteblockaddons.NoteblockAddons;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executor;

/** Fetches synced lyrics from LRCLIB (free, no API key). One request per track change. */
public final class LyricsService {
    private static final String API = "https://lrclib.net/api";
    private static final String USER_AGENT = "NoteblockAddons (Minecraft mod)";
    private static final double MAX_DURATION_DIFF_S = 5.0;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Executor io;

    private volatile String trackKey = "";
    private volatile Lyrics current = Lyrics.EMPTY;
    private volatile boolean loading;
    private volatile String cachedKey = "";
    private volatile Lyrics cached = Lyrics.EMPTY;

    public LyricsService(Executor io) {
        this.io = io;
    }

    public Lyrics current() {
        return current;
    }

    public boolean isLoading() {
        return loading;
    }

    public void update(NowPlaying track) {
        if (!track.hasMedia() || track.title().isBlank()) {
            clear();
            return;
        }
        String key = track.title() + '\n' + track.artist();
        if (key.equals(trackKey)) return;
        trackKey = key;
        if (key.equals(cachedKey)) { // e.g. lyrics toggled off and on again
            current = cached;
            loading = false;
            return;
        }
        current = Lyrics.EMPTY;
        loading = true;
        io.execute(() -> {
            Lyrics lyrics = fetch(track);
            cached = lyrics; // before the key, so a reader never pairs a new key with old lyrics
            cachedKey = key;
            if (key.equals(trackKey)) { // ignore if the song changed meanwhile
                current = lyrics;
                loading = false;
            }
        });
    }

    public void clear() {
        trackKey = "";
        current = Lyrics.EMPTY;
        loading = false;
    }

    private Lyrics fetch(NowPlaying track) {
        double duration = track.durationMs() / 1000.0;
        if (!track.album().isBlank() && duration > 0) {
            try {
                JsonElement exact = get("/get?track_name=" + enc(track.title())
                        + "&artist_name=" + enc(track.artist())
                        + "&album_name=" + enc(track.album())
                        + "&duration=" + Math.round(duration));
                if (exact != null && exact.isJsonObject()) {
                    Lyrics lyrics = synced(exact.getAsJsonObject());
                    if (!lyrics.isEmpty()) return lyrics;
                }
            } catch (Exception e) {
                // LRCLIB answers 503, not 404, when the album doesn't match. Not documented anywhere,
                // found it by hammering the live API. Deluxe editions and singles hit this constantly,
                // so any failure here just means: go search instead.
            }
        }

        try {
            JsonElement results = get("/search?track_name=" + enc(track.title()) + "&artist_name=" + enc(track.artist()));
            if (results == null || !results.isJsonArray()) return Lyrics.EMPTY;
            return closestSynced(results.getAsJsonArray(), duration);
        } catch (Exception e) {
            NoteblockAddons.LOGGER.debug("Lyrics lookup failed for {}: {}", track.title(), e.getMessage());
            return Lyrics.EMPTY;
        }
    }

    private static Lyrics closestSynced(JsonArray results, double duration) {
        JsonObject best = null;
        double bestDiff = MAX_DURATION_DIFF_S;
        for (JsonElement element : results) {
            if (!element.isJsonObject()) continue;
            JsonObject result = element.getAsJsonObject();
            if (!hasText(result, "syncedLyrics")) continue;
            JsonElement resultDuration = result.get("duration");
            double diff = duration > 0 && resultDuration != null && resultDuration.isJsonPrimitive()
                    ? Math.abs(resultDuration.getAsDouble() - duration) : 0;
            if (diff <= bestDiff) {
                bestDiff = diff;
                best = result;
            }
        }
        return best == null ? Lyrics.EMPTY : synced(best);
    }

    private static Lyrics synced(JsonObject result) {
        return hasText(result, "syncedLyrics") ? Lyrics.parse(result.get("syncedLyrics").getAsString()) : Lyrics.EMPTY;
    }

    private static boolean hasText(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() && !object.get(key).getAsString().isBlank();
    }

    /** Returns parsed JSON, or null on 404. */
    private JsonElement get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + path))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return null;
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + response.statusCode());
        return JsonParser.parseString(response.body());
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
