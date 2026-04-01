package com.moveinsync.scheduler.core;

import java.time.Duration;
import java.util.random.RandomGenerator;

public final class Backoff {
    private Backoff() {}
    public static Duration delay(int attempt, RandomGenerator random) {
        long ceiling = Math.min(300_000L, 1000L << Math.min(18, Math.max(0, attempt - 1)));
        return Duration.ofMillis((long) (ceiling * (0.5 + random.nextDouble() * 0.5)));
    }
}
