package com.dankhole.airlockandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;
import java.util.TimeZone;

public class UsageTrackerTest {
    private static final TimeZone NEW_YORK = TimeZone.getTimeZone("America/New_York");

    @Test
    public void rejectsSystemBucketContainingYesterdayEvenWhenUsageIsRecent() {
        UsageTracker.Snapshot snapshot = snapshot("2026-09-13T12:00:00Z");

        snapshot.addBucket("app.one", snapshot.startMs - 1L, snapshot.endMs, 3_600_000L);

        assertTrue(snapshot.usageByPackage.isEmpty());
    }

    @Test
    public void sumsOnlyBucketsEntirelyInsideTheCapturedDay() {
        UsageTracker.Snapshot snapshot = snapshot("2026-09-13T12:00:00Z");
        long midpoint = snapshot.startMs + 3_600_000L;

        snapshot.addBucket("app.one", snapshot.startMs, midpoint, 60_000L);
        snapshot.addBucket("app.one", midpoint, snapshot.endMs, 120_000L);
        snapshot.addBucket("app.one", snapshot.startMs - 86_400_000L, midpoint, 7_200_000L);

        assertEquals(Long.valueOf(180_000L), snapshot.usageByPackage.get("app.one"));
        assertEquals("20260913", snapshot.day);
        assertEquals(Instant.parse("2026-09-13T04:00:00Z").toEpochMilli(), snapshot.startMs);
    }

    @Test
    public void rejectsFutureMalformedAndImpossibleBuckets() {
        UsageTracker.Snapshot snapshot = snapshot("2026-09-13T12:00:00Z");

        snapshot.addBucket("future", snapshot.startMs, snapshot.endMs + 1L, 60_000L);
        snapshot.addBucket("reversed", snapshot.endMs, snapshot.startMs, 60_000L);
        snapshot.addBucket("negative", snapshot.startMs, snapshot.endMs, -1L);
        snapshot.addBucket("inflated", snapshot.startMs, snapshot.startMs + 1_000L, 60_000L);

        assertTrue(snapshot.usageByPackage.isEmpty());
    }

    @Test
    public void midnightCompletionCannotWriteThePreviousDaysObservationIntoToday() {
        UsageTracker.Snapshot snapshot = snapshot("2026-09-14T03:59:59Z");

        assertEquals("20260913", snapshot.day);
        assertTrue(snapshot.matchesDay(Instant.parse("2026-09-14T03:59:59.500Z").toEpochMilli(), NEW_YORK));
        assertFalse(snapshot.matchesDay(Instant.parse("2026-09-14T04:00:00Z").toEpochMilli(), NEW_YORK));
    }

    @Test
    public void timezoneChangeInvalidatesObservationEvenIfDateLabelIsUnchanged() {
        UsageTracker.Snapshot snapshot = snapshot("2026-09-13T12:00:00Z");

        assertFalse(snapshot.matchesDay(snapshot.endMs, TimeZone.getTimeZone("America/Los_Angeles")));
        assertFalse(snapshot.matchesDay(snapshot.endMs - 1L, NEW_YORK));
    }

    @Test
    public void daylightSavingDaysUseLocalMidnightRatherThanFixedDayLength() {
        UsageTracker.Snapshot spring = snapshot("2026-03-09T03:30:00Z");
        UsageTracker.Snapshot fall = snapshot("2026-11-02T04:30:00Z");

        assertEquals(Instant.parse("2026-03-08T05:00:00Z").toEpochMilli(), spring.startMs);
        assertEquals(Instant.parse("2026-11-01T04:00:00Z").toEpochMilli(), fall.startMs);
        assertEquals(22L * 3_600_000L + 30L * 60_000L, spring.endMs - spring.startMs);
        assertEquals(24L * 3_600_000L + 30L * 60_000L, fall.endMs - fall.startMs);
    }

    private static UsageTracker.Snapshot snapshot(String instant) {
        return new UsageTracker.Snapshot(Instant.parse(instant).toEpochMilli(), NEW_YORK);
    }
}
