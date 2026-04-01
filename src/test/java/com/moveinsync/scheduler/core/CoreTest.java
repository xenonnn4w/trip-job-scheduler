package com.moveinsync.scheduler.core;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CoreTest {
    @Test void springForwardSkipsMissingLocalTime() {
        ZoneId zone=ZoneId.of("America/New_York");
        Instant after=ZonedDateTime.of(2026,3,7,2,30,0,0,zone).toInstant();
        Instant next=FireTimes.next("0 30 2 * * *",zone,after);
        assertEquals(LocalDate.of(2026,3,9),next.atZone(zone).toLocalDate());
    }
    @Test void fallBackFiresOnceAtEarlierOffset() {
        ZoneId zone=ZoneId.of("America/New_York");
        Instant before=Instant.parse("2026-11-01T04:00:00Z");
        Instant first=FireTimes.next("0 30 1 * * *",zone,before);
        Instant second=FireTimes.next("0 30 1 * * *",zone,first);
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"),first);
        assertEquals(LocalDate.of(2026,11,2),second.atZone(zone).toLocalDate());
    }
    @Test void shardsMoveWhenNodeLeaves() {
        Set<Integer> a=ShardAssignment.owned("a",List.of("a","b","c"),256);
        Set<Integer> b=ShardAssignment.owned("b",List.of("a","b","c"),256);
        Set<Integer> c=ShardAssignment.owned("c",List.of("a","b","c"),256);
        Set<Integer> all=new HashSet<>(a);all.addAll(b);all.addAll(c);
        assertEquals(256,all.size());
        assertTrue(Collections.disjoint(a,b));
        Set<Integer> after=new HashSet<>(ShardAssignment.owned("a",List.of("a","c"),256));
        after.addAll(ShardAssignment.owned("c",List.of("a","c"),256));
        assertEquals(256,after.size());
    }
    @Test void misfiresAndBackoff() {
        List<Instant> missed=List.of(Instant.EPOCH,Instant.EPOCH.plusSeconds(60));
        assertEquals(2,Misfires.runs("FIRE_NOW",missed,Instant.EPOCH.plusSeconds(120)).size());
        assertTrue(Misfires.runs("SKIP",missed,Instant.EPOCH.plusSeconds(120)).isEmpty());
        assertEquals(List.of(missed.getLast()),Misfires.runs("COALESCE",missed,Instant.EPOCH.plusSeconds(120)));
        assertTrue(Backoff.delay(2,new Random(42)).toMillis()>=1000);
    }
}
