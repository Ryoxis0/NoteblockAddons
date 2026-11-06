package com.noteblockaddons.client;

import foo.starred.cascade.graphics.font.rendering.impl.FontRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * Scrolling lyrics. Lines sit on a continuous track that eases up one line at a time:
 * the next line grows and brightens into the highlighted spot while the current one
 * drifts up, fading and blurring until it's gone.
 */
final class LyricsView {
    private static final float CURRENT_SIZE = 8.5f;
    private static final float NEXT_SIZE = 7f;
    private static final float LINE_HEIGHT = CURRENT_SIZE * 1.25f;
    private static final float LINE_GAP = 3f;
    private static final float NEXT_ALPHA = 0.42f;
    private static final float EXIT_DISTANCE = 0.75f; // fully gone after moving this many lines up
    private static final float MAX_BLUR = 3.5f;
    private static final float SNAP_DISTANCE = 2.5f;  // seeking further than this jumps instead of scrolling
    private static final int MAX_WRAP_LINES = 2;

    private static final int BLUR_SAMPLES = 6;
    private static final float[] BLUR_X = new float[BLUR_SAMPLES];
    private static final float[] BLUR_Y = new float[BLUR_SAMPLES];

    static {
        for (int i = 0; i < BLUR_SAMPLES; i++) {
            double angle = Math.PI * 2 * i / BLUR_SAMPLES;
            BLUR_X[i] = (float) Math.cos(angle);
            BLUR_Y[i] = (float) Math.sin(angle);
        }
    }

    // Lyric changes happen constantly, so the scroll stays quick and subtle.
    private final Tween scroll = new Tween(-1f, 320, Easing.EASE_IN_OUT);
    private final Tween fade = new Tween(0f, 220, Easing.EASE_OUT);

    private Lyrics shown = Lyrics.EMPTY;
    private int index = -1;
    private List<List<String>> wrapped = List.of();
    private float[] offsets = new float[1];

    /**
     * @param incoming lyrics for the current track (empty when off, missing, or still loading)
     * @param reveal   how far the lyrics area is open, 0..1
     */
    void draw(GuiGraphicsExtractor g, FontRenderer font, Lyrics incoming, boolean loading, long progressMs,
              float x, float top, float width, float reveal) {
        update(font, incoming, loading, progressMs, width, reveal);
        if (shown.isEmpty()) return;

        float alpha = fade.value() * reveal;
        if (alpha <= 0.01f) return;

        float s = scroll.value();
        float yBase = top - offsetAt(s) + (1f - reveal) * 6f; // slides up into place as the area opens
        int first = Math.max(0, (int) Math.floor(s) - 1);
        int last = Math.min(wrapped.size() - 1, (int) Math.floor(s) + 2);

        for (int i = first; i <= last; i++) {
            float d = i - s; // 0 = highlighted line, 1 = next, negative = leaving
            float lineAlpha = lineAlpha(d) * alpha;
            if (lineAlpha <= 0.01f) continue;

            float size = d <= 0f ? CURRENT_SIZE : lerp(CURRENT_SIZE, NEXT_SIZE, Math.min(d, 1f));
            float blur = d < 0f ? Math.min(-d / EXIT_DISTANCE, 1f) * MAX_BLUR : 0f;
            float y = yBase + offsets[i];
            for (String text : wrapped.get(i)) {
                drawLine(g, font, text, x, y, size, lineAlpha, blur, Math.abs(d) < 0.5f);
                y += size * 1.25f;
            }
        }
    }

    private void update(FontRenderer font, Lyrics incoming, boolean loading, long progressMs, float width, float reveal) {
        if (incoming != shown) {
            if (!incoming.isEmpty() && (shown.isEmpty() || fade.value() < 0.02f)) {
                // new song's lyrics: swap in at the right line, then fade in
                shown = incoming;
                layout(font, width);
                index = shown.indexAt(progressMs);
                scroll.snapTo(index);
                fade.animateTo(1f);
            } else if (!incoming.isEmpty() || loading) {
                fade.animateTo(0f); // old song's lyrics fade out before the new ones come in
            } else if (reveal <= 0f) {
                shown = Lyrics.EMPTY; // area finished closing
            }
            return;
        }
        if (shown.isEmpty()) return;

        int now = shown.indexAt(progressMs);
        if (now != index) {
            index = now;
            if (Math.abs(now - scroll.value()) > SNAP_DISTANCE) scroll.snapTo(now);
            else scroll.animateTo(now);
        }
    }

    private void layout(FontRenderer font, float width) {
        List<List<String>> lines = new ArrayList<>();
        for (int i = 0; ; i++) {
            Lyrics.Line line = shown.line(i);
            if (line == null) break;
            lines.add(line.text().isEmpty() ? List.of() : wrap(font, line.text(), CURRENT_SIZE, width));
        }
        wrapped = lines;
        offsets = new float[lines.size() + 1];
        for (int i = 0; i < lines.size(); i++) {
            offsets[i + 1] = offsets[i] + heightOf(i);
        }
    }

    private float heightOf(int i) {
        return Math.max(1, wrapped.get(i).size()) * LINE_HEIGHT + LINE_GAP;
    }

    /** Vertical position of fractional line {@code s} on the track; before line 0 lines are one row tall. */
    private float offsetAt(float s) {
        int k = (int) Math.floor(s);
        float f = s - k;
        if (k < 0) return (k + f) * (LINE_HEIGHT + LINE_GAP);
        if (k >= wrapped.size()) return offsets[wrapped.size()];
        return offsets[k] + f * heightOf(k);
    }

    private static float lineAlpha(float d) {
        if (d <= -EXIT_DISTANCE || d >= 2f) return 0f;
        if (d < 0f) return 1f + d / EXIT_DISTANCE;
        if (d < 1f) return lerp(1f, NEXT_ALPHA, d);
        return lerp(NEXT_ALPHA, 0f, d - 1f);
    }

    /** Text with a cheap blur: copies arranged in a ring, which reads as softness at small sizes. */
    private static void drawLine(GuiGraphicsExtractor g, FontRenderer font, String text, float x, float y,
                                 float size, float alpha, float blur, boolean shadow) {
        if (blur < 0.25f) {
            font.extract(g, text, x, y, color(alpha), shadow, size, false);
            return;
        }
        float spread = blur * 0.6f;
        font.extract(g, text, x, y, color(alpha * 0.35f), false, size, false);
        for (int i = 0; i < BLUR_SAMPLES; i++) {
            font.extract(g, text, x + BLUR_X[i] * spread, y + BLUR_Y[i] * spread, color(alpha * 0.22f), false, size, false);
        }
    }

    /** Word-wraps into at most two lines; the last one is truncated with an ellipsis. */
    private static List<String> wrap(FontRenderer font, String text, float size, float maxWidth) {
        List<String> lines = new ArrayList<>(MAX_WRAP_LINES);
        String rest = text;
        while (!rest.isEmpty() && lines.size() < MAX_WRAP_LINES - 1 && font.width(rest, size, true) > maxWidth) {
            int cut = rest.length();
            while (cut > 0 && font.width(rest.substring(0, cut), size, true) > maxWidth) {
                int space = rest.lastIndexOf(' ', cut - 1);
                cut = space > 0 ? space : cut - 1;
            }
            if (cut == 0) break;
            lines.add(rest.substring(0, cut));
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) lines.add(font.truncate(rest, size, maxWidth, "…", false, true));
        return lines;
    }

    private static int color(float alpha) {
        return (Math.round(Math.clamp(alpha, 0f, 1f) * 255f) << 24) | 0xFFFFFF;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
