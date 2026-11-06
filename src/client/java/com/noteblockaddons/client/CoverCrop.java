package com.noteblockaddons.client;

import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * Turns whatever picture an app hands Windows into a clean square cover:
 * removes Spotify's padding and logo, trims black letterbox bars, and center-crops to a square
 * (video thumbnails from YouTube etc. are wide).
 */
public final class CoverCrop {
    // Spotify bakes its own logo and padding into the picture it gives Windows. On purpose.
    // The real art is a 234x234 square at (33, 0) of a 300x300 image, logo strip underneath.
    // Numbers from ModernFlyouts (MIT), whose comment on this is not fit for print. Scaled in
    // case Spotify ever changes the size, because of course they will.
    private static final double SPOTIFY_ART_X = 33.0 / 300.0;
    private static final double SPOTIFY_ART_SIZE = 234.0 / 300.0;

    private static final int BAR_MAX_BRIGHTNESS = 24;   // 0..255; letterbox bars are near-black
    private static final double MAX_BAR_FRACTION = 0.3; // never trim more than this per side

    private CoverCrop() {}

    public static BufferedImage crop(BufferedImage image, String appId) {
        BufferedImage result = image;
        if (isSpotify(appId) && image.getWidth() == image.getHeight()) {
            int x = (int) Math.round(image.getWidth() * SPOTIFY_ART_X);
            int size = (int) Math.round(image.getWidth() * SPOTIFY_ART_SIZE);
            result = image.getSubimage(x, 0, Math.min(size, image.getWidth() - x), Math.min(size, image.getHeight()));
        }
        // album covers are square already; only wide/tall pictures (video thumbnails) get trimmed,
        // so real covers with black edges are never cut into
        if (result.getWidth() != result.getHeight()) result = trimBars(result);
        return centerSquare(result);
    }

    static boolean isSpotify(String appId) {
        return appId != null && appId.toLowerCase(Locale.ROOT).contains("spotify");
    }

    /** Cuts off matching pairs of near-black bars on opposite edges (letterboxing). */
    static BufferedImage trimBars(BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        int maxX = (int) (w * MAX_BAR_FRACTION), maxY = (int) (h * MAX_BAR_FRACTION);
        int top = 0, bottom = 0, left = 0, right = 0;
        while (top < maxY && darkRow(image, top, 0, w)) top++;
        while (bottom < maxY && darkRow(image, h - 1 - bottom, 0, w)) bottom++;
        while (left < maxX && darkColumn(image, left, top, h - bottom)) left++;
        while (right < maxX && darkColumn(image, w - 1 - right, top, h - bottom)) right++;
        if (!pair(top, bottom, h)) top = bottom = 0;
        if (!pair(left, right, w)) left = right = 0;
        if (top + bottom + left + right == 0) return image;
        return image.getSubimage(left, top, w - left - right, h - top - bottom);
    }

    /** Letterbox bars come in equal pairs; one dark edge on its own is part of the picture. */
    private static boolean pair(int a, int b, int length) {
        return a > 0 && b > 0 && Math.abs(a - b) <= Math.max(2, length / 50);
    }

    static BufferedImage centerSquare(BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        if (w == h) return image;
        int size = Math.min(w, h);
        return image.getSubimage((w - size) / 2, (h - size) / 2, size, size);
    }

    private static boolean darkRow(BufferedImage image, int y, int x0, int x1) {
        for (int x = x0; x < x1; x++) if (!dark(image.getRGB(x, y))) return false;
        return true;
    }

    private static boolean darkColumn(BufferedImage image, int x, int y0, int y1) {
        for (int y = y0; y < y1; y++) if (!dark(image.getRGB(x, y))) return false;
        return true;
    }

    private static boolean dark(int argb) {
        int r = (argb >>> 16) & 0xFF, g = (argb >>> 8) & 0xFF, b = argb & 0xFF;
        return Math.max(r, Math.max(g, b)) <= BAR_MAX_BRIGHTNESS;
    }
}
