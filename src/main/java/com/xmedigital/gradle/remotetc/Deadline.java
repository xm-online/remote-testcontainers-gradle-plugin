package com.xmedigital.gradle.remotetc;

import java.time.Duration;

/** One time budget shared by every step of setup. */
final class Deadline {

    private final long endNanos;
    private final Duration limit;

    private Deadline(long endNanos, Duration limit) {
        this.endNanos = endNanos;
        this.limit = limit;
    }

    static Deadline after(Duration limit) {
        return new Deadline(System.nanoTime() + limit.toNanos(), limit);
    }

    Duration remaining() {
        return Duration.ofNanos(Math.max(0L, endNanos - System.nanoTime()));
    }

    boolean expired() {
        return System.nanoTime() >= endNanos;
    }

    Duration limit() {
        return limit;
    }
}
