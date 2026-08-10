package com.dankhole.airlockandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class UsageLedgerTest {
    @Test
    public void batchesDirtyUsageUntilPersistInterval() {
        FakeStore store = new FakeStore();
        AtomicLong elapsed = new AtomicLong(1_000L);
        UsageLedger ledger = new UsageLedger(store, elapsed::get, 30_000L);

        ledger.ensure(Collections.singleton("app.one"));
        ledger.add("app.one", 750L);
        ledger.flush(false);
        assertTrue(store.lastSaved.isEmpty());

        elapsed.set(31_000L);
        ledger.flush(false);
        assertEquals(Long.valueOf(750L), store.lastSaved.get("app.one"));
    }

    @Test
    public void newerSystemObservationReplacesAndCleansPendingDelta() {
        FakeStore store = new FakeStore();
        AtomicLong elapsed = new AtomicLong();
        UsageLedger ledger = new UsageLedger(store, elapsed::get, 30_000L);

        ledger.ensure(Collections.singleton("app.one"));
        ledger.add("app.one", 500L);
        ledger.mergeObserved("20260809", Collections.singletonMap("app.one", 900L));
        ledger.flush(true);

        assertEquals(900L, ledger.usageMs("app.one"));
        assertTrue(store.lastSaved.isEmpty());
    }

    @Test
    public void smallerSystemObservationDoesNotDiscardFreshPollingUsage() {
        FakeStore store = new FakeStore();
        store.values.put(key("20260809", "app.one"), 1_000L);
        AtomicLong elapsed = new AtomicLong();
        UsageLedger ledger = new UsageLedger(store, elapsed::get, 30_000L);

        ledger.ensure(Collections.singleton("app.one"));
        ledger.add("app.one", 500L);
        ledger.mergeObserved("20260809", Collections.singletonMap("app.one", 1_200L));
        ledger.flush(true);

        assertEquals(Long.valueOf(1_500L), store.lastSaved.get("app.one"));
    }

    @Test
    public void dayRolloverFlushesOldDayAndLoadsNewDay() {
        FakeStore store = new FakeStore();
        AtomicLong elapsed = new AtomicLong();
        UsageLedger ledger = new UsageLedger(store, elapsed::get, 30_000L);

        ledger.ensure(Collections.singleton("app.one"));
        ledger.add("app.one", 500L);
        store.day = "20260810";
        store.values.put(key(store.day, "app.one"), 200L);
        ledger.ensure(Collections.singleton("app.one"));

        assertEquals("20260809", store.lastSavedDay);
        assertEquals(Long.valueOf(500L), store.lastSaved.get("app.one"));
        assertEquals(200L, ledger.usageMs("app.one"));
    }

    @Test
    public void rejectsImplausiblePollDeltaAndUsesPerAppLimit() {
        FakeStore store = new FakeStore();
        store.limitMinutes = 1;
        AtomicLong elapsed = new AtomicLong();
        UsageLedger ledger = new UsageLedger(store, elapsed::get, 30_000L);

        ledger.ensure(Collections.singleton("app.one"));
        ledger.add("app.one", 10_001L);
        assertFalse(ledger.isOverLimit("app.one"));
        ledger.mergeObserved("20260809", Collections.singletonMap("app.one", 60_000L));
        assertTrue(ledger.isOverLimit("app.one"));
    }

    private static String key(String day, String packageName) {
        return day + ":" + packageName;
    }

    private static final class FakeStore implements UsageLedger.Store {
        String day = "20260809";
        int limitMinutes = 15;
        String lastSavedDay = "";
        final Map<String, Long> values = new HashMap<>();
        final Map<String, Long> lastSaved = new HashMap<>();

        @Override
        public String currentDay() {
            return day;
        }

        @Override
        public long read(String requestedDay, String packageName) {
            Long value = values.get(key(requestedDay, packageName));
            return value == null ? 0L : value;
        }

        @Override
        public void save(String requestedDay, Map<String, Long> usageByPackage) {
            lastSavedDay = requestedDay;
            lastSaved.clear();
            lastSaved.putAll(usageByPackage);
            for (Map.Entry<String, Long> entry : usageByPackage.entrySet()) {
                values.put(key(requestedDay, entry.getKey()), entry.getValue());
            }
        }

        @Override
        public int limitMinutes(String packageName) {
            return limitMinutes;
        }
    }
}
