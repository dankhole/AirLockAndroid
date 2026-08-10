package com.dankhole.airlockandroid;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

import java.nio.charset.StandardCharsets;
import java.util.List;

final class MonitoringHealth {
    private static final String KEY_LAST_SERVICE_START_MS = "health_last_service_start_ms";
    private static final String KEY_LAST_HEARTBEAT_MS = "health_last_heartbeat_ms";
    private static final String KEY_LAST_HEALTHY_POLL_MS = "health_last_healthy_poll_ms";
    private static final String KEY_LAST_ISSUE = "health_last_issue";
    private static final String KEY_LAST_ISSUE_MS = "health_last_issue_ms";
    private static final String KEY_LAST_EXIT_SEEN_MS = "health_last_exit_seen_ms";
    private static final String KEY_LAST_RECOVERY = "health_last_recovery";
    private static final String KEY_LAST_RECOVERY_MS = "health_last_recovery_ms";
    private static final long HEARTBEAT_PERSIST_INTERVAL_MS = 60_000L;
    private static final long RECOVERY_DISPLAY_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L;

    private static volatile boolean serviceRunning;
    private static volatile String runtimeIssue = "";
    private static long lastHeartbeatPersistElapsedMs;

    private MonitoringHealth() {
    }

    static synchronized long onServiceCreated(Context context) {
        serviceRunning = true;
        runtimeIssue = context.getString(R.string.monitoring_issue_starting);
        long now = System.currentTimeMillis();
        SharedPreferences preferences = Preferences.prefs(context);
        long previousServiceStartMs = preferences.getLong(KEY_LAST_SERVICE_START_MS, 0L);
        preferences.edit()
                .putLong(KEY_LAST_SERVICE_START_MS, now)
                .putLong(KEY_LAST_HEARTBEAT_MS, now)
                .putString(KEY_LAST_ISSUE, runtimeIssue)
                .putLong(KEY_LAST_ISSUE_MS, now)
                .apply();
        lastHeartbeatPersistElapsedMs = SystemClock.elapsedRealtime();
        updateProcessStateSummary(context, "starting");
        return previousServiceStartMs;
    }

    static synchronized void onServiceDestroyed(Context context, boolean intentionallyStopped) {
        serviceRunning = false;
        if (intentionallyStopped) {
            runtimeIssue = "";
            Preferences.prefs(context).edit()
                    .remove(KEY_LAST_ISSUE)
                    .remove(KEY_LAST_ISSUE_MS)
                    .apply();
            updateProcessStateSummary(context, "stopped_by_user");
        }
    }

    static synchronized void recordHealthyPoll(Context context) {
        boolean issueChanged = !runtimeIssue.isEmpty();
        runtimeIssue = "";
        persistHeartbeat(context, true, issueChanged);
        if (issueChanged) {
            updateProcessStateSummary(context, "healthy");
        }
    }

    static synchronized void recordWaiting(Context context, String issue) {
        String safeIssue = issue == null
                ? context.getString(R.string.monitoring_issue_waiting)
                : issue;
        boolean issueChanged = !safeIssue.equals(runtimeIssue);
        runtimeIssue = safeIssue;
        persistHeartbeat(context, false, issueChanged);
        if (issueChanged) {
            updateProcessStateSummary(context, "waiting");
        }
    }

    static synchronized void recordHeartbeat(Context context) {
        persistHeartbeat(context, false, false);
    }

    static synchronized void recordStartFailure(Context context, String issue) {
        serviceRunning = false;
        runtimeIssue = issue == null
                ? context.getString(R.string.monitoring_start_failed_default)
                : issue;
        long now = System.currentTimeMillis();
        Preferences.prefs(context).edit()
                .putString(KEY_LAST_ISSUE, runtimeIssue)
                .putLong(KEY_LAST_ISSUE_MS, now)
                .apply();
    }

    static boolean isServiceRunning() {
        return serviceRunning;
    }

    static String currentIssue(Context context) {
        if (serviceRunning) {
            return runtimeIssue;
        }
        return Preferences.prefs(context).getString(KEY_LAST_ISSUE, "");
    }

    static String recentRecovery(Context context) {
        SharedPreferences preferences = Preferences.prefs(context);
        long recoveryMs = preferences.getLong(KEY_LAST_RECOVERY_MS, 0L);
        if (recoveryMs <= 0L
                || System.currentTimeMillis() - recoveryMs > RECOVERY_DISPLAY_WINDOW_MS) {
            return "";
        }
        return preferences.getString(KEY_LAST_RECOVERY, "");
    }

    static long lastHealthyPollMs(Context context) {
        return Preferences.prefs(context).getLong(KEY_LAST_HEALTHY_POLL_MS, 0L);
    }

    private static void persistHeartbeat(
            Context context,
            boolean healthy,
            boolean force
    ) {
        long elapsedNow = SystemClock.elapsedRealtime();
        if (!force
                && elapsedNow - lastHeartbeatPersistElapsedMs < HEARTBEAT_PERSIST_INTERVAL_MS) {
            return;
        }

        long now = System.currentTimeMillis();
        SharedPreferences.Editor editor = Preferences.prefs(context).edit()
                .putLong(KEY_LAST_HEARTBEAT_MS, now);
        if (healthy) {
            editor.putLong(KEY_LAST_HEALTHY_POLL_MS, now)
                    .remove(KEY_LAST_ISSUE)
                    .remove(KEY_LAST_ISSUE_MS);
        } else if (!runtimeIssue.isEmpty()) {
            editor.putString(KEY_LAST_ISSUE, runtimeIssue)
                    .putLong(KEY_LAST_ISSUE_MS, now);
        }
        editor.apply();
        lastHeartbeatPersistElapsedMs = elapsedNow;
    }

    static void capturePreviousExit(Context context, long previousServiceStartMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                || !Preferences.isMonitoringRequested(context)) {
            return;
        }

        ActivityManager activityManager = context.getSystemService(ActivityManager.class);
        if (activityManager == null) {
            return;
        }

        List<ApplicationExitInfo> exitReasons;
        try {
            exitReasons = activityManager.getHistoricalProcessExitReasons(
                    context.getPackageName(),
                    0,
                    5
            );
        } catch (RuntimeException ignored) {
            return;
        }
        if (exitReasons == null || exitReasons.isEmpty()) {
            return;
        }

        SharedPreferences preferences = Preferences.prefs(context);
        long lastSeenMs = preferences.getLong(KEY_LAST_EXIT_SEEN_MS, 0L);
        ApplicationExitInfo latest = exitReasons.get(0);
        if (latest.getTimestamp() <= lastSeenMs) {
            return;
        }

        SharedPreferences.Editor editor = preferences.edit()
                .putLong(KEY_LAST_EXIT_SEEN_MS, latest.getTimestamp());
        boolean wasForegroundService = latest.getImportance()
                == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
        boolean wasMonitoring = processStateWasMonitoring(
                wasForegroundService,
                latest.getProcessStateSummary()
        );
        String recovery = shouldRecordRecovery(
                previousServiceStartMs,
                latest.getTimestamp(),
                wasMonitoring
        )
                ? recoveryMessage(context, latest.getReason())
                : "";
        if (!recovery.isEmpty()) {
            editor.putString(KEY_LAST_RECOVERY, recovery)
                    .putLong(KEY_LAST_RECOVERY_MS, System.currentTimeMillis());
        }
        editor.apply();
    }

    static boolean shouldRecordRecovery(
            long previousServiceStartMs,
            long exitTimestampMs,
            boolean wasMonitoring
    ) {
        return previousServiceStartMs > 0L
                && exitTimestampMs >= previousServiceStartMs
                && wasMonitoring;
    }

    static boolean processStateWasMonitoring(
            boolean wasForegroundService,
            byte[] processSummary
    ) {
        if (processSummary == null) {
            return wasForegroundService;
        }
        String summary = new String(processSummary, StandardCharsets.UTF_8);
        return "airlock_monitoring=starting".equals(summary)
                || "airlock_monitoring=healthy".equals(summary)
                || "airlock_monitoring=waiting".equals(summary);
    }

    private static String recoveryMessage(Context context, int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_LOW_MEMORY:
                return context.getString(R.string.recovery_low_memory);
            case ApplicationExitInfo.REASON_CRASH:
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
            case ApplicationExitInfo.REASON_ANR:
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                return context.getString(R.string.recovery_unexpected_exit);
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                return context.getString(R.string.recovery_resource_limit);
            case ApplicationExitInfo.REASON_USER_REQUESTED:
                return context.getString(R.string.recovery_user_stop);
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE:
                return context.getString(R.string.recovery_permission_change);
            case ApplicationExitInfo.REASON_PACKAGE_UPDATED:
            case ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE:
                return context.getString(R.string.recovery_app_update);
            default:
                return "";
        }
    }

    private static void updateProcessStateSummary(Context context, String state) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        ActivityManager activityManager = context.getSystemService(ActivityManager.class);
        if (activityManager == null) {
            return;
        }
        try {
            activityManager.setProcessStateSummary(
                    ("airlock_monitoring=" + state).getBytes(StandardCharsets.UTF_8)
            );
        } catch (RuntimeException ignored) {
            // Diagnostics must never interfere with monitoring.
        }
    }
}
