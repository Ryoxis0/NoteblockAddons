package com.noteblockaddons.client;

import foo.starred.cascade.graphics.extensions.blur.BlurExtensionsKt;
import foo.starred.cascade.graphics.extensions.image.ImageExtensionsKt;
import foo.starred.cascade.graphics.extensions.rectangle.hollow.HollowRectangleExtensionsKt;
import foo.starred.cascade.graphics.extensions.rectangle.rounded.RoundedRectangleExtensionKt;
import foo.starred.cascade.graphics.extensions.shadow.DropShadowExtensionsKt;
import foo.starred.cascade.graphics.extensions.triangle.TriangleExtensionsKt;
import foo.starred.cascade.graphics.font.CascadeFonts;
import foo.starred.cascade.graphics.font.rendering.impl.FontRenderer;
import foo.starred.cascade.graphics.geometry.CascadeGeometricColor;
import foo.starred.cascade.graphics.geometry.CascadeGeometricOffset;
import foo.starred.cascade.graphics.geometry.CascadeGeometricRadius;
import foo.starred.cascade.graphics.states.impl.image.data.CascadeImageFilter;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fStack;

/**
 * Draws the panel in a fixed WIDTH-wide space; position and scale are applied once via the pose.
 * (v1 multiplied every coordinate by the scale by hand. Boxes shrank, text didn't, because text
 * has no size parameter. Scaling the pose is the only thing that shrinks everything together.)
 * Trailing nulls on Cascade calls are Kotlin default args (pose, scissor, bounds).
 */
public final class HudRenderer {
    public static final int WIDTH = 200;
    public static final int HEIGHT = 64;
    private static final float LYRICS_HEIGHT = 40f;
    private static final float LYRICS_TOP = HEIGHT + 2f;
    private static final float LYRICS_CLIP_TOP = HEIGHT - 6f;

    private static final float PAD = 8f;
    private static final float ART_SIZE = 48f;
    private static final float CONTENT_X = PAD + ART_SIZE + 10f;
    private static final float CONTENT_W = WIDTH - CONTENT_X - 10f;
    private static final float LYRICS_W = WIDTH - 2 * PAD;

    private static final float TITLE_Y = 11f;
    private static final float TITLE_SIZE = 9f;
    private static final float ARTIST_Y = 24f;
    private static final float ARTIST_SIZE = 7.5f;
    private static final float BAR_Y = 41f;
    private static final float BAR_H = 2f;
    private static final float TIME_Y = 48f;
    private static final float TIME_SIZE = 6.5f;
    private static final float ICON_SIZE = 6f;
    private static final float HINT_SIZE = 7f;

    private static final int EQ_BARS = 5;
    private static final float EQ_BAR_W = 4f;
    private static final float EQ_GAP = 3f;
    private static final float EQ_MIN_H = 4f;
    private static final float EQ_MAX_H = 24f;
    private static final double[] EQ_SPEED = {2.1, 3.3, 2.7, 3.9, 2.4};
    private static final double[] EQ_PHASE = {0.0, 1.3, 2.6, 0.7, 1.9};

    private static final float BLUR_STRENGTH = 10f;
    private static final float SHADOW_BLUR = 14f;
    private static final float GLOW_BLUR = 6f;

    private static final int SHADOW_COLOR = 0x50000000;
    private static final int BORDER_COLOR = 0x33FFFFFF;
    private static final int PLACEHOLDER_COLOR = 0x22FFFFFF;
    private static final int TRACK_COLOR = 0x59000000; // dark groove, so the glowing fill pops
    private static final int TITLE_COLOR = 0xFFFFFFFF;
    private static final int ARTIST_COLOR = 0xC8FFFFFF;
    private static final int TIME_COLOR = 0x99FFFFFF;
    private static final int ICON_COLOR = 0xFFFFFFFF;
    private static final int PREVIEW_COLOR = 0xB0FFFFFF;
    private static final int BG_ALPHA = 0xA0;
    private static final int GLOW_ALPHA = 0xB0;

    private static final CascadeGeometricRadius PANEL_RADIUS = new CascadeGeometricRadius(12f);
    private static final CascadeGeometricRadius SHEEN_RADIUS = new CascadeGeometricRadius(12f, 12f, 0f, 0f);
    private static final CascadeGeometricRadius ART_RADIUS = new CascadeGeometricRadius(7f);
    private static final CascadeGeometricRadius BAR_RADIUS = new CascadeGeometricRadius(0f); // flat, square ends
    private static final CascadeGeometricRadius EQ_RADIUS = new CascadeGeometricRadius(EQ_BAR_W / 2f);
    private static final CascadeGeometricRadius PREVIEW_RADIUS = new CascadeGeometricRadius(14f);
    private static final CascadeGeometricOffset SHADOW_OFFSET = new CascadeGeometricOffset(0, 3);
    private static final CascadeGeometricOffset NO_OFFSET = new CascadeGeometricOffset(0);
    private static final CascadeGeometricColor WHITE = new CascadeGeometricColor(0xFFFFFFFF);
    private static final CascadeGeometricColor SHEEN = new CascadeGeometricColor(0x1CFFFFFF, 0x00FFFFFF);

    // Play/pause icon, drawn in a 60x60 box centered on 0,0 and scaled down to ICON_SIZE.
    // Quads are corners clockwise from top-left. The play halves overlap (x 4..6) so no seam shows.
    private static final float ICON_BOX = 60f;
    private static final float[][] PAUSE = {
            {-24f, -30f, -8f, -30f, -8f, 30f, -24f, 30f},
            {8f, -30f, 24f, -30f, 24f, 30f, 8f, 30f}};
    private static final float[][] PLAY = {
            {-22f, -30f, 6f, -13.85f, 6f, 13.85f, -22f, 30f},
            {4f, -15f, 30f, 0f, 30f, 0f, 4f, 15f}};
    private static final float[] PLAY_TIP = {-22f, -30f, 30f, 0f, -22f, 30f};
    private static final CascadeGeometricRadius ICON_BAR_RADIUS = new CascadeGeometricRadius(3f);

    // Opening/closing the lyrics area: user-initiated, so a strong ease-out.
    private static final Tween LYRICS_OPEN = new Tween(0f, 280, Easing.EASE_OUT);
    // 0 = pause icon (playing), 1 = play icon (paused).
    private static final Tween PLAY_ICON = new Tween(0f, 200, Easing.EASE_OUT);
    private static final LyricsView LYRICS = new LyricsView();
    private static boolean iconInitialized;

    private HudRenderer() {}

    /** Unscaled panel height, including however far the lyrics area is currently open. */
    public static float height() {
        return HEIGHT + LYRICS_HEIGHT * LYRICS_OPEN.value();
    }

    public static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Settings s = MediaManager.get().settings();
        if (!s.hudEnabled || Screens.current() instanceof HudEditorScreen) return;
        draw(graphics, s.hudX, s.hudY, s.hudScale, false);
    }

    public static void draw(GuiGraphicsExtractor g, float x, float y, float scale, boolean preview) {
        MediaManager manager = MediaManager.get();
        NowPlaying np = manager.nowPlaying();
        Lyrics lyrics = manager.lyrics();
        boolean lyricsLoading = manager.lyricsLoading();
        int primary = manager.primaryColor() & 0xFFFFFF;
        int secondary = manager.secondaryColor() & 0xFFFFFF;
        int accent = mix(primary, 0xFFFFFF, 0.35f);
        int accent2 = mix(secondary, 0xFFFFFF, 0.35f);
        // stay as-is while the next song's lyrics load, so the area doesn't flicker shut and open
        if (!lyrics.isEmpty()) LYRICS_OPEN.animateTo(1f);
        else if (!lyricsLoading) LYRICS_OPEN.animateTo(0f);
        float open = LYRICS_OPEN.value();
        float height = HEIGHT + LYRICS_HEIGHT * open;
        long progress = np.progressNow();
        FontRenderer font = CascadeFonts.INSTANCE.getSans();

        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(scale, scale);

        drawPanel(g, height, primary, secondary);
        drawArt(g, manager.cover(), accent, accent2);

        font.extract(g, font.truncate(np.title(), TITLE_SIZE, CONTENT_W, "…", false, true),
                CONTENT_X, TITLE_Y, TITLE_COLOR, true, TITLE_SIZE, true);
        font.extract(g, font.truncate(np.artist(), ARTIST_SIZE, CONTENT_W, "…", false, true),
                CONTENT_X, ARTIST_Y, ARTIST_COLOR, false, ARTIST_SIZE, true);

        drawProgress(g, np.progressRatio(), accent, accent2);

        if (np.durationMs() > 0L) {
            String total = formatTime(np.durationMs());
            font.extract(g, formatTime(progress), CONTENT_X, TIME_Y, TIME_COLOR, false, TIME_SIZE, true);
            font.extract(g, total, CONTENT_X + CONTENT_W - font.width(total, TIME_SIZE, true), TIME_Y, TIME_COLOR, false, TIME_SIZE, true);
        }
        if (np.hasMedia()) drawPlayState(g, np.playing(), CONTENT_X + CONTENT_W / 2f, TIME_Y + TIME_SIZE / 2f);

        if (open > 0f) {
            g.enableScissor(0, (int) LYRICS_CLIP_TOP, WIDTH, (int) Math.ceil(height - 3f));
            LYRICS.draw(g, font, lyrics, lyricsLoading, progress, PAD, LYRICS_TOP, LYRICS_W, open);
            g.disableScissor();
        }

        if (preview) {
            HollowRectangleExtensionsKt.hollowRectangle(g, -3f, -3f, WIDTH + 6f, height + 6f, 1f, PREVIEW_COLOR, PREVIEW_RADIUS, true, null, null, null);
            font.extract(g, "Drag to move  •  Scroll to resize  •  Esc to save", 2f, height + 7f, PREVIEW_COLOR, true, HINT_SIZE, true);
        }

        pose.popMatrix();
    }

    // One rounded rect with a color per corner. The first attempt faked a gradient with 34 thin
    // strips and banded like a 2004 website.
    private static void drawPanel(GuiGraphicsExtractor g, float height, int primary, int secondary) {
        int middle = mix(primary, secondary, 0.5f);
        CascadeGeometricColor background = new CascadeGeometricColor(
                argb(BG_ALPHA, mix(primary, 0xFFFFFF, 0.1f)),
                argb(BG_ALPHA, middle),
                argb(BG_ALPHA, mix(middle, 0x000000, 0.2f)),
                argb(BG_ALPHA, mix(secondary, 0x000000, 0.3f)));

        DropShadowExtensionsKt.dropShadow(g, 0f, 0f, WIDTH, height, SHADOW_OFFSET, SHADOW_BLUR, 0f, SHADOW_COLOR, PANEL_RADIUS, null, null, null);
        BlurExtensionsKt.blur(g, 0f, 0f, WIDTH, height, 0, PANEL_RADIUS, BLUR_STRENGTH, null, null, null);
        RoundedRectangleExtensionKt.roundedRectangle(g, 0f, 0f, WIDTH, height, background, PANEL_RADIUS, null, null, null);
        RoundedRectangleExtensionKt.roundedRectangle(g, 0f, 0f, WIDTH, HEIGHT / 2f, SHEEN, SHEEN_RADIUS, null, null, null);
        HollowRectangleExtensionsKt.hollowRectangle(g, 0f, 0f, WIDTH, height, 1f, BORDER_COLOR, PANEL_RADIUS, true, null, null, null);
    }

    private static void drawArt(GuiGraphicsExtractor g, Identifier cover, int accent, int accent2) {
        if (cover != null) {
            ImageExtensionsKt.image(g, cover, PAD, PAD, ART_SIZE, ART_SIZE, 0f, 0f, 1f, 1f, WHITE, ART_RADIUS,
                    CascadeImageFilter.LINEAR, null, null, null);
            return;
        }

        // no cover: dancing bars. (This used to be a ♪, until it turned out Cascade's font doesn't
        // have that glyph and everyone got a box with an X in it instead.)
        RoundedRectangleExtensionKt.roundedRectangle(g, PAD, PAD, ART_SIZE, ART_SIZE, PLACEHOLDER_COLOR, ART_RADIUS, null, null, null);

        double seconds = System.currentTimeMillis() / 1000.0;
        float barsWidth = EQ_BARS * EQ_BAR_W + (EQ_BARS - 1) * EQ_GAP;
        float startX = PAD + (ART_SIZE - barsWidth) / 2f;
        float centerY = PAD + ART_SIZE / 2f;
        CascadeGeometricColor barColor = new CascadeGeometricColor(0xFF000000 | accent, 0xFF000000 | accent2);
        for (int i = 0; i < EQ_BARS; i++) {
            float wave = (float) (0.5 + 0.5 * Math.sin(seconds * EQ_SPEED[i] + EQ_PHASE[i]));
            float h = EQ_MIN_H + (EQ_MAX_H - EQ_MIN_H) * wave;
            float bx = startX + i * (EQ_BAR_W + EQ_GAP);
            RoundedRectangleExtensionKt.roundedRectangle(g, bx, centerY - h / 2f, EQ_BAR_W, h, barColor, EQ_RADIUS, null, null, null);
        }
    }

    private static void drawProgress(GuiGraphicsExtractor g, float ratio, int left, int right) {
        RoundedRectangleExtensionKt.roundedRectangle(g, CONTENT_X, BAR_Y, CONTENT_W, BAR_H, TRACK_COLOR, BAR_RADIUS, null, null, null);

        float filled = CONTENT_W * ratio;
        if (filled < 1f) return;

        CascadeGeometricColor fill = CascadeGeometricColor.Companion.horizontal(0xFF000000 | left, 0xFF000000 | right);
        DropShadowExtensionsKt.dropShadow(g, CONTENT_X, BAR_Y, filled, BAR_H, NO_OFFSET, GLOW_BLUR, 0.5f, argb(GLOW_ALPHA, left), BAR_RADIUS, null, null, null);
        RoundedRectangleExtensionKt.roundedRectangle(g, CONTENT_X, BAR_Y, filled, BAR_H, fill, BAR_RADIUS, null, null, null);
    }

    /**
     * Pause while playing, play while paused; the shapes morph into each other.
     *
     * Third time's the charm. Cascade's triangle shader truncates corner offsets to whole numbers,
     * which on a 6px icon turned the play button into modern art (attempt 1). So it's drawn at 10x
     * and shrunk with the pose. Then two triangles sharing an edge each soften that edge, which left
     * hairline seams and tiny holes in the pause bars at big HUD sizes (attempt 2). So at rest it's
     * real rounded rects and one triangle; the triangle soup only exists mid-morph, for 200ms,
     * where nobody can see a seam.
     */
    private static void drawPlayState(GuiGraphicsExtractor g, boolean playing, float cx, float cy) {
        if (!iconInitialized) {
            PLAY_ICON.snapTo(playing ? 0f : 1f);
            iconInitialized = true;
        }
        PLAY_ICON.animateTo(playing ? 0f : 1f);
        float t = PLAY_ICON.value();

        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(cx, cy);
        pose.scale(ICON_SIZE / ICON_BOX, ICON_SIZE / ICON_BOX);
        if (t <= 0.001f) {
            for (float[] bar : PAUSE) {
                RoundedRectangleExtensionKt.roundedRectangle(g, bar[0], bar[1], bar[2] - bar[0], bar[5] - bar[1],
                        ICON_COLOR, ICON_BAR_RADIUS, null, null, null);
            }
        } else if (t >= 0.999f) {
            triangle(g, PLAY_TIP[0], PLAY_TIP[1], PLAY_TIP[2], PLAY_TIP[3], PLAY_TIP[4], PLAY_TIP[5]);
        } else {
            float[] q = new float[8];
            for (int shape = 0; shape < 2; shape++) {
                for (int i = 0; i < 8; i++) q[i] = PAUSE[shape][i] + (PLAY[shape][i] - PAUSE[shape][i]) * t;
                triangle(g, q[0], q[1], q[2], q[3], q[4], q[5]);
                triangle(g, q[2], q[3], q[4], q[5], q[6], q[7]);
                triangle(g, q[4], q[5], q[6], q[7], q[0], q[1]);
                triangle(g, q[6], q[7], q[0], q[1], q[2], q[3]);
            }
        }
        pose.popMatrix();
    }

    /** Skips zero-area triangles, which the shader can't draw (it divides by edge length). */
    private static void triangle(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, float x2, float y2) {
        float area = Math.abs((x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0));
        if (area < 1f) return;
        TriangleExtensionsKt.triangle(g, x0, y0, x1, y1, x2, y2, ICON_COLOR, null, null);
    }

    private static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int gr = Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return (r << 16) | (gr << 8) | bl;
    }

    private static int argb(int alpha, int rgb) {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    private static String formatTime(long ms) {
        long seconds = ms / 1000L;
        return (seconds / 60L) + ":" + String.format("%02d", seconds % 60L);
    }
}
