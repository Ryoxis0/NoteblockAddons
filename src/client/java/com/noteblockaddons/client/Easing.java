package com.noteblockaddons.client;

/** Easing curves, following Emil Kowalski's recommendations. */
@FunctionalInterface
public interface Easing {
    /** Strong ease-out: things appearing, disappearing, or reacting to the player. */
    Easing EASE_OUT = cubicBezier(0.23f, 1f, 0.32f, 1f);
    /** Strong ease-in-out: things already on screen moving somewhere else. */
    Easing EASE_IN_OUT = cubicBezier(0.77f, 0f, 0.175f, 1f);

    float apply(float t);

    /** CSS-style cubic-bezier(x1, y1, x2, y2). */
    static Easing cubicBezier(float x1, float y1, float x2, float y2) {
        float cx = 3f * x1, bx = 3f * (x2 - x1) - cx, ax = 1f - cx - bx;
        float cy = 3f * y1, by = 3f * (y2 - y1) - cy, ay = 1f - cy - by;
        return t -> {
            if (t <= 0f) return 0f;
            if (t >= 1f) return 1f;
            // find the curve parameter whose x equals t: Newton first, bisection if it doesn't converge
            float u = t;
            for (int i = 0; i < 8; i++) {
                float x = ((ax * u + bx) * u + cx) * u - t;
                if (Math.abs(x) < 1e-5f) return ((ay * u + by) * u + cy) * u;
                float dx = (3f * ax * u + 2f * bx) * u + cx;
                if (Math.abs(dx) < 1e-6f) break;
                u -= x / dx;
            }
            float lo = 0f, hi = 1f;
            u = t;
            for (int i = 0; i < 30; i++) {
                float x = ((ax * u + bx) * u + cx) * u;
                if (Math.abs(x - t) < 1e-5f) break;
                if (x < t) lo = u; else hi = u;
                u = (lo + hi) / 2f;
            }
            return ((ay * u + by) * u + cy) * u;
        };
    }
}
