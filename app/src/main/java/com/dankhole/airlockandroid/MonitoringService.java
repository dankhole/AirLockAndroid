package com.dankhole.airlockandroid;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageEventsQuery;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class MonitoringService extends Service {
    public static final String ACTION_START = "com.dankhole.airlock.START";
    public static final String ACTION_STOP = "com.dankhole.airlock.STOP";
    static final String ACTION_DEBUG_FORCE_FOREGROUND_SANITY =
            "com.dankhole.airlock.DEBUG_FORCE_FOREGROUND_SANITY";
    static final String EXTRA_DEBUG_SANITY_TOKEN = "debug_sanity_token";

    static final String CHANNEL_ID = "airlock_monitoring_silent_v2";
    private static final String TAG = "AirLockMonitor";
    private static final int NOTIFICATION_ID = 42;
    private static final long POLL_INTERVAL_MS = 1000L;
    private static final long RECOVERY_FAST_POLL_INTERVAL_MS = 200L;
    private static final long RECOVERY_WARM_POLL_INTERVAL_MS = 500L;
    private static final long RECOVERY_FAST_WINDOW_MS = 3_000L;
    private static final long RECOVERY_WARM_WINDOW_MS = 15_000L;
    private static final long USAGE_EVENT_OVERLAP_MS = 10_000L;
    private static final long UNKNOWN_FOREGROUND_LOOKBACK_MS = 5 * 60_000L;
    private static final long FOREGROUND_SANITY_CHECK_INTERVAL_MS = 30_000L;
    private static final long FOREGROUND_QUERY_TIMEOUT_MS = 10_000L;
    private static final int MAX_FOREGROUND_QUERY_WORKERS = 2;
    private static final long FOREGROUND_QUERY_WORKER_IDLE_MS = 30_000L;
    private static final long DEGRADED_RETRY_INTERVAL_MS = 30_000L;
    private static final long BACKGROUND_RESTRICTION_CHECK_INTERVAL_MS = 30_000L;
    private static final long SCREEN_OFF_CHECK_INTERVAL_MS = 60_000L;
    private static final long USAGE_RECONCILE_INTERVAL_MS = 60_000L;
    private static final long USAGE_PERSIST_INTERVAL_MS = 30_000L;
    private static final long EMERGENCY_PAUSE_CHECK_INTERVAL_MS = 60_000L;
    private static final long CELEBRATION_MAX_MS = 4_000L;
    private static final long OVERLAY_STICKY_MS = 5 * 60 * 1000L;
    private static final AtomicInteger FOREGROUND_THREAD_SEQUENCE = new AtomicInteger();
    private static final BoundedTaskExecutor FOREGROUND_QUERY_EXECUTOR =
            new BoundedTaskExecutor(
                    MAX_FOREGROUND_QUERY_WORKERS,
                    FOREGROUND_QUERY_WORKER_IDLE_MS,
                    MonitoringService::newForegroundQueryThread
            );

    private final Handler handler = new Handler(Looper.getMainLooper());
    private HandlerThread reconciliationThread;
    private Handler reconciliationHandler;
    private WindowManager windowManager;
    private PowerManager powerManager;
    private KeyguardManager keyguardManager;
    private BlockerOverlayController blockerOverlayController;
    private BroadcastReceiver deviceStateReceiver;
    private View overlayView;
    private String overlayPackageName;
    private String stickyBlockedPackageName;
    private String homePackageName;
    private Set<String> homePackageNames;
    private String lastForegroundPackage;
    private boolean foregroundStateKnown;
    private long foregroundCandidateEventMs = Long.MIN_VALUE;
    private long latestForegroundEventMs = Long.MIN_VALUE;
    private final Map<String, Long> latestBackgroundEventMs = new HashMap<>();
    private String lastCriticalBlockValidationPackage;
    private long lastTickElapsedMs;
    private long lastUsageQueryEndMs;
    private long lastForegroundSanityCheckMs;
    private long lastLifecycleEventMs;
    private final Set<String> lastLifecycleEventKeys = new HashSet<>();
    private long keepOverlayUntilMs;
    private long leaveAppGraceUntilElapsedMs;
    private long transientRecoveryStartedElapsedMs;
    private boolean unlockCelebrationRunning;
    private boolean emergencyCelebrationRunning;
    private long celebrationDeadlineElapsedMs;
    private boolean overlayNeedsRefresh;
    private boolean overlayWindowObscured;
    private boolean foregroundQueryInFlight;
    private long foregroundQueryId;
    private Runnable foregroundQueryTimeoutRunnable;
    private int consecutiveForegroundQueryFailures;
    private int consecutiveForegroundQueryTimeouts;
    private boolean reconciliationInFlight;
    private boolean emergencyPauseNotificationShown;
    private boolean stopping;
    private boolean intentionallyStopped;
    private boolean deviceInteractive;
    private boolean deviceUnlocked;
    private boolean foregroundStatusRecoveryPending;
    private boolean backgroundRestricted;
    private boolean debugForegroundSanityPending;
    private String debugForegroundSanityToken;
    private long nextForegroundPromotionAttemptElapsedMs;
    private long lastBackgroundRestrictionCheckElapsedMs;
    private String monitoringIssue = "";
    private int consecutiveOverlayFailures;
    private long nextOverlayAttemptElapsedMs;
    private Runnable overlayRemovalRetryRunnable;
    private int consecutiveOverlayRemovalFailures;
    private UsageLedger usageLedger;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                requestForegroundPoll();
            } catch (RuntimeException exception) {
                handleUnexpectedForegroundLoopFailure(exception);
            }
        }
    };

    private final Runnable reconciliationRunnable = new Runnable() {
        @Override
        public void run() {
            requestUsageReconciliation();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        usageLedger = UsageLedger.forContext(this, USAGE_PERSIST_INTERVAL_MS);
        monitoringIssue = getString(R.string.monitoring_issue_starting);
        startForeground(NOTIFICATION_ID, buildNotification());

        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        blockerOverlayController = new BlockerOverlayController(
                this,
                createBlockerOverlayListener()
        );
        deviceInteractive = powerManager == null || powerManager.isInteractive();
        deviceUnlocked = keyguardManager == null || !keyguardManager.isKeyguardLocked();
        backgroundRestricted = AndroidPermissions.isBackgroundRestricted(this);
        lastBackgroundRestrictionCheckElapsedMs = SystemClock.elapsedRealtime();
        foregroundStatusRecoveryPending = backgroundRestricted;
        long previousServiceStartMs = MonitoringHealth.onServiceCreated(this);
        if (foregroundStatusRecoveryPending) {
            monitoringIssue = getString(R.string.monitoring_issue_background_restricted);
            MonitoringHealth.recordWaiting(this, monitoringIssue);
        }
        reconciliationThread = new HandlerThread(
                "AirLockReconciliation",
                Process.THREAD_PRIORITY_BACKGROUND
        );
        reconciliationThread.start();
        reconciliationHandler = new Handler(reconciliationThread.getLooper());
        reconciliationHandler.post(() -> MonitoringHealth.capturePreviousExit(
                this,
                previousServiceStartMs
        ));
        registerDeviceStateReceiver();
        emergencyPauseNotificationShown = Preferences.isEmergencyPauseActive(this);
        lastTickElapsedMs = SystemClock.elapsedRealtime();
        lastUsageQueryEndMs = System.currentTimeMillis() - USAGE_EVENT_OVERLAP_MS;
        handler.post(pollRunnable);
        handler.post(reconciliationRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (isDebuggable()
                && intent != null
                && ACTION_DEBUG_FORCE_FOREGROUND_SANITY.equals(intent.getAction())) {
            debugForegroundSanityPending = true;
            debugForegroundSanityToken = intent.getStringExtra(EXTRA_DEBUG_SANITY_TOKEN);
            lastForegroundSanityCheckMs = 0L;
            handler.removeCallbacks(pollRunnable);
            handler.post(pollRunnable);
            return START_STICKY;
        }
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            intentionallyStopped = true;
            stopMonitoring(true);
            return START_NOT_STICKY;
        }
        if (!Preferences.isMonitoringRequested(this)) {
            stopMonitoring(false);
            return START_NOT_STICKY;
        }
        boolean foregroundStatusNeedsAttention = refreshForegroundServiceHealth(true);
        if (Preferences.isEmergencyPauseActive(this)) {
            clearMonitoringIssue();
            updateForegroundNotification();
            return START_STICKY;
        }
        String requirementIssue = monitoringRequirementIssue();
        if (!requirementIssue.isEmpty()) {
            waitForRecovery(requirementIssue, 0L);
            return START_STICKY;
        }
        if (!foregroundStatusNeedsAttention) {
            clearMonitoringIssue();
        }
        handler.removeCallbacks(pollRunnable);
        handler.post(pollRunnable);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        handler.removeCallbacks(pollRunnable);
        handler.removeCallbacks(reconciliationRunnable);
        cancelForegroundQueryTimeout();
        cancelOverlayRemovalRetry();
        hideOverlay(false);
        flushUsageSafely(true, "final");
        unregisterDeviceStateReceiver();
        if (reconciliationHandler != null) {
            reconciliationHandler.removeCallbacksAndMessages(null);
        }
        if (reconciliationThread != null) {
            reconciliationThread.quitSafely();
        }
        MonitoringHealth.onServiceDestroyed(
                this,
                intentionallyStopped || !Preferences.isMonitoringRequested(this)
        );
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    static boolean requestStart(Context context) {
        Intent intent = new Intent(context, MonitoringService.class);
        intent.setAction(ACTION_START);
        try {
            context.startForegroundService(intent);
            return true;
        } catch (RuntimeException exception) {
            MonitoringHealth.recordStartFailure(
                    context,
                    context.getString(R.string.monitoring_issue_start_denied)
            );
            return false;
        }
    }

    private static Thread newForegroundQueryThread(Runnable task) {
        Thread thread = new Thread(() -> {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            } catch (RuntimeException ignored) {
                // The query is still kept off the main thread if priority adjustment fails.
            }
            task.run();
        }, "AirLockForeground-" + FOREGROUND_THREAD_SEQUENCE.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }

    private void armForegroundQueryTimeout(long queryId) {
        cancelForegroundQueryTimeout();
        foregroundQueryTimeoutRunnable = () -> {
            if (stopping || !foregroundQueryInFlight || queryId != foregroundQueryId) {
                return;
            }
            foregroundQueryInFlight = false;
            foregroundQueryId++;
            consecutiveForegroundQueryFailures++;
            consecutiveForegroundQueryTimeouts++;
            invalidateForegroundState();
            setMonitoringIssue(getString(R.string.monitoring_issue_query_timeout));
            debugLog("foreground query timed out; consecutive timeouts="
                    + consecutiveForegroundQueryTimeouts);
            foregroundQueryTimeoutRunnable = null;
            scheduleForegroundPoll(consecutiveForegroundQueryTimeouts < MAX_FOREGROUND_QUERY_WORKERS
                    ? POLL_INTERVAL_MS
                    : DEGRADED_RETRY_INTERVAL_MS);
        };
        handler.postDelayed(foregroundQueryTimeoutRunnable, FOREGROUND_QUERY_TIMEOUT_MS);
    }

    private void cancelForegroundQueryTimeout() {
        if (foregroundQueryTimeoutRunnable != null) {
            handler.removeCallbacks(foregroundQueryTimeoutRunnable);
            foregroundQueryTimeoutRunnable = null;
        }
    }

    private void handleForegroundQueryFailure() {
        consecutiveForegroundQueryFailures++;
        invalidateForegroundState();
        setMonitoringIssue(getString(R.string.monitoring_issue_usage_unavailable));
        if (consecutiveForegroundQueryFailures >= 3) {
            scheduleForegroundPoll(DEGRADED_RETRY_INTERVAL_MS);
        } else {
            scheduleForegroundPoll(POLL_INTERVAL_MS);
        }
    }

    private void handleUnexpectedForegroundLoopFailure(RuntimeException exception) {
        if (stopping) {
            return;
        }
        foregroundQueryInFlight = false;
        foregroundQueryId++;
        cancelForegroundQueryTimeout();
        consecutiveForegroundQueryFailures++;
        try {
            invalidateForegroundState();
        } catch (RuntimeException ignored) {
            // Keep the recovery loop alive even if Android window cleanup also failed.
        }
        try {
            setMonitoringIssue(getString(R.string.monitoring_issue_blocker_error));
        } catch (RuntimeException ignored) {
            // A later healthy poll can repair the persisted health state.
        }
        debugLog("foreground loop failed: " + exception.getClass().getSimpleName());
        scheduleForegroundPoll(consecutiveForegroundQueryFailures < 3
                ? POLL_INTERVAL_MS
                : DEGRADED_RETRY_INTERVAL_MS);
    }

    private String monitoringRequirementIssue() {
        if (!AndroidPermissions.hasUsageAccess(this)) {
            return getString(R.string.monitoring_requirement_usage);
        }
        if (!AndroidPermissions.hasOverlayAccess(this)) {
            return getString(R.string.monitoring_requirement_overlay);
        }
        if (!Preferences.hasAccountabilityNumber(this)) {
            return getString(R.string.monitoring_requirement_keyholder);
        }
        if (!Preferences.hasMasterPin(this)) {
            return getString(R.string.monitoring_requirement_pin);
        }
        if (!Preferences.isApprovalCalculatorReady(this)) {
            return getString(R.string.monitoring_requirement_pin_update);
        }
        if (!Preferences.hasLimitedApps(this)) {
            return getString(R.string.monitoring_requirement_apps);
        }
        return "";
    }

    private boolean refreshForegroundServiceHealth(boolean forcePromotion) {
        long elapsedNow = SystemClock.elapsedRealtime();
        if (forcePromotion
                || lastBackgroundRestrictionCheckElapsedMs == 0L
                || elapsedNow - lastBackgroundRestrictionCheckElapsedMs
                >= BACKGROUND_RESTRICTION_CHECK_INTERVAL_MS) {
            backgroundRestricted = AndroidPermissions.isBackgroundRestricted(this);
            lastBackgroundRestrictionCheckElapsedMs = elapsedNow;
        }
        if (backgroundRestricted) {
            foregroundStatusRecoveryPending = true;
            nextForegroundPromotionAttemptElapsedMs = 0L;
            setMonitoringIssue(getString(R.string.monitoring_issue_background_restricted));
            return true;
        }

        boolean shouldPromote = forcePromotion
                || (foregroundStatusRecoveryPending
                && elapsedNow >= nextForegroundPromotionAttemptElapsedMs);
        if (!shouldPromote) {
            return foregroundStatusRecoveryPending;
        }

        try {
            startForeground(NOTIFICATION_ID, buildNotification());
            if (foregroundStatusRecoveryPending) {
                debugLog("foreground service status restored");
            }
            foregroundStatusRecoveryPending = false;
            nextForegroundPromotionAttemptElapsedMs = 0L;
            return false;
        } catch (RuntimeException exception) {
            foregroundStatusRecoveryPending = true;
            nextForegroundPromotionAttemptElapsedMs = elapsedNow + DEGRADED_RETRY_INTERVAL_MS;
            setMonitoringIssue(getString(R.string.monitoring_issue_foreground_recovery));
            debugLog("foreground service promotion failed: "
                    + exception.getClass().getSimpleName());
            return true;
        }
    }

    private void waitForRecovery(String issue, long delayMs) {
        hideOverlay(true);
        flushUsageSafely(true, "recovery");
        resetForegroundEvidence(false, Long.MIN_VALUE);
        resetLifecycleWatermark();
        lastTickElapsedMs = SystemClock.elapsedRealtime();
        endTransientRecovery();
        setMonitoringIssue(issue);
        scheduleForegroundPoll(delayMs <= 0L ? DEGRADED_RETRY_INTERVAL_MS : delayMs);
    }

    private void setMonitoringIssue(String issue) {
        String safeIssue = issue == null
                ? getString(R.string.monitoring_issue_waiting)
                : issue;
        boolean changed = !safeIssue.equals(monitoringIssue);
        monitoringIssue = safeIssue;
        MonitoringHealth.recordWaiting(this, safeIssue);
        if (changed) {
            updateForegroundNotification();
        }
    }

    private void clearMonitoringIssue() {
        boolean changed = !monitoringIssue.isEmpty();
        monitoringIssue = "";
        MonitoringHealth.recordHealthyPoll(this);
        if (changed) {
            updateForegroundNotification();
        }
    }

    private boolean isDeviceReadyForMonitoring() {
        if (!deviceInteractive && (powerManager == null || powerManager.isInteractive())) {
            deviceInteractive = true;
            deviceUnlocked = keyguardManager == null || !keyguardManager.isKeyguardLocked();
        }
        if (!deviceUnlocked && (keyguardManager == null || !keyguardManager.isKeyguardLocked())) {
            deviceUnlocked = true;
        }
        return deviceInteractive && deviceUnlocked;
    }

    private void pauseWhileDeviceUnavailable() {
        hideOverlay(true);
        flushUsageSafely(true, "device pause");
        resetForegroundEvidence(false, Long.MIN_VALUE);
        resetLifecycleWatermark();
        lastTickElapsedMs = SystemClock.elapsedRealtime();
        endTransientRecovery();
        MonitoringHealth.recordHeartbeat(this);
        scheduleForegroundPoll(SCREEN_OFF_CHECK_INTERVAL_MS);
    }

    private void registerDeviceStateReceiver() {
        deviceStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    abandonForegroundQuery();
                    deviceInteractive = false;
                    deviceUnlocked = false;
                    pauseWhileDeviceUnavailable();
                    return;
                }
                if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    deviceInteractive = true;
                    deviceUnlocked = keyguardManager == null || !keyguardManager.isKeyguardLocked();
                } else if (Intent.ACTION_USER_PRESENT.equals(action)
                        || Intent.ACTION_USER_UNLOCKED.equals(action)) {
                    deviceInteractive = true;
                    deviceUnlocked = true;
                }
                if (Intent.ACTION_SCREEN_ON.equals(action)
                        || Intent.ACTION_USER_PRESENT.equals(action)
                        || Intent.ACTION_USER_UNLOCKED.equals(action)
                        || Intent.ACTION_TIME_CHANGED.equals(action)
                        || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
                    abandonForegroundQuery();
                    long now = System.currentTimeMillis();
                    lastUsageQueryEndMs = now - USAGE_EVENT_OVERLAP_MS;
                    lastForegroundSanityCheckMs = 0L;
                    resetLifecycleWatermark();
                    resetForegroundEvidence(false, Long.MIN_VALUE);
                    lastTickElapsedMs = SystemClock.elapsedRealtime();
                    handler.removeCallbacks(pollRunnable);
                    handler.post(pollRunnable);
                    handler.removeCallbacks(reconciliationRunnable);
                    handler.post(reconciliationRunnable);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(deviceStateReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(deviceStateReceiver, filter);
        }
    }

    private void unregisterDeviceStateReceiver() {
        if (deviceStateReceiver == null) {
            return;
        }
        try {
            unregisterReceiver(deviceStateReceiver);
        } catch (IllegalArgumentException ignored) {
            // The process may already have detached the receiver.
        }
        deviceStateReceiver = null;
    }

    private void abandonForegroundQuery() {
        if (!foregroundQueryInFlight) {
            return;
        }
        foregroundQueryInFlight = false;
        foregroundQueryId++;
        cancelForegroundQueryTimeout();
        debugLog("foreground query abandoned after device-state change");
    }

    private void requestForegroundPoll() {
        if (stopping || foregroundQueryInFlight) {
            return;
        }
        if (!Preferences.isMonitoringRequested(this)) {
            stopMonitoring(false);
            return;
        }
        if (emergencyCelebrationRunning) {
            long celebrationRemainingMs = celebrationDeadlineElapsedMs
                    - SystemClock.elapsedRealtime();
            if (celebrationRemainingMs <= 0L) {
                debugLog("emergency celebration watchdog removed overlay");
                hideOverlay(false);
                pauseForEmergencyDayPassIfActive();
            } else {
                scheduleForegroundPoll(Math.min(POLL_INTERVAL_MS, celebrationRemainingMs));
            }
            return;
        }
        if (!unlockCelebrationRunning && pauseForEmergencyDayPassIfActive()) {
            return;
        }
        String requirementIssue = monitoringRequirementIssue();
        if (!requirementIssue.isEmpty()) {
            waitForRecovery(requirementIssue, DEGRADED_RETRY_INTERVAL_MS);
            return;
        }
        refreshForegroundServiceHealth(false);
        if (!isDeviceReadyForMonitoring()) {
            pauseWhileDeviceUnavailable();
            return;
        }
        long now = System.currentTimeMillis();
        long elapsedNow = SystemClock.elapsedRealtime();
        long previousQueryEndMs = lastUsageQueryEndMs;
        boolean queryClockDiscontinuity = previousQueryEndMs > now
                || (previousQueryEndMs > 0L
                && now - previousQueryEndMs > UNKNOWN_FOREGROUND_LOOKBACK_MS);
        if (queryClockDiscontinuity) {
            hideOverlay(true, true);
            resetForegroundEvidence(false, Long.MIN_VALUE);
            resetLifecycleWatermark();
            lastForegroundSanityCheckMs = 0L;
            debugLog("foreground evidence reset after clock/query gap");
        }
        if (previousQueryEndMs <= 0L || queryClockDiscontinuity) {
            previousQueryEndMs = now - USAGE_EVENT_OVERLAP_MS;
        }
        final long queryPreviousEndMs = previousQueryEndMs;
        boolean runSanityCheck = lastForegroundSanityCheckMs == 0L
                || now - lastForegroundSanityCheckMs >= FOREGROUND_SANITY_CHECK_INTERVAL_MS;
        boolean debugSanityCheck = isDebuggable()
                && debugForegroundSanityPending
                && runSanityCheck;
        String debugSanityToken = debugSanityCheck
                ? debugForegroundSanityToken
                : null;
        String previousForegroundPackage = lastForegroundPackage;
        boolean previousForegroundStateKnown = foregroundStateKnown;
        long previousForegroundCandidateEventMs = foregroundCandidateEventMs;
        long previousLatestForegroundEventMs = latestForegroundEventMs;
        Map<String, Long> previousLatestBackgroundEventMs =
                new HashMap<>(latestBackgroundEventMs);
        String blockedPackage = overlayPackageName != null
                ? overlayPackageName
                : stickyBlockedPackageName;
        Set<String> selectedPackages = Preferences.selectedPackages(this);
        Set<String> transientPackages = transientSystemPackages();
        long previousLifecycleEventMs = lastLifecycleEventMs;
        Set<String> previousLifecycleEventKeys = new HashSet<>(lastLifecycleEventKeys);
        long queryId = ++foregroundQueryId;

        foregroundQueryInFlight = true;
        armForegroundQueryTimeout(queryId);
        boolean posted = FOREGROUND_QUERY_EXECUTOR.tryExecute(() -> {
            ForegroundQueryResult result;
            try {
                result = queryForegroundPackage(
                        now,
                        queryPreviousEndMs,
                        previousForegroundPackage,
                        previousForegroundStateKnown,
                        previousForegroundCandidateEventMs,
                        previousLatestForegroundEventMs,
                        previousLatestBackgroundEventMs,
                        blockedPackage,
                        transientPackages,
                        previousLifecycleEventMs,
                        previousLifecycleEventKeys,
                        runSanityCheck
                );
            } catch (RuntimeException exception) {
                debugLog("foreground query failed: " + exception.getClass().getSimpleName());
                result = ForegroundQueryResult.failed(
                        previousForegroundPackage,
                        previousForegroundStateKnown,
                        previousForegroundCandidateEventMs,
                        previousLatestForegroundEventMs,
                        previousLatestBackgroundEventMs,
                        previousLifecycleEventMs,
                        previousLifecycleEventKeys
                );
            }
            ForegroundQueryResult completedResult = result;
            handler.post(() -> {
                try {
                    completeForegroundPoll(
                            queryId,
                            elapsedNow,
                            previousForegroundPackage,
                            previousForegroundStateKnown,
                            selectedPackages,
                            completedResult,
                            debugSanityToken
                    );
                } catch (RuntimeException exception) {
                    handleUnexpectedForegroundLoopFailure(exception);
                }
            });
        });
        if (!posted) {
            foregroundQueryInFlight = false;
            cancelForegroundQueryTimeout();
            consecutiveForegroundQueryFailures++;
            invalidateForegroundState();
            setMonitoringIssue(getString(R.string.monitoring_issue_stalled));
            scheduleForegroundPoll(DEGRADED_RETRY_INTERVAL_MS);
            return;
        }
        lastUsageQueryEndMs = now;
        if (runSanityCheck) {
            lastForegroundSanityCheckMs = now;
        }
        if (debugSanityCheck) {
            debugForegroundSanityPending = false;
            debugForegroundSanityToken = null;
        }
    }

    private void completeForegroundPoll(
            long queryId,
            long queryStartedElapsedMs,
            String previousForegroundPackage,
            boolean previousForegroundStateKnown,
            Set<String> selectedPackages,
            ForegroundQueryResult queryResult,
            String debugSanityToken
    ) {
        if (queryId != foregroundQueryId) {
            return;
        }
        foregroundQueryInFlight = false;
        cancelForegroundQueryTimeout();
        consecutiveForegroundQueryTimeouts = 0;
        if (stopping) {
            return;
        }
        if (!unlockCelebrationRunning && pauseForEmergencyDayPassIfActive()) {
            return;
        }
        if (!isDeviceReadyForMonitoring()) {
            pauseWhileDeviceUnavailable();
            return;
        }
        if (!queryResult.successful) {
            handleForegroundQueryFailure();
            return;
        }

        consecutiveForegroundQueryFailures = 0;
        lastLifecycleEventMs = queryResult.latestLifecycleEventMs;
        lastLifecycleEventKeys.clear();
        lastLifecycleEventKeys.addAll(queryResult.latestLifecycleEventKeys);
        foregroundCandidateEventMs = queryResult.foregroundCandidateEventMs;
        latestForegroundEventMs = queryResult.latestForegroundEventMs;
        latestBackgroundEventMs.clear();
        latestBackgroundEventMs.putAll(queryResult.latestBackgroundEventMs);
        long now = System.currentTimeMillis();
        long elapsedNow = SystemClock.elapsedRealtime();
        String foregroundPackage = queryResult.packageName;
        boolean foregroundIsTransient = isTransientSystemSurface(foregroundPackage);
        boolean previousForegroundWasTransient =
                isTransientSystemSurface(previousForegroundPackage);
        if (foregroundIsTransient
                && (!previousForegroundWasTransient
                || (!previousForegroundStateKnown && queryResult.candidateKnown))) {
            beginTransientRecovery();
        } else if (!foregroundIsTransient) {
            endTransientRecovery();
        }
        if (queryResult.overlayInterrupted) {
            overlayNeedsRefresh = true;
            lastCriticalBlockValidationPackage = null;
        }
        if (previousForegroundStateKnown != queryResult.candidateKnown
                || !ForegroundEventPolicy.samePackage(
                        previousForegroundPackage,
                        foregroundPackage
                )) {
            debugLog("foreground " + previousForegroundPackage
                    + " (known=" + previousForegroundStateKnown + ") -> "
                    + foregroundPackage + " (known=" + queryResult.candidateKnown + ")"
                    + ", overlay=" + overlayPackageName
                    + ", sticky=" + stickyBlockedPackageName);
        }

        boolean pollHealthy = true;
        try {
            usageLedger.ensure(selectedPackages);

            if (foregroundPackage != null
                    && selectedPackages.contains(foregroundPackage)
                    && foregroundPackage.equals(lastForegroundPackage)) {
                usageLedger.add(foregroundPackage, elapsedNow - lastTickElapsedMs);
            }

            lastForegroundPackage = foregroundPackage;
            foregroundStateKnown = queryResult.candidateKnown;
            lastTickElapsedMs = elapsedNow;

            if (unlockCelebrationRunning) {
                ForegroundEventPolicy.CandidateState currentForeground =
                        queryResult.candidateKnown
                                ? ForegroundEventPolicy.knownCandidate(foregroundPackage)
                                : ForegroundEventPolicy.unknownCandidate();
                if (!ForegroundEventPolicy.shouldKeepCelebration(
                        overlayPackageName,
                        currentForeground,
                        elapsedNow,
                        celebrationDeadlineElapsedMs
                )) {
                    if (elapsedNow >= celebrationDeadlineElapsedMs) {
                        debugLog("celebration watchdog removed overlay for "
                                + overlayPackageName);
                    }
                    hideOverlay(false);
                }
                return;
            }

            boolean foregroundSelected = foregroundPackage != null
                    && selectedPackages.contains(foregroundPackage);
            if (!foregroundSelected) {
                lastCriticalBlockValidationPackage = null;
                String stickyPackage = stickyBlockedPackageForTransient(
                        now,
                        foregroundPackage,
                        selectedPackages
                );
                if (stickyPackage != null) {
                    beginTransientRecovery();
                    overlayNeedsRefresh = true;
                    hideOverlay(true, true);
                    return;
                }
                resetOverlayFailures();
                hideOverlay(true);
                return;
            }

            if (elapsedNow < leaveAppGraceUntilElapsedMs) {
                hideOverlay(true);
                return;
            }
            leaveAppGraceUntilElapsedMs = 0L;

            boolean shouldBlock = usageLedger.isOverLimit(foregroundPackage)
                    && !Preferences.isTemporarilyUnlocked(this, foregroundPackage);
            if (shouldBlock) {
                if (!isStillSafeToBlock(foregroundPackage)) {
                    resetOverlayFailures();
                    clearStickyBlockedPackage();
                    hideOverlay(false);
                    return;
                }
                if (!ForegroundEventPolicy.samePackage(previousForegroundPackage, foregroundPackage)
                        && foregroundPackage.equals(stickyBlockedPackageName)) {
                    overlayNeedsRefresh = true;
                }
                showOverlay(foregroundPackage);
            } else {
                lastCriticalBlockValidationPackage = null;
                resetOverlayFailures();
                if (foregroundPackage.equals(stickyBlockedPackageName)) {
                    clearStickyBlockedPackage();
                }
                hideOverlay(false);
            }
        } catch (RuntimeException exception) {
            pollHealthy = false;
            invalidateForegroundState();
            setMonitoringIssue(getString(R.string.monitoring_issue_blocker_error));
            debugLog("foreground completion failed: " + exception.getClass().getSimpleName());
        } finally {
            try {
                usageLedger.flush(false);
                if (debugSanityToken != null) {
                    debugLog("debug foreground sanity check completed token=" + debugSanityToken);
                }
                if (pollHealthy
                        && consecutiveOverlayFailures == 0
                        && consecutiveOverlayRemovalFailures == 0
                        && !foregroundStatusRecoveryPending) {
                    clearMonitoringIssue();
                }
            } catch (RuntimeException exception) {
                pollHealthy = false;
                try {
                    invalidateForegroundState();
                    setMonitoringIssue(getString(R.string.monitoring_issue_blocker_error));
                } catch (RuntimeException ignored) {
                    // Scheduling below is the last-resort recovery guarantee.
                }
                debugLog("foreground finalization failed: "
                        + exception.getClass().getSimpleName());
            } finally {
                scheduleNextForegroundPoll(queryStartedElapsedMs);
            }
        }
    }

    private boolean isStillSafeToBlock(String packageName) {
        if (packageName.equals(lastCriticalBlockValidationPackage)) {
            return true;
        }
        CriticalApps.refresh(getApplicationContext());
        boolean stillSelected = Preferences.selectedPackages(this).contains(packageName);
        if (stillSelected) {
            lastCriticalBlockValidationPackage = packageName;
        }
        return stillSelected;
    }

    private ForegroundQueryResult queryForegroundPackage(
            long now,
            long previousQueryEndMs,
            String previousForegroundPackage,
            boolean previousForegroundStateKnown,
            long previousForegroundCandidateEventMs,
            long previousLatestForegroundEventMs,
            Map<String, Long> previousLatestBackgroundEventMs,
            String blockedPackage,
            Set<String> transientPackages,
            long previousLifecycleEventMs,
            Set<String> previousLifecycleEventKeys,
            boolean runSanityCheck
    ) {
        UsageStatsManager usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usageStatsManager == null) {
            return ForegroundQueryResult.failed(
                    previousForegroundPackage,
                    previousForegroundStateKnown,
                    previousForegroundCandidateEventMs,
                    previousLatestForegroundEventMs,
                    previousLatestBackgroundEventMs,
                    previousLifecycleEventMs,
                    previousLifecycleEventKeys
            );
        }

        UsageEvents events;
        try {
            long queryStartMs = Math.max(
                    now - USAGE_EVENT_OVERLAP_MS,
                    previousQueryEndMs - USAGE_EVENT_OVERLAP_MS
            );
            if (!previousForegroundStateKnown && runSanityCheck) {
                queryStartMs = Math.min(
                        queryStartMs,
                        now - UNKNOWN_FOREGROUND_LOOKBACK_MS
                );
            }
            events = queryLifecycleEvents(usageStatsManager, queryStartMs, now);
        } catch (SecurityException ignored) {
            return ForegroundQueryResult.failed(
                    previousForegroundPackage,
                    previousForegroundStateKnown,
                    previousForegroundCandidateEventMs,
                    previousLatestForegroundEventMs,
                    previousLatestBackgroundEventMs,
                    previousLifecycleEventMs,
                    previousLifecycleEventKeys
            );
        }
        if (events == null) {
            return ForegroundQueryResult.failed(
                    previousForegroundPackage,
                    previousForegroundStateKnown,
                    previousForegroundCandidateEventMs,
                    previousLatestForegroundEventMs,
                    previousLatestBackgroundEventMs,
                    previousLifecycleEventMs,
                    previousLifecycleEventKeys
            );
        }

        UsageEvents.Event event = new UsageEvents.Event();
        ForegroundEventPolicy.TimedCandidateState candidateState = previousForegroundStateKnown
                ? ForegroundEventPolicy.knownTimedCandidate(
                        previousForegroundPackage,
                        previousForegroundCandidateEventMs,
                        previousLatestForegroundEventMs,
                        previousLatestBackgroundEventMs
                )
                : ForegroundEventPolicy.unknownTimedCandidate();
        boolean overlayInterrupted = false;
        long latestLifecycleEventMs = previousLifecycleEventMs;
        Set<String> latestLifecycleEventKeys = new HashSet<>(previousLifecycleEventKeys);
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            int type = event.getEventType();
            String eventPackageName = event.getPackageName();
            if (eventPackageName == null) {
                continue;
            }
            long eventTimestampMs = event.getTimeStamp();
            if (!ForegroundEventPolicy.shouldApplyLifecycleEvent(
                    eventTimestampMs,
                    type,
                    eventPackageName,
                    latestLifecycleEventMs,
                    latestLifecycleEventKeys,
                    Build.VERSION.SDK_INT
            )) {
                continue;
            }
            if (eventTimestampMs > latestLifecycleEventMs) {
                latestLifecycleEventMs = eventTimestampMs;
                latestLifecycleEventKeys.clear();
            }
            if (eventTimestampMs == latestLifecycleEventMs) {
                latestLifecycleEventKeys.add(
                        ForegroundEventPolicy.lifecycleEventKey(type, eventPackageName)
                );
            }

            if (eventTimestampMs > previousQueryEndMs
                    && ForegroundEventPolicy.isOverlayInterruptionEvent(
                            type,
                            eventPackageName,
                            blockedPackage,
                            transientPackages,
                            Build.VERSION.SDK_INT
                    )) {
                overlayInterrupted = true;
                debugLog("overlay interruption event=" + type + " package=" + eventPackageName);
            }

            candidateState = ForegroundEventPolicy.applyTimedLifecycleEvent(
                    candidateState,
                    type,
                    eventPackageName,
                    eventTimestampMs,
                    Build.VERSION.SDK_INT
            );
        }

        if (ForegroundEventPolicy.shouldSeedFromUsageSummary(
                candidateState,
                runSanityCheck
        )) {
            UsageStats mostRecent = mostRecentlyUsedPackage(usageStatsManager, now);
            if (mostRecent != null
                    && mostRecent.getLastTimeUsed() > latestLifecycleEventMs
                    && mostRecent.getLastTimeUsed() >= now - 5 * 60_000L) {
                if (!ForegroundEventPolicy.samePackage(
                        candidateState.packageName,
                        mostRecent.getPackageName()
                )) {
                    debugLog("usage summary seeded foreground " + candidateState.packageName
                            + " -> " + mostRecent.getPackageName());
                }
                candidateState = ForegroundEventPolicy.seedTimedCandidate(
                        candidateState,
                        mostRecent.getPackageName()
                );
            }
        }

        candidateState = ForegroundEventPolicy.pruneTimedEvidence(
                candidateState,
                now - USAGE_EVENT_OVERLAP_MS
        );

        return ForegroundQueryResult.successful(
                candidateState,
                overlayInterrupted,
                latestLifecycleEventMs,
                latestLifecycleEventKeys
        );
    }

    private UsageEvents queryLifecycleEvents(
            UsageStatsManager usageStatsManager,
            long queryStartMs,
            long queryEndMs
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            UsageEventsQuery query = new UsageEventsQuery.Builder(queryStartMs, queryEndMs)
                    .setEventTypes(
                            UsageEvents.Event.ACTIVITY_RESUMED,
                            UsageEvents.Event.ACTIVITY_PAUSED,
                            UsageEvents.Event.ACTIVITY_STOPPED
                    )
                    .build();
            return usageStatsManager.queryEvents(query);
        }
        return usageStatsManager.queryEvents(queryStartMs, queryEndMs);
    }

    private UsageStats mostRecentlyUsedPackage(UsageStatsManager usageStatsManager, long now) {
        List<UsageStats> stats;
        try {
            stats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    now - 5 * 60_000L,
                    now
            );
        } catch (SecurityException ignored) {
            return null;
        }
        if (stats == null || stats.isEmpty()) {
            return null;
        }

        UsageStats mostRecent = null;
        for (UsageStats stat : stats) {
            if (mostRecent == null || stat.getLastTimeUsed() > mostRecent.getLastTimeUsed()) {
                mostRecent = stat;
            }
        }
        return mostRecent;
    }

    private void requestUsageReconciliation() {
        if (stopping || reconciliationInFlight) {
            return;
        }
        if (!Preferences.isMonitoringRequested(this)
                || !AndroidPermissions.hasUsageAccess(this)) {
            scheduleNextUsageReconciliation();
            return;
        }
        if (Preferences.isEmergencyPauseActive(this) || !isDeviceReadyForMonitoring()) {
            scheduleNextUsageReconciliation();
            return;
        }

        Set<String> selectedPackages = Preferences.selectedPackages(this);
        if (selectedPackages.isEmpty()) {
            scheduleNextUsageReconciliation();
            return;
        }

        Context appContext = getApplicationContext();
        reconciliationInFlight = true;
        boolean posted = reconciliationHandler != null && reconciliationHandler.post(() -> {
            String observedDay = Preferences.currentUsageDay();
            Map<String, Long> observedUsage;
            try {
                Preferences.pruneOldUsageIfNeeded(appContext);
                observedUsage = UsageTracker.queryTodayFromSystemStats(
                        appContext,
                        selectedPackages
                );
                Preferences.saveUsageForDayMs(appContext, observedDay, observedUsage);
            } catch (RuntimeException exception) {
                debugLog("usage reconciliation failed: " + exception.getClass().getSimpleName());
                observedUsage = new HashMap<>();
            }
            Map<String, Long> completedUsage = observedUsage;
            handler.post(() -> completeUsageReconciliation(
                    observedDay,
                    selectedPackages,
                    completedUsage
            ));
        });
        if (!posted) {
            reconciliationInFlight = false;
            scheduleNextUsageReconciliation();
        }
    }

    private void completeUsageReconciliation(
            String observedDay,
            Set<String> selectedPackages,
            Map<String, Long> observedUsage
    ) {
        reconciliationInFlight = false;
        if (stopping) {
            return;
        }
        usageLedger.ensure(selectedPackages);
        usageLedger.mergeObserved(observedDay, observedUsage);
        scheduleNextUsageReconciliation();
    }

    private void scheduleNextUsageReconciliation() {
        if (stopping) {
            return;
        }
        handler.removeCallbacks(reconciliationRunnable);
        handler.postDelayed(reconciliationRunnable, USAGE_RECONCILE_INTERVAL_MS);
    }

    private Set<String> transientSystemPackages() {
        Set<String> packageNames = new HashSet<>(homePackageNames());
        String resolvedHomePackage = homePackageName();
        if (!resolvedHomePackage.isEmpty()) {
            packageNames.add(resolvedHomePackage);
        }
        packageNames.add("android");
        packageNames.add("com.android.systemui");
        return packageNames;
    }

    private void beginTransientRecovery() {
        if (transientRecoveryStartedElapsedMs == 0L) {
            transientRecoveryStartedElapsedMs = SystemClock.elapsedRealtime();
            debugLog("transient foreground recovery started");
        }
    }

    private void endTransientRecovery() {
        transientRecoveryStartedElapsedMs = 0L;
    }

    private boolean pauseForEmergencyDayPassIfActive() {
        long pauseUntilMs = Preferences.emergencyPauseUntilMs(this);
        long remainingMs = pauseUntilMs - System.currentTimeMillis();
        if (remainingMs <= 0L) {
            if (emergencyPauseNotificationShown) {
                emergencyPauseNotificationShown = false;
                updateForegroundNotification();
            }
            return false;
        }

        if (!emergencyPauseNotificationShown) {
            emergencyPauseNotificationShown = true;
            updateForegroundNotification();
        }
        hideOverlay(false);
        flushUsageSafely(true, "emergency pause");
        resetForegroundEvidence(false, Long.MIN_VALUE);
        lastTickElapsedMs = SystemClock.elapsedRealtime();
        transientRecoveryStartedElapsedMs = 0L;
        MonitoringHealth.recordHeartbeat(this);
        scheduleForegroundPoll(
                Math.max(200L, Math.min(EMERGENCY_PAUSE_CHECK_INTERVAL_MS, remainingMs))
        );
        return true;
    }

    private void scheduleNextForegroundPoll(long queryStartedElapsedMs) {
        long queryDurationMs = Math.max(
                0L,
                SystemClock.elapsedRealtime() - queryStartedElapsedMs
        );
        scheduleForegroundPoll(nextPollDelayMs() - queryDurationMs);
    }

    private void scheduleForegroundPoll(long delayMs) {
        if (stopping) {
            return;
        }
        handler.removeCallbacks(pollRunnable);
        handler.postDelayed(pollRunnable, Math.max(200L, delayMs));
    }

    private long nextPollDelayMs() {
        if (transientRecoveryStartedElapsedMs == 0L) {
            return POLL_INTERVAL_MS;
        }

        long recoveryElapsedMs = SystemClock.elapsedRealtime() - transientRecoveryStartedElapsedMs;
        if (recoveryElapsedMs < RECOVERY_FAST_WINDOW_MS) {
            return RECOVERY_FAST_POLL_INTERVAL_MS;
        }
        if (isWaitingForBlockedAppReturn() && recoveryElapsedMs < RECOVERY_WARM_WINDOW_MS) {
            return RECOVERY_WARM_POLL_INTERVAL_MS;
        }
        return POLL_INTERVAL_MS;
    }

    private boolean isWaitingForBlockedAppReturn() {
        return stickyBlockedPackageName != null
                && overlayView == null
                && System.currentTimeMillis() <= keepOverlayUntilMs;
    }

    private void invalidateForegroundState() {
        hideOverlay(true, true);
        // A failed query must not let the aggregate fallback guess that the old
        // blocked app is still foreground. Require lifecycle evidence to reattach.
        resetForegroundEvidence(true, System.currentTimeMillis());
        lastCriticalBlockValidationPackage = null;
        lastTickElapsedMs = SystemClock.elapsedRealtime();
        if (stickyBlockedPackageName != null) {
            beginTransientRecovery();
        }
    }

    private void resetForegroundEvidence(boolean knownEmpty, long evidenceTimestampMs) {
        lastForegroundPackage = null;
        foregroundStateKnown = knownEmpty;
        foregroundCandidateEventMs = evidenceTimestampMs;
        latestForegroundEventMs = evidenceTimestampMs;
        latestBackgroundEventMs.clear();
    }

    private void resetLifecycleWatermark() {
        lastLifecycleEventMs = 0L;
        lastLifecycleEventKeys.clear();
    }

    private void markExplicitForegroundExit(String destination) {
        abandonForegroundQuery();
        resetForegroundEvidence(true, System.currentTimeMillis());
        lastCriticalBlockValidationPackage = null;
        lastTickElapsedMs = SystemClock.elapsedRealtime();
        scheduleForegroundPoll(RECOVERY_FAST_POLL_INTERVAL_MS);
        debugLog("foreground cleared after explicit exit to " + destination);
    }

    private void showOverlay(String packageName) {
        cancelOverlayRemovalRetry();
        rememberBlockedPackage(packageName);
        if (overlayView != null && packageName.equals(overlayPackageName)) {
            if (!overlayNeedsRefresh && overlayView.isAttachedToWindow()) {
                consecutiveOverlayRemovalFailures = 0;
                resetOverlayFailures();
                return;
            }
            debugLog("rebuilding overlay for " + packageName
                    + ", refresh=" + overlayNeedsRefresh
                    + ", attached=" + overlayView.isAttachedToWindow());
            if (!hideOverlay(true, true)) {
                return;
            }
        } else if (overlayView != null) {
            if (!hideOverlay(true, true)) {
                return;
            }
        }

        if (SystemClock.elapsedRealtime() < nextOverlayAttemptElapsedMs) {
            return;
        }

        if (!Settings.canDrawOverlays(this)) {
            consecutiveOverlayFailures = Math.max(1, consecutiveOverlayFailures);
            nextOverlayAttemptElapsedMs = SystemClock.elapsedRealtime() + DEGRADED_RETRY_INTERVAL_MS;
            setMonitoringIssue(getString(R.string.monitoring_requirement_overlay));
            return;
        }

        overlayPackageName = packageName;
        overlayView = buildOverlay(packageName);
        View observedOverlay = overlayView;
        ViewTreeObserver viewTreeObserver = observedOverlay.getViewTreeObserver();
        viewTreeObserver.addOnWindowFocusChangeListener(hasFocus -> {
            if (overlayView != observedOverlay) {
                return;
            }
            if (!hasFocus) {
                overlayWindowObscured = true;
                debugLog("overlay window lost focus for " + overlayPackageName);
            } else if (overlayWindowObscured) {
                // Android can suppress an attached overlay while recents owns the display.
                overlayWindowObscured = false;
                overlayNeedsRefresh = true;
                debugLog("overlay window regained focus; refresh requested");
            }
        });

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
        params.gravity = Gravity.CENTER;

        try {
            windowManager.addView(observedOverlay, params);
        } catch (RuntimeException exception) {
            if (observedOverlay.isAttachedToWindow()) {
                hideOverlay(true, true);
            } else {
                overlayView = null;
                overlayPackageName = null;
            }
            overlayNeedsRefresh = true;
            recordOverlayFailure(packageName, "add", exception);
            return;
        }

        try {
            UiStyle.applyDarkSystemBarAppearance(overlayView);
            overlayView.post(() -> {
                if (overlayView == observedOverlay && observedOverlay.isAttachedToWindow()) {
                    try {
                        UiStyle.applyDarkSystemBarAppearance(observedOverlay);
                    } catch (RuntimeException exception) {
                        overlayNeedsRefresh = true;
                        debugLog("overlay system-bar refresh failed for " + packageName + ": "
                                + exception.getClass().getSimpleName());
                    }
                }
            });
            overlayNeedsRefresh = false;
            consecutiveOverlayRemovalFailures = 0;
            resetOverlayFailures();
            debugLog("overlay added for " + packageName);
            blockerOverlayController.onAttached(overlayView);
        } catch (RuntimeException exception) {
            hideOverlay(true, true);
            overlayNeedsRefresh = true;
            recordOverlayFailure(packageName, "initialize", exception);
        }
    }

    private void recordOverlayFailure(
            String packageName,
            String operation,
            RuntimeException exception
    ) {
        consecutiveOverlayFailures++;
        long retryDelayMs = Math.min(
                30_000L,
                1_000L << Math.min(4, consecutiveOverlayFailures - 1)
        );
        nextOverlayAttemptElapsedMs = SystemClock.elapsedRealtime() + retryDelayMs;
        setMonitoringIssue(getString(R.string.monitoring_issue_blocker_failed));
        debugLog("overlay " + operation + " failed for " + packageName + ": "
                + exception.getClass().getSimpleName());
    }

    private void resetOverlayFailures() {
        consecutiveOverlayFailures = 0;
        nextOverlayAttemptElapsedMs = 0L;
    }

    private View buildOverlay(String packageName) {
        return blockerOverlayController.build(
                packageName,
                appLabel(packageName),
                usageLedger.usageMs(packageName) / 60_000L,
                Preferences.dailyLimitMinutes(this, packageName)
        );
    }

    private BlockerOverlayController.Listener createBlockerOverlayListener() {
        return new BlockerOverlayController.Listener() {
            @Override
            public void onFormInteraction() {
                keepOverlayUntilMs = System.currentTimeMillis() + OVERLAY_STICKY_MS;
            }

            @Override
            public boolean requestApproval(String packageName, int requestedMinutes) {
                return composeCodeSms(packageName, requestedMinutes);
            }

            @Override
            public int redeemApprovalCode(String packageName, String enteredCode) {
                return Preferences.redeemApprovalCodeAndGrantMinutes(
                        MonitoringService.this,
                        packageName,
                        enteredCode
                );
            }

            @Override
            public boolean consumeEmergencyCode(String enteredCode) {
                return Preferences.consumeEmergencyCode(MonitoringService.this, enteredCode);
            }

            @Override
            public void onLeaveApp() {
                leaveBlockedApp();
            }

            @Override
            public void onUnlockCelebrationStarted() {
                unlockCelebrationRunning = true;
                emergencyCelebrationRunning = false;
                celebrationDeadlineElapsedMs =
                        SystemClock.elapsedRealtime() + CELEBRATION_MAX_MS;
                keepOverlayUntilMs = System.currentTimeMillis() + OVERLAY_STICKY_MS;
            }

            @Override
            public void onUnlockCelebrationFinished(int approvedMinutes) {
                unlockCelebrationRunning = false;
                celebrationDeadlineElapsedMs = 0L;
                hideOverlay(false);
                Toast.makeText(
                        MonitoringService.this,
                        getResources().getQuantityString(
                                R.plurals.unlock_granted_toast,
                                approvedMinutes,
                                approvedMinutes
                        ),
                        Toast.LENGTH_LONG
                ).show();
            }

            @Override
            public void onEmergencyCelebrationStarted() {
                unlockCelebrationRunning = true;
                emergencyCelebrationRunning = true;
                celebrationDeadlineElapsedMs =
                        SystemClock.elapsedRealtime() + CELEBRATION_MAX_MS;
                clearStickyBlockedPackage();
                emergencyPauseNotificationShown = true;
                updateForegroundNotification();
            }

            @Override
            public void onEmergencyCelebrationFinished() {
                unlockCelebrationRunning = false;
                emergencyCelebrationRunning = false;
                celebrationDeadlineElapsedMs = 0L;
                hideOverlay(false);
                Toast.makeText(
                        MonitoringService.this,
                        R.string.emergency_active_toast,
                        Toast.LENGTH_LONG
                ).show();
                handler.removeCallbacks(pollRunnable);
                handler.post(pollRunnable);
            }
        };
    }

    private void leaveBlockedApp() {
        leaveAppGraceUntilElapsedMs = SystemClock.elapsedRealtime() + 1_500L;
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        boolean exitStarted = false;
        try {
            startActivity(home);
            exitStarted = true;
        } catch (RuntimeException exception) {
            debugLog("home exit failed: " + exception.getClass().getSimpleName());
        } finally {
            if (exitStarted) {
                markExplicitForegroundExit("home");
            }
            hideOverlay(true);
        }
    }

    private String stickyBlockedPackageForTransient(
            long now,
            String foregroundPackage,
            Set<String> selectedPackages
    ) {
        String packageName = overlayPackageName != null ? overlayPackageName : stickyBlockedPackageName;
        if (packageName == null
                || now > keepOverlayUntilMs
                || !selectedPackages.contains(packageName)
                || !isTransientSystemSurface(foregroundPackage)
                || !usageLedger.isOverLimit(packageName)
                || Preferences.isTemporarilyUnlocked(this, packageName)) {
            return null;
        }
        return packageName;
    }

    private boolean isTransientSystemSurface(String packageName) {
        if (packageName == null) {
            return true;
        }
        if ("android".equals(packageName) || "com.android.systemui".equals(packageName)) {
            return true;
        }
        return packageName.equals(homePackageName()) || homePackageNames().contains(packageName);
    }

    private boolean isTransientSystemSurface(
            String packageName,
            Set<String> transientPackages
    ) {
        return packageName == null || transientPackages.contains(packageName);
    }

    private String homePackageName() {
        if (homePackageName != null) {
            return homePackageName;
        }
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolveInfo = getPackageManager().resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
        if (resolveInfo != null && resolveInfo.activityInfo != null) {
            homePackageName = resolveInfo.activityInfo.packageName;
        } else {
            homePackageName = "";
        }
        return homePackageName;
    }

    private Set<String> homePackageNames() {
        if (homePackageNames != null) {
            return homePackageNames;
        }
        Set<String> packageNames = new HashSet<>();
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> resolveInfos = getPackageManager().queryIntentActivities(
                homeIntent,
                PackageManager.MATCH_DEFAULT_ONLY
        );
        for (ResolveInfo resolveInfo : resolveInfos) {
            if (resolveInfo != null && resolveInfo.activityInfo != null) {
                packageNames.add(resolveInfo.activityInfo.packageName);
            }
        }
        homePackageNames = packageNames;
        return homePackageNames;
    }

    private void rememberBlockedPackage(String packageName) {
        stickyBlockedPackageName = packageName;
        keepOverlayUntilMs = System.currentTimeMillis() + OVERLAY_STICKY_MS;
        endTransientRecovery();
    }

    private void clearStickyBlockedPackage() {
        stickyBlockedPackageName = null;
        keepOverlayUntilMs = 0L;
        overlayNeedsRefresh = false;
        overlayWindowObscured = false;
    }

    private boolean composeCodeSms(String packageName, int requestedMinutes) {
        String phone = Preferences.accountabilityPhoneNumber(this);
        if (phone.length() != 10) {
            Toast.makeText(this, R.string.sms_keyholder_required, Toast.LENGTH_LONG).show();
            return false;
        }
        if (!Preferences.isApprovalCalculatorReady(this)) {
            Toast.makeText(this, R.string.sms_approval_setup_required, Toast.LENGTH_LONG).show();
            return false;
        }

        String requestCode = Preferences.createRequestCode(this, packageName, requestedMinutes);
        if (requestCode.isEmpty()) {
            Toast.makeText(
                    this,
                    R.string.sms_request_save_failed,
                    Toast.LENGTH_LONG
            ).show();
            return false;
        }
        String message = getResources().getQuantityString(
                R.plurals.sms_request_body,
                requestedMinutes,
                requestedMinutes,
                appLabel(packageName),
                requestCode
        );
        Intent intent = new Intent(Intent.ACTION_SENDTO);
        intent.setData(Uri.parse("smsto:" + Uri.encode(phone)));
        intent.putExtra("sms_body", message);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
            leaveAppGraceUntilElapsedMs = SystemClock.elapsedRealtime() + 1_500L;
            markExplicitForegroundExit("messaging");
            hideOverlay(true, true);
            return true;
        } catch (RuntimeException ignored) {
            Toast.makeText(this, R.string.sms_app_missing, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private boolean hideOverlay(boolean preserveFormState) {
        return hideOverlay(preserveFormState, false);
    }

    private boolean hideOverlay(boolean preserveFormState, boolean preserveSticky) {
        unlockCelebrationRunning = false;
        emergencyCelebrationRunning = false;
        celebrationDeadlineElapsedMs = 0L;
        String removedPackageName = overlayPackageName;
        if (removedPackageName != null) {
            debugLog("hiding overlay for " + removedPackageName
                    + ", preserveSticky=" + preserveSticky);
        }
        if (overlayView == null) {
            overlayPackageName = null;
            if (!preserveSticky) {
                clearStickyBlockedPackage();
            }
            if (!preserveFormState && removedPackageName != null) {
                blockerOverlayController.clearFormState(removedPackageName);
            }
            cancelOverlayRemovalRetry();
            consecutiveOverlayRemovalFailures = 0;
            resetOverlayFailures();
            return true;
        }
        View viewToRemove = overlayView;
        try {
            KeyboardHelper.hide(this, viewToRemove);
        } catch (RuntimeException ignored) {
            // Keyboard cleanup must never prevent removal of the system-wide window.
        }
        try {
            windowManager.removeViewImmediate(viewToRemove);
        } catch (IllegalArgumentException ignored) {
            // Android already detached the view, often after overlay access changes.
        } catch (RuntimeException exception) {
            if (viewToRemove.isAttachedToWindow()) {
                consecutiveOverlayRemovalFailures++;
                consecutiveOverlayFailures = Math.max(1, consecutiveOverlayFailures);
                overlayNeedsRefresh = true;
                try {
                    setMonitoringIssue(getString(R.string.monitoring_issue_blocker_failed));
                } catch (RuntimeException ignored) {
                    // Retaining the view reference and retrying removal is authoritative.
                }
                debugLog("overlay removal failed for " + removedPackageName + ": "
                        + exception.getClass().getSimpleName());
                scheduleOverlayRemovalRetry(
                        viewToRemove,
                        preserveFormState,
                        preserveSticky
                );
                return false;
            }
        }
        overlayView = null;
        overlayPackageName = null;
        overlayWindowObscured = false;
        cancelOverlayRemovalRetry();
        consecutiveOverlayRemovalFailures = 0;
        resetOverlayFailures();
        if (!preserveSticky) {
            clearStickyBlockedPackage();
        }
        if (!preserveFormState && removedPackageName != null) {
            blockerOverlayController.clearFormState(removedPackageName);
        }
        return true;
    }

    private void scheduleOverlayRemovalRetry(
            View expectedView,
            boolean preserveFormState,
            boolean preserveSticky
    ) {
        cancelOverlayRemovalRetry();
        long retryDelayMs = Math.min(
                30_000L,
                200L << Math.min(7, Math.max(0, consecutiveOverlayRemovalFailures - 1))
        );
        overlayRemovalRetryRunnable = () -> {
            overlayRemovalRetryRunnable = null;
            if (overlayView != expectedView) {
                return;
            }
            hideOverlay(preserveFormState, preserveSticky);
        };
        handler.postDelayed(overlayRemovalRetryRunnable, retryDelayMs);
    }

    private void cancelOverlayRemovalRetry() {
        if (overlayRemovalRetryRunnable != null) {
            handler.removeCallbacks(overlayRemovalRetryRunnable);
            overlayRemovalRetryRunnable = null;
        }
    }

    private void stopMonitoring(boolean clearEnabledPreference) {
        stopping = true;
        handler.removeCallbacks(pollRunnable);
        handler.removeCallbacks(reconciliationRunnable);
        cancelForegroundQueryTimeout();
        cancelOverlayRemovalRetry();
        hideOverlay(false);
        if (clearEnabledPreference) {
            Preferences.setMonitoringRequested(this, false);
        }
        intentionallyStopped = intentionallyStopped
                || !Preferences.isMonitoringRequested(this);
        flushUsageSafely(true, "stop");
        blockerOverlayController.clearAllFormStates();
        stopForeground(true);
        stopSelf();
    }

    private void flushUsageSafely(boolean force, String operation) {
        try {
            usageLedger.flush(force);
        } catch (RuntimeException exception) {
            debugLog(operation + " usage flush failed: "
                    + exception.getClass().getSimpleName());
        }
    }

    private void debugLog(String message) {
        if (isDebuggable()) {
            Log.d(TAG, message);
        }
    }

    private boolean isDebuggable() {
        return (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private void updateForegroundNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private Notification buildNotification() {
        return MonitoringNotificationFactory.build(this, CHANNEL_ID, monitoringIssue);
    }

    private String appLabel(String packageName) {
        PackageManager packageManager = getPackageManager();
        try {
            CharSequence label = packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(packageName, 0)
            );
            return label == null ? packageName : label.toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            return packageName;
        }
    }

    private static final class ForegroundQueryResult {
        final String packageName;
        final boolean candidateKnown;
        final boolean overlayInterrupted;
        final boolean successful;
        final long latestLifecycleEventMs;
        final Set<String> latestLifecycleEventKeys;
        final long foregroundCandidateEventMs;
        final long latestForegroundEventMs;
        final Map<String, Long> latestBackgroundEventMs;

        private ForegroundQueryResult(
                String packageName,
                boolean candidateKnown,
                boolean overlayInterrupted,
                boolean successful,
                long latestLifecycleEventMs,
                Set<String> latestLifecycleEventKeys,
                long foregroundCandidateEventMs,
                long latestForegroundEventMs,
                Map<String, Long> latestBackgroundEventMs
        ) {
            this.packageName = packageName;
            this.candidateKnown = candidateKnown;
            this.overlayInterrupted = overlayInterrupted;
            this.successful = successful;
            this.latestLifecycleEventMs = latestLifecycleEventMs;
            this.latestLifecycleEventKeys = new HashSet<>(latestLifecycleEventKeys);
            this.foregroundCandidateEventMs = foregroundCandidateEventMs;
            this.latestForegroundEventMs = latestForegroundEventMs;
            this.latestBackgroundEventMs = new HashMap<>(latestBackgroundEventMs);
        }

        static ForegroundQueryResult successful(
                ForegroundEventPolicy.TimedCandidateState candidateState,
                boolean overlayInterrupted,
                long latestLifecycleEventMs,
                Set<String> latestLifecycleEventKeys
        ) {
            return new ForegroundQueryResult(
                    candidateState.packageName,
                    candidateState.known,
                    overlayInterrupted,
                    true,
                    latestLifecycleEventMs,
                    latestLifecycleEventKeys,
                    candidateState.candidateEventTimestampMs,
                    candidateState.latestForegroundEventTimestampMs,
                    candidateState.latestBackgroundEventTimestamps
            );
        }

        static ForegroundQueryResult failed(
                String packageName,
                boolean candidateKnown,
                long foregroundCandidateEventMs,
                long latestForegroundEventMs,
                Map<String, Long> latestBackgroundEventMs,
                long latestLifecycleEventMs,
                Set<String> latestLifecycleEventKeys
        ) {
            return new ForegroundQueryResult(
                    packageName,
                    candidateKnown,
                    false,
                    false,
                    latestLifecycleEventMs,
                    latestLifecycleEventKeys,
                    foregroundCandidateEventMs,
                    latestForegroundEventMs,
                    latestBackgroundEventMs
            );
        }
    }

}
