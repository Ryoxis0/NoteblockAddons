package com.noteblockaddons.client;

import com.mojang.blaze3d.platform.NativeImage;

import java.awt.Color;

/** Picks the two colors the HUD gradient is built from. */
public final class AlbumColors {
    public static final int DEFAULT_PRIMARY = 0xFF5F6B99;
    public static final int DEFAULT_SECONDARY = 0xFF2E3550;

    private static final int SAMPLE_GRID = 28;
    private static final int MIN_ALPHA = 200;
    private static final int MIN_BRIGHTNESS = 55;   // r+g+b, skips near-black
    private static final int MAX_BRIGHTNESS = 710;  // skips near-white
    private static final int MIN_CHROMA = 18;       // skips greys
    private static final double PRIMARY_BOOST = 1.05;
    private static final double MONOCHROME_SHADE = 0.45;

    private AlbumColors() {}

    /** Returns {primary, secondary}: the chroma-weighted average color and the sample with the most different hue. */
    public static int[] extract(NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int stepX = Math.max(1, width / SAMPLE_GRID);
        int stepY = Math.max(1, height / SAMPLE_GRID);

        int count = 0;
        int[] samples = new int[((width + stepX - 1) / stepX) * ((height + stepY - 1) / stepY)];
        long rSum = 0, gSum = 0, bSum = 0, weightSum = 0;

        for (int y = 0; y < height; y += stepY) {
            for (int x = 0; x < width; x += stepX) {
                // ARGB on this version. It was unpacked as ABGR once and every blue album painted
                // the HUD brown. Took a purple cover and a very confused screenshot to notice.
                int pixel = image.getPixel(x, y);
                if ((pixel >>> 24) < MIN_ALPHA) continue;
                int r = (pixel >>> 16) & 0xFF, g = (pixel >>> 8) & 0xFF, b = pixel & 0xFF;
                int sum = r + g + b;
                int chroma = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
                if (sum < MIN_BRIGHTNESS || sum > MAX_BRIGHTNESS || chroma < MIN_CHROMA) continue;
                samples[count++] = pixel;
                rSum += (long) r * chroma;
                gSum += (long) g * chroma;
                bSum += (long) b * chroma;
                weightSum += chroma;
            }
        }
        if (weightSum == 0) return new int[]{DEFAULT_PRIMARY, DEFAULT_SECONDARY};

        int pr = clamp255(rSum * PRIMARY_BOOST / weightSum);
        int pg = clamp255(gSum * PRIMARY_BOOST / weightSum);
        int pb = clamp255(bSum * PRIMARY_BOOST / weightSum);
        int primary = 0xFF000000 | (pr << 16) | (pg << 8) | pb;
        float primaryHue = Color.RGBtoHSB(pr, pg, pb, null)[0];

        int secondary = 0;
        double bestScore = 0;
        float[] hsv = new float[3];
        for (int i = 0; i < count; i++) {
            int p = samples[i];
            int r = (p >>> 16) & 0xFF, g = (p >>> 8) & 0xFF, b = p & 0xFF;
            Color.RGBtoHSB(r, g, b, hsv);
            float hueDistance = Math.abs(hsv[0] - primaryHue);
            hueDistance = Math.min(hueDistance, 1f - hueDistance);
            double score = hueDistance * hsv[1] * (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)));
            if (score > bestScore) {
                bestScore = score;
                secondary = 0xFF000000 | (p & 0xFFFFFF);
            }
        }
        if (secondary == 0) {
            secondary = 0xFF000000 | (clamp255(pr * MONOCHROME_SHADE) << 16) | (clamp255(pg * MONOCHROME_SHADE) << 8) | clamp255(pb * MONOCHROME_SHADE);
        }
        return new int[]{primary, secondary};
    }

    private static int clamp255(double value) {
        return (int) Math.clamp(Math.round(value), 0L, 255L);
    }
}
