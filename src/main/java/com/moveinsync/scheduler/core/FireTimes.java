package com.moveinsync.scheduler.core;

import java.time.*;
import java.util.*;
import org.springframework.scheduling.support.CronExpression;

/** Spring's zoned cron iterator skips nonexistent wall times and chooses the earlier offset in overlaps. */
public final class FireTimes {
    private FireTimes() {}
    public static Instant next(String cron, ZoneId zone, Instant after) {
        CronExpression expression=CronExpression.parse(cron);
        ZonedDateTime previous=after.atZone(zone);
        ZonedDateTime next = expression.next(previous);
        // Spring can return both offsets of an overlapped wall time; this API fires it once.
        if(next!=null && next.toLocalDateTime().equals(previous.toLocalDateTime()) && !next.getOffset().equals(previous.getOffset())) next=expression.next(next);
        if (next == null) throw new IllegalArgumentException("Cron has no next occurrence");
        return next.toInstant();
    }
    public static List<Instant> nextN(String cron, ZoneId zone, Instant after, int count) {
        if (count < 1 || count > 100) throw new IllegalArgumentException("Count must be 1..100");
        List<Instant> result = new ArrayList<>();
        for (int i = 0; i < count; i++) { after = next(cron, zone, after); result.add(after); }
        return result;
    }
}
