package com.noteblockaddons.client;

/**
 * A value that animates toward a target. Changing the target mid-animation starts the new
 * animation from wherever the value currently is, so animations are interruptible.
 */
public final class Tween {
    private final long durationNanos;
    private final Easing easing;
    private float from;
    private float to;
    private long startNanos;

    public Tween(float initial, long durationMs, Easing easing) {
        this.durationNanos = durationMs * 1_000_000L;
        this.easing = easing;
        this.from = initial;
        this.to = initial;
    }

    public float value() {
        float t = (System.nanoTime() - startNanos) / (float) durationNanos;
        return t >= 1f ? to : from + (to - from) * easing.apply(Math.max(0f, t));
    }

    public float target() {
        return to;
    }

    public void animateTo(float target) {
        if (target == to) return;
        from = value();
        to = target;
        startNanos = System.nanoTime();
    }

    public void snapTo(float value) {
        from = value;
        to = value;
        startNanos = 0L;
    }
}
