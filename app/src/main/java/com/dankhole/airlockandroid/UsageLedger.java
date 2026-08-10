package com.dankhole.airlockandroid;

import android.content.Context;
import android.os.SystemClock;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

final class UsageLedger {
    interface Store {
        String currentDay();

        long read(String day, String packageName);

        void save(String day, Map<String, Long> usageByPackage);

        int limitMinutes(String packageName);
    }

    private static final long MAX_POLL_DELTA_MS = 10_000L;

    private final Store store;
    private final LongSupplier elapsedRealtime;
    private final long persistIntervalMs;
    private final Map<String, Long> totalsMs = new HashMap<>();
    private final Set<String> dirtyPackages = new HashSet<>();

    private String cachedDay;
    private long lastPersistElapsedMs;

    UsageLedger(Store store, LongSupplier elapsedRealtime, long persistIntervalMs) {
        this.store = store;
        this.elapsedRealtime = elapsedRealtime;
        this.persistIntervalMs = Math.max(0L, persistIntervalMs);
        lastPersistElapsedMs = elapsedRealtime.getAsLong();
    }

    static UsageLedger forContext(Context context, long persistIntervalMs) {
        Context appContext = context.getApplicationContext();
        return new UsageLedger(new Store() {
            @Override
            public String currentDay() {
                return Preferences.currentUsageDay();
            }

            @Override
            public long read(String day, String packageName) {
                return Preferences.getUsageForDayMs(appContext, day, packageName);
            }

            @Override
            public void save(String day, Map<String, Long> usageByPackage) {
                Preferences.saveUsageForDayMs(appContext, day, usageByPackage);
            }

            @Override
            public int limitMinutes(String packageName) {
                return Preferences.dailyLimitMinutes(appContext, packageName);
            }
        }, SystemClock::elapsedRealtime, persistIntervalMs);
    }

    void ensure(Set<String> selectedPackages) {
        String currentDay = store.currentDay();
        if (cachedDay == null) {
            cachedDay = currentDay;
        } else if (!cachedDay.equals(currentDay)) {
            flush(true);
            cachedDay = currentDay;
            totalsMs.clear();
            dirtyPackages.clear();
            lastPersistElapsedMs = elapsedRealtime.getAsLong();
        }

        for (String packageName : selectedPackages) {
            if (!totalsMs.containsKey(packageName)) {
                totalsMs.put(packageName, store.read(cachedDay, packageName));
            }
        }
    }

    void add(String packageName, long deltaMs) {
        if (deltaMs <= 0L || deltaMs > MAX_POLL_DELTA_MS) {
            return;
        }
        long current = totalsMs.containsKey(packageName)
                ? totalsMs.get(packageName)
                : store.read(cachedDay, packageName);
        totalsMs.put(packageName, current + deltaMs);
        dirtyPackages.add(packageName);
    }

    void mergeObserved(String observedDay, Map<String, Long> observedUsage) {
        if (cachedDay == null || !cachedDay.equals(observedDay)) {
            return;
        }
        for (Map.Entry<String, Long> entry : observedUsage.entrySet()) {
            String packageName = entry.getKey();
            long observedMs = entry.getValue() == null ? 0L : entry.getValue();
            long currentMs = totalsMs.containsKey(packageName)
                    ? totalsMs.get(packageName)
                    : 0L;
            if (observedMs >= currentMs) {
                totalsMs.put(packageName, observedMs);
                dirtyPackages.remove(packageName);
            }
        }
    }

    long usageMs(String packageName) {
        Long cached = totalsMs.get(packageName);
        return cached == null
                ? store.read(store.currentDay(), packageName)
                : cached;
    }

    boolean isOverLimit(String packageName) {
        return usageMs(packageName) >= store.limitMinutes(packageName) * 60_000L;
    }

    void flush(boolean force) {
        if (cachedDay == null || dirtyPackages.isEmpty()) {
            return;
        }
        long now = elapsedRealtime.getAsLong();
        if (!force && now - lastPersistElapsedMs < persistIntervalMs) {
            return;
        }

        Map<String, Long> dirtyTotals = new HashMap<>();
        for (String packageName : dirtyPackages) {
            Long usageMs = totalsMs.get(packageName);
            if (usageMs != null) {
                dirtyTotals.put(packageName, usageMs);
            }
        }
        store.save(cachedDay, dirtyTotals);
        dirtyPackages.clear();
        lastPersistElapsedMs = now;
    }
}
