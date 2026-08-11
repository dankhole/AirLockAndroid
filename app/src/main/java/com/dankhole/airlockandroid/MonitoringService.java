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
    public static final String ACTION_START = "com.dankhole.airlockandroid.START";
    public static final String ACTION_STOP = "com.dankhole.airlockandroid.STOP";
    static final String ACTION_DEBUG_FORCE_FOREGROUND_SANITY =
            "com.dankhole.airlockandroid.DEBUG_FORCE_FOREGROUND_SANITY";
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
    private String lastCriticalBlockValidationPackage;
    private long lastTickElapsedMs;
    private long lastUsageQueryEndMs;
    private long lastForegroundSanityCheckMs;
    private long lastLifecycleEventMs;
    private long keepOverlayUntilMs;
    private long leaveAppGraceUntilElapsedMs;
    private long transientRecoveryStartedElapsedMs;
    private boolean unlockCelebrationRunning;
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
    private UsageLedger usageLedger;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            requestForegroundPoll();
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
        usageLedger.flush(true);
        hideOverlay(false);
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
            lastTickElapsedMs = SystemClock.elapsedRealtime();
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
        lastTickElapsedMs = SystemClock.elapsedRealtime();
        if (consecutiveForegroundQueryFailures >= 3) {
            setMonitoringIssue(getString(R.string.monitoring_issue_usage_unavailable));
            scheduleForegroundPoll(DEGRADED_RETRY_INTERVAL_MS);
        } else {
            scheduleForegroundPoll(POLL_INTERVAL_MS);
        }
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
        usageLedger.flush(true);
        hideOverlay(true);
        lastForegroundPackage = null;
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
        usageLedger.flush(true);
        hideOverlay(true);
        lastForegroundPackage = null;
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
                    long now = System.currentTimeMillis();
                    lastUsageQueryEndMs = now - USAGE_EVENT_OVERLAP_MS;
                    lastForegroundSanityCheckMs = 0L;
                    lastLifecycleEventMs = 0L;
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

    private void requestForegroundPoll() {
        if (stopping || foregroundQueryInFlight) {
            return;
        }
        if (!Preferences.isMonitoringRequested(this)) {
            stopMonitoring(false);
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
        if (unlockCelebrationRunning) {
            keepOverlayUntilMs = System.currentTimeMillis() + OVERLAY_STICKY_MS;
            scheduleNextForegroundPoll();
            return;
        }
        long now = System.currentTimeMillis();
        long elapsedNow = SystemClock.elapsedRealtime();
        long previousQueryEndMs = lastUsageQueryEndMs;
        if (previousQueryEndMs <= 0L
                || previousQueryEndMs > now
                || now - previousQueryEndMs > 5 * 60_000L) {
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
        String blockedPackage = overlayPackageName != null
                ? overlayPackageName
                : stickyBlockedPackageName;
        Set<String> selectedPackages = Preferences.selectedPackages(this);
        Set<String> transientPackages = transientSystemPackages();
        long previousLifecycleEventMs = lastLifecycleEventMs;
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
                        blockedPackage,
                        transientPackages,
                        previousLifecycleEventMs,
                        runSanityCheck
                );
            } catch (RuntimeException exception) {
                debugLog("foreground query failed: " + exception.getClass().getSimpleName());
                result = ForegroundQueryResult.failed(previousForegroundPackage, previousLifecycleEventMs);
            }
            ForegroundQueryResult completedResult = result;
            handler.post(() -> completeForegroundPoll(
                    queryId,
                    previousForegroundPackage,
                    selectedPackages,
                    completedResult,
                    debugSanityToken
            ));
        });
        if (!posted) {
            foregroundQueryInFlight = false;
            cancelForegroundQueryTimeout();
            consecutiveForegroundQueryFailures++;
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
            String previousForegroundPackage,
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
        lastLifecycleEventMs = Math.max(lastLifecycleEventMs, queryResult.latestLifecycleEventMs);
        long now = System.currentTimeMillis();
        long elapsedNow = SystemClock.elapsedRealtime();
        String foregroundPackage = queryResult.packageName;
        boolean foregroundIsTransient = isTransientSystemSurface(foregroundPackage);
        boolean previousForegroundWasTransient =
                isTransientSystemSurface(previousForegroundPackage);
        if (foregroundIsTransient && !previousForegroundWasTransient) {
            beginTransientRecovery();
        } else if (!foregroundIsTransient) {
            endTransientRecovery();
        }
        if (queryResult.overlayInterrupted) {
            overlayNeedsRefresh = true;
            lastCriticalBlockValidationPackage = null;
        }
        if (!ForegroundEventPolicy.samePackage(previousForegroundPackage, foregroundPackage)) {
            debugLog("foreground " + previousForegroundPackage + " -> " + foregroundPackage
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
            lastTickElapsedMs = elapsedNow;

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
            setMonitoringIssue(getString(R.string.monitoring_issue_blocker_error));
            debugLog("foreground completion failed: " + exception.getClass().getSimpleName());
        } finally {
            usageLedger.flush(false);
            if (debugSanityToken != null) {
                debugLog("debug foreground sanity check completed token=" + debugSanityToken);
            }
            if (pollHealthy
                    && consecutiveOverlayFailures == 0
                    && !foregroundStatusRecoveryPending) {
                clearMonitoringIssue();
            }
            scheduleNextForegroundPoll();
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
            String blockedPackage,
            Set<String> transientPackages,
            long previousLifecycleEventMs,
            boolean runSanityCheck
    ) {
        UsageStatsManager usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usageStatsManager == null) {
            return ForegroundQueryResult.failed(previousForegroundPackage, previousLifecycleEventMs);
        }

        UsageEvents events;
        try {
            long queryStartMs = Math.max(
                    now - USAGE_EVENT_OVERLAP_MS,
                    previousQueryEndMs - USAGE_EVENT_OVERLAP_MS
            );
            events = queryLifecycleEvents(usageStatsManager, queryStartMs, now);
        } catch (SecurityException ignored) {
            return ForegroundQueryResult.failed(previousForegroundPackage, previousLifecycleEventMs);
        }
        if (events == null) {
            return ForegroundQueryResult.failed(previousForegroundPackage, previousLifecycleEventMs);
        }

        UsageEvents.Event event = new UsageEvents.Event();
        String candidate = previousForegroundPackage;
        boolean sawLifecycleEvent = false;
        boolean overlayInterrupted = false;
        long latestLifecycleEventMs = previousLifecycleEventMs;
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            int type = event.getEventType();
            String eventPackageName = event.getPackageName();
            if (eventPackageName == null) {
                continue;
            }
            if (ForegroundEventPolicy.isLifecycleEvent(type)) {
                sawLifecycleEvent = true;
                latestLifecycleEventMs = Math.max(latestLifecycleEventMs, event.getTimeStamp());
            }

            if (event.getTimeStamp() > previousQueryEndMs
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

            // A canceled recents gesture can pause an app without resuming a replacement.
            // Keep the last real foreground app until another foreground event names one.
            if (ForegroundEventPolicy.isForegroundEvent(type)) {
                candidate = eventPackageName;
            } else if (ForegroundEventPolicy.isBackgroundEvent(type)
                    && eventPackageName.equals(candidate)
                    && isTransientSystemSurface(eventPackageName, transientPackages)
                    && blockedPackage != null) {
                // Returning to the same task from recents often pauses/stops the launcher
                // without sending another resumed event for the underlying app.
                candidate = blockedPackage;
                debugLog("transient surface exited; restoring candidate=" + candidate);
            }
        }

        if (ForegroundEventPolicy.shouldSeedFromUsageSummary(
                candidate,
                sawLifecycleEvent,
                runSanityCheck
        )) {
            UsageStats mostRecent = mostRecentlyUsedPackage(usageStatsManager, now);
            if (mostRecent != null
                    && mostRecent.getLastTimeUsed() > latestLifecycleEventMs
                    && mostRecent.getLastTimeUsed() >= now - 5 * 60_000L) {
                if (!ForegroundEventPolicy.samePackage(candidate, mostRecent.getPackageName())) {
                    debugLog("usage summary repaired foreground " + candidate
                            + " -> " + mostRecent.getPackageName());
                }
                candidate = mostRecent.getPackageName();
                latestLifecycleEventMs = mostRecent.getLastTimeUsed();
            }
        }

        return ForegroundQueryResult.successful(
                candidate,
                overlayInterrupted,
                latestLifecycleEventMs
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
        usageLedger.flush(true);
        hideOverlay(false);
        lastForegroundPackage = null;
        lastTickElapsedMs = SystemClock.elapsedRealtime();
        transientRecoveryStartedElapsedMs = 0L;
        MonitoringHealth.recordHeartbeat(this);
        scheduleForegroundPoll(
                Math.max(200L, Math.min(EMERGENCY_PAUSE_CHECK_INTERVAL_MS, remainingMs))
        );
        return true;
    }

    private void scheduleNextForegroundPoll() {
        scheduleForegroundPoll(nextPollDelayMs());
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

    private void showOverlay(String packageName) {
        rememberBlockedPackage(packageName);
        if (overlayView != null && packageName.equals(overlayPackageName)) {
            if (!overlayNeedsRefresh && overlayView.isAttachedToWindow()) {
                resetOverlayFailures();
                return;
            }
            debugLog("rebuilding overlay for " + packageName
                    + ", refresh=" + overlayNeedsRefresh
                    + ", attached=" + overlayView.isAttachedToWindow());
            hideOverlay(true, true);
        } else if (overlayView != null) {
            hideOverlay(true, true);
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
            windowManager.addView(overlayView, params);
            UiStyle.applyDarkSystemBarAppearance(overlayView);
            overlayView.post(() -> {
                if (overlayView == observedOverlay && observedOverlay.isAttachedToWindow()) {
                    UiStyle.applyDarkSystemBarAppearance(observedOverlay);
                }
            });
            overlayNeedsRefresh = false;
            resetOverlayFailures();
            debugLog("overlay added for " + packageName);
            blockerOverlayController.onAttached(overlayView);
        } catch (RuntimeException exception) {
            overlayView = null;
            overlayPackageName = null;
            overlayNeedsRefresh = true;
            consecutiveOverlayFailures++;
            long retryDelayMs = Math.min(
                    30_000L,
                    1_000L << Math.min(4, consecutiveOverlayFailures - 1)
            );
            nextOverlayAttemptElapsedMs = SystemClock.elapsedRealtime() + retryDelayMs;
            setMonitoringIssue(getString(R.string.monitoring_issue_blocker_failed));
            debugLog("overlay add failed for " + packageName + ": "
                    + exception.getClass().getSimpleName());
        }
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
                keepOverlayUntilMs = System.currentTimeMillis() + OVERLAY_STICKY_MS;
            }

            @Override
            public void onUnlockCelebrationFinished(int approvedMinutes) {
                unlockCelebrationRunning = false;
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
                clearStickyBlockedPackage();
                emergencyPauseNotificationShown = true;
                updateForegroundNotification();
            }

            @Override
            public void onEmergencyCelebrationFinished() {
                unlockCelebrationRunning = false;
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
        startActivity(home);
        hideOverlay(true);
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
            return true;
        } catch (RuntimeException ignored) {
            Toast.makeText(this, R.string.sms_app_missing, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void hideOverlay() {
        hideOverlay(true);
    }

    private void hideOverlay(boolean preserveFormState) {
        hideOverlay(preserveFormState, false);
    }

    private void hideOverlay(boolean preserveFormState, boolean preserveSticky) {
        unlockCelebrationRunning = false;
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
            return;
        }
        try {
            KeyboardHelper.hide(this, overlayView);
            windowManager.removeView(overlayView);
        } catch (RuntimeException ignored) {
            // The view may already be detached if Android revoked overlay permission.
        }
        overlayView = null;
        overlayPackageName = null;
        overlayWindowObscured = false;
        if (!preserveSticky) {
            clearStickyBlockedPackage();
        }
        if (!preserveFormState && removedPackageName != null) {
            blockerOverlayController.clearFormState(removedPackageName);
        }
    }

    private void stopMonitoring(boolean clearEnabledPreference) {
        stopping = true;
        if (clearEnabledPreference) {
            Preferences.setMonitoringRequested(this, false);
        }
        intentionallyStopped = intentionallyStopped
                || !Preferences.isMonitoringRequested(this);
        handler.removeCallbacks(pollRunnable);
        handler.removeCallbacks(reconciliationRunnable);
        cancelForegroundQueryTimeout();
        usageLedger.flush(true);
        hideOverlay(false);
        blockerOverlayController.clearAllFormStates();
        stopForeground(true);
        stopSelf();
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
        final boolean overlayInterrupted;
        final boolean successful;
        final long latestLifecycleEventMs;

        private ForegroundQueryResult(
                String packageName,
                boolean overlayInterrupted,
                boolean successful,
                long latestLifecycleEventMs
        ) {
            this.packageName = packageName;
            this.overlayInterrupted = overlayInterrupted;
            this.successful = successful;
            this.latestLifecycleEventMs = latestLifecycleEventMs;
        }

        static ForegroundQueryResult successful(
                String packageName,
                boolean overlayInterrupted,
                long latestLifecycleEventMs
        ) {
            return new ForegroundQueryResult(
                    packageName,
                    overlayInterrupted,
                    true,
                    latestLifecycleEventMs
            );
        }

        static ForegroundQueryResult failed(String packageName, long latestLifecycleEventMs) {
            return new ForegroundQueryResult(
                    packageName,
                    false,
                    false,
                    latestLifecycleEventMs
            );
        }
    }

}
