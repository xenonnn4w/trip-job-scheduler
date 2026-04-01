package com.moveinsync.scheduler.core;

import java.util.*;

public final class ShardAssignment {
    private ShardAssignment() {}
    public static int shard(UUID jobId, int count) { return Math.floorMod(jobId.hashCode(), count); }
    public static Set<Integer> owned(String self, List<String> active, int count) {
        if (active.isEmpty()) return Set.of();
        List<String> sorted = active.stream().distinct().sorted().toList();
        Set<Integer> result = new HashSet<>();
        for (int i = 0; i < count; i++) if (sorted.get(i % sorted.size()).equals(self)) result.add(i);
        return result;
    }
}
