package com.noteblockaddons.client;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Time-synced lyrics parsed from LRC ("[mm:ss.xx] text"). */
public final class Lyrics {
    public static final Lyrics EMPTY = new Lyrics(List.of());

    private static final Pattern TIMESTAMP = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d{1,3}))?]");

    public record Line(long timeMs, String text) {}

    private final List<Line> lines;

    private Lyrics(List<Line> lines) {
        this.lines = lines;
    }

    public static Lyrics parse(String lrc) {
        if (lrc == null || lrc.isBlank()) return EMPTY;
        List<Line> lines = new ArrayList<>();
        for (String raw : lrc.split("\\R")) {
            Matcher m = TIMESTAMP.matcher(raw);
            int textStart = 0;
            List<Long> times = new ArrayList<>();
            // a line can carry several timestamps: "[00:12.00][01:30.50] chorus"
            while (m.find() && m.start() == textStart) {
                times.add(toMillis(m.group(1), m.group(2), m.group(3)));
                textStart = m.end();
            }
            String text = raw.substring(textStart).trim();
            for (long time : times) lines.add(new Line(time, text));
        }
        lines.sort((a, b) -> Long.compare(a.timeMs(), b.timeMs()));
        return lines.isEmpty() ? EMPTY : new Lyrics(List.copyOf(lines));
    }

    private static long toMillis(String minutes, String seconds, String fraction) {
        long ms = Long.parseLong(minutes) * 60_000L + Long.parseLong(seconds) * 1000L;
        if (fraction != null) {
            // ".5" = 500ms, ".50" = 500ms, ".500" = 500ms
            ms += Long.parseLong((fraction + "00").substring(0, 3));
        }
        return ms;
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /** Index of the line being sung at {@code timeMs}, or -1 before the first line. */
    public int indexAt(long timeMs) {
        int lo = 0, hi = lines.size() - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).timeMs() <= timeMs) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    /** Line at {@code index}, or null when out of range. */
    public Line line(int index) {
        return index >= 0 && index < lines.size() ? lines.get(index) : null;
    }
}
