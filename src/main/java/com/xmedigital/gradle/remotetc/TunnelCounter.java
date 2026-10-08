package com.xmedigital.gradle.remotetc;

import java.util.concurrent.atomic.AtomicInteger;

/** Counts Test tasks whose actions executed. Thread-safe: Test tasks may run in parallel. */
final class TunnelCounter {

    private final AtomicInteger ran = new AtomicInteger();

    void countRan() {
        ran.incrementAndGet();
    }

    int ranCount() {
        return ran.get();
    }
}
