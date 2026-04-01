package com.moveinsync.scheduler.core;

import java.time.Instant;
import java.util.List;

public final class Misfires {
    private Misfires() {}
    public static List<Instant> runs(String policy, List<Instant> missed, Instant now) {
        if (missed.isEmpty()) return List.of();
        return switch (policy) {
            case "FIRE_NOW" -> missed;
            case "SKIP" -> List.of();
            case "COALESCE" -> List.of(missed.getLast());
            default -> throw new IllegalArgumentException("Unknown misfire policy");
        };
    }
}
