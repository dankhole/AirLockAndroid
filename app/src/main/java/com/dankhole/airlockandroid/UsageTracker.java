package com.dankhole.airlockandroid;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;

import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

final class UsageTracker {
    private UsageTracker() {
    }

    static Map<String, Long> queryTodayFromSystemStats(
            Context context,
            Set<String> trackedPackages
    ) {
        if (trackedPackages.isEmpty() || !AndroidPermissions.hasUsageAccess(context)) {
            return Collections.emptyMap();
        }
        UsageStatsManager usageStatsManager =
                (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usageStatsManager == null) {
            return Collections.emptyMap();
        }

        long now = System.currentTimeMillis();
        Map<String, UsageStats> stats;
        try {
            stats = usageStatsManager.queryAndAggregateUsageStats(startOfTodayMs(now), now);
        } catch (SecurityException ignored) {
            return Collections.emptyMap();
        }
        if (stats == null || stats.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Long> observedUsageByPackage = new HashMap<>();
        for (String packageName : trackedPackages) {
            UsageStats stat = stats.get(packageName);
            if (stat != null) {
                observedUsageByPackage.put(packageName, stat.getTotalTimeInForeground());
            }
        }
        return observedUsageByPackage;
    }

    private static long startOfTodayMs(long now) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(now);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }
}
