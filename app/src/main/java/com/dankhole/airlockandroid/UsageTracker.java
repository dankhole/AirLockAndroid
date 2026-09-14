package com.dankhole.airlockandroid;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

final class UsageTracker {
    private UsageTracker() {
    }

    static Snapshot queryTodayFromSystemStats(
            Context context,
            Set<String> trackedPackages
    ) {
        Snapshot snapshot = new Snapshot(
                System.currentTimeMillis(),
                SystemClock.elapsedRealtime(),
                TimeZone.getDefault()
        );
        if (trackedPackages.isEmpty() || !AndroidPermissions.hasUsageAccess(context)) {
            return snapshot;
        }
        UsageStatsManager usageStatsManager =
                (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usageStatsManager == null) {
            return snapshot;
        }

        List<UsageStats> stats;
        try {
            stats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    snapshot.startMs,
                    snapshot.endMs
            );
        } catch (SecurityException ignored) {
            return snapshot;
        }
        if (stats == null || stats.isEmpty()) {
            return snapshot;
        }

        for (UsageStats stat : stats) {
            if (stat != null && trackedPackages.contains(stat.getPackageName())) {
                snapshot.addBucket(
                        stat.getPackageName(),
                        stat.getFirstTimeStamp(),
                        stat.getLastTimeStamp(),
                        stat.getTotalTimeInForeground()
                );
            }
        }
        return snapshot;
    }

    static final class Snapshot {
        final String day;
        final long startMs;
        final long endMs;
        final long endElapsedMs;
        final Map<String, Long> usageByPackage;
        private final Map<String, Long> totals = new HashMap<>();

        Snapshot(long now, TimeZone timeZone) {
            this(now, 0L, timeZone);
        }

        Snapshot(long now, long elapsedNow, TimeZone timeZone) {
            Calendar calendar = Calendar.getInstance(timeZone);
            calendar.setTimeInMillis(now);
            SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMdd", Locale.US);
            formatter.setTimeZone(timeZone);
            day = formatter.format(calendar.getTime());
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            startMs = calendar.getTimeInMillis();
            endMs = now;
            endElapsedMs = elapsedNow;
            usageByPackage = Collections.unmodifiableMap(totals);
        }

        boolean matchesCurrentDay() {
            return matchesDay(System.currentTimeMillis(), TimeZone.getDefault());
        }

        boolean matchesDay(long now, TimeZone timeZone) {
            Snapshot current = new Snapshot(now, timeZone);
            return startMs == current.startMs && day.equals(current.day) && now >= endMs;
        }

        void addBucket(String packageName, long firstMs, long lastMs, long foregroundMs) {
            // Android can expand even a daily query to whole interval boundaries.
            // A straddling bucket cannot be safely prorated into today's usage.
            if (packageName == null || packageName.isEmpty()
                    || firstMs < startMs || lastMs > endMs || lastMs < firstMs
                    || foregroundMs <= 0L || foregroundMs > lastMs - firstMs) {
                return;
            }
            Long previous = totals.get(packageName);
            long current = previous == null ? 0L : previous;
            if (foregroundMs > endMs - startMs - current) {
                return;
            }
            totals.put(packageName, current + foregroundMs);
        }
    }
}
