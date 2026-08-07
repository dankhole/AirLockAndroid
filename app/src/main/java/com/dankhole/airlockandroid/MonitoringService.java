package com.dankhole.airlockandroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
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

public class MonitoringService extends Service {
    public static final String ACTION_START = "com.dankhole.airlockandroid.START";
    public static final String ACTION_STOP = "com.dankhole.airlockandroid.STOP";

    private static final String CHANNEL_ID = "airlock_monitoring";
    private static final String TAG = "AirLockMonitor";
    private static final int NOTIFICATION_ID = 42;
    private static final long POLL_INTERVAL_MS = 1000L;
    private static final long RECOVERY_FAST_POLL_INTERVAL_MS = 200L;
    private static final long RECOVERY_WARM_POLL_INTERVAL_MS = 500L;
    private static final long RECOVERY_FAST_WINDOW_MS = 3_000L;
    private static final long RECOVERY_WARM_WINDOW_MS = 15_000L;
    private static final long USAGE_EVENT_OVERLAP_MS = 500L;
    private static final long RECOVERY_USAGE_EVENT_OVERLAP_MS = 3_000L;
    private static final long USAGE_RECONCILE_INTERVAL_MS = 60_000L;
    private static final long USAGE_PERSIST_INTERVAL_MS = 30_000L;
    private static final long EMERGENCY_PAUSE_CHECK_INTERVAL_MS = 60_000L;
    private static final long OVERLAY_STICKY_MS = 5 * 60 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private HandlerThread foregroundThread;
    private Handler foregroundHandler;
    private HandlerThread reconciliationThread;
    private Handler reconciliationHandler;
    private WindowManager windowManager;
    private View overlayView;
    private String overlayPackageName;
    private String stickyBlockedPackageName;
    private String homePackageName;
    private Set<String> homePackageNames;
    private String lastForegroundPackage;
    private long lastTickElapsedMs;
    private long lastUsageQueryEndMs;
    private long lastUsagePersistElapsedMs;
    private long keepOverlayUntilMs;
    private long leaveAppGraceUntilElapsedMs;
    private long transientRecoveryStartedElapsedMs;
    private boolean unlockCelebrationRunning;
    private boolean overlayNeedsRefresh;
    private boolean overlayWindowObscured;
    private boolean foregroundQueryInFlight;
    private boolean reconciliationInFlight;
    private boolean emergencyPauseNotificationShown;
    private boolean stopping;
    private String cachedUsageDay;
    private final Map<String, Long> usageTotalsMs = new HashMap<>();
    private final Set<String> dirtyUsagePackages = new HashSet<>();
    private final Map<String, OverlayFormState> overlayFormStates = new HashMap<>();

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
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        foregroundThread = new HandlerThread(
                "AirLockForeground",
                Process.THREAD_PRIORITY_BACKGROUND
        );
        foregroundThread.start();
        foregroundHandler = new Handler(foregroundThread.getLooper());
        reconciliationThread = new HandlerThread(
                "AirLockReconciliation",
                Process.THREAD_PRIORITY_BACKGROUND
        );
        reconciliationThread.start();
        reconciliationHandler = new Handler(reconciliationThread.getLooper());
        emergencyPauseNotificationShown = Preferences.isEmergencyPauseActive(this);
        startForeground(NOTIFICATION_ID, buildNotification());
        lastTickElapsedMs = SystemClock.elapsedRealtime();
        lastUsagePersistElapsedMs = lastTickElapsedMs;
        lastUsageQueryEndMs = System.currentTimeMillis();
        handler.post(pollRunnable);
        handler.post(reconciliationRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopMonitoring();
            return START_NOT_STICKY;
        }
        if (Preferences.prefs(this).getBoolean(Preferences.KEY_ENABLED, false)
                && Preferences.isEmergencyPauseActive(this)) {
            updateForegroundNotification();
            return START_STICKY;
        }
        if (!AndroidPermissions.hasUsageAccess(this)) {
            stopMonitoring();
            return START_NOT_STICKY;
        }
        if (!AndroidPermissions.hasOverlayAccess(this)) {
            stopMonitoring();
            return START_NOT_STICKY;
        }
        if (!Preferences.hasAccountabilityNumber(this)) {
            stopMonitoring();
            return START_NOT_STICKY;
        }
        if (!Preferences.hasMasterPin(this)) {
            stopMonitoring();
            return START_NOT_STICKY;
        }
        if (!Preferences.hasLimitedApps(this)) {
            stopMonitoring();
            return START_NOT_STICKY;
        }
        Preferences.prefs(this).edit().putBoolean(Preferences.KEY_ENABLED, true).apply();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        handler.removeCallbacks(pollRunnable);
        handler.removeCallbacks(reconciliationRunnable);
        flushUsageTotals(true);
        hideOverlay(false);
        if (foregroundHandler != null) {
            foregroundHandler.removeCallbacksAndMessages(null);
        }
        if (reconciliationHandler != null) {
            reconciliationHandler.removeCallbacksAndMessages(null);
        }
        if (foregroundThread != null) {
            foregroundThread.quitSafely();
        }
        if (reconciliationThread != null) {
            reconciliationThread.quitSafely();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void requestForegroundPoll() {
        if (stopping || foregroundQueryInFlight) {
            return;
        }
        SharedPreferences preferences = Preferences.prefs(this);
        if (!preferences.getBoolean(Preferences.KEY_ENABLED, false)) {
            stopMonitoring();
            return;
        }
        if (!unlockCelebrationRunning && pauseForEmergencyDayPassIfActive()) {
            return;
        }
        if (!AndroidPermissions.hasUsageAccess(this)) {
            stopMonitoring();
            return;
        }
        if (!AndroidPermissions.hasOverlayAccess(this)) {
            stopMonitoring();
            return;
        }
        if (!Preferences.hasAccountabilityNumber(this)) {
            stopMonitoring();
            return;
        }
        if (!Preferences.hasMasterPin(this)) {
            stopMonitoring();
            return;
        }
        if (!Preferences.hasLimitedApps(this)) {
            stopMonitoring();
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
        lastUsageQueryEndMs = now;
        long usageEventOverlapMs = isTransientRecoveryActive(elapsedNow)
                ? RECOVERY_USAGE_EVENT_OVERLAP_MS
                : USAGE_EVENT_OVERLAP_MS;
        String previousForegroundPackage = lastForegroundPackage;
        String blockedPackage = overlayPackageName != null
                ? overlayPackageName
                : stickyBlockedPackageName;
        Set<String> selectedPackages = Preferences.selectedPackages(this);
        Set<String> transientPackages = transientSystemPackages();

        foregroundQueryInFlight = true;
        boolean posted = foregroundHandler != null && foregroundHandler.post(() -> {
            ForegroundQueryResult result;
            try {
                result = queryForegroundPackage(
                        now,
                        previousQueryEndMs,
                        usageEventOverlapMs,
                        previousForegroundPackage,
                        blockedPackage,
                        transientPackages
                );
            } catch (RuntimeException exception) {
                debugLog("foreground query failed: " + exception.getClass().getSimpleName());
                result = new ForegroundQueryResult(previousForegroundPackage, false);
            }
            ForegroundQueryResult completedResult = result;
            handler.post(() -> completeForegroundPoll(
                    previousForegroundPackage,
                    selectedPackages,
                    completedResult
            ));
        });
        if (!posted) {
            foregroundQueryInFlight = false;
            scheduleNextForegroundPoll();
        }
    }

    private void completeForegroundPoll(
            String previousForegroundPackage,
            Set<String> selectedPackages,
            ForegroundQueryResult queryResult
    ) {
        foregroundQueryInFlight = false;
        if (stopping) {
            return;
        }
        if (!unlockCelebrationRunning && pauseForEmergencyDayPassIfActive()) {
            return;
        }

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
        }
        if (!samePackage(previousForegroundPackage, foregroundPackage)) {
            debugLog("foreground " + previousForegroundPackage + " -> " + foregroundPackage
                    + ", overlay=" + overlayPackageName
                    + ", sticky=" + stickyBlockedPackageName);
        }

        ensureUsageCache(selectedPackages);

        if (foregroundPackage != null
                && selectedPackages.contains(foregroundPackage)
                && foregroundPackage.equals(lastForegroundPackage)) {
            addUsageToCache(foregroundPackage, elapsedNow - lastTickElapsedMs);
        }

        lastForegroundPackage = foregroundPackage;
        lastTickElapsedMs = elapsedNow;

        try {
            boolean foregroundSelected = foregroundPackage != null
                    && selectedPackages.contains(foregroundPackage);
            if (!foregroundSelected) {
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
                hideOverlay(true);
                return;
            }

            if (elapsedNow < leaveAppGraceUntilElapsedMs) {
                hideOverlay(true);
                return;
            }
            leaveAppGraceUntilElapsedMs = 0L;

            boolean shouldBlock = isOverLimit(foregroundPackage)
                    && !Preferences.isTemporarilyUnlocked(this, foregroundPackage);
            if (shouldBlock) {
                if (!samePackage(previousForegroundPackage, foregroundPackage)
                        && foregroundPackage.equals(stickyBlockedPackageName)) {
                    overlayNeedsRefresh = true;
                }
                showOverlay(foregroundPackage);
            } else {
                if (foregroundPackage.equals(stickyBlockedPackageName)) {
                    clearStickyBlockedPackage();
                }
                hideOverlay(false);
            }
        } finally {
            flushUsageTotals(false);
            scheduleNextForegroundPoll();
        }
    }

    private ForegroundQueryResult queryForegroundPackage(
            long now,
            long previousQueryEndMs,
            long usageEventOverlapMs,
            String previousForegroundPackage,
            String blockedPackage,
            Set<String> transientPackages
    ) {
        UsageStatsManager usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usageStatsManager == null) {
            return new ForegroundQueryResult(previousForegroundPackage, false);
        }

        UsageEvents events;
        try {
            long queryStartMs = Math.max(
                    now - 10_000L,
                    previousQueryEndMs - usageEventOverlapMs
            );
            events = usageStatsManager.queryEvents(queryStartMs, now);
        } catch (SecurityException ignored) {
            return new ForegroundQueryResult(previousForegroundPackage, false);
        }

        UsageEvents.Event event = new UsageEvents.Event();
        String candidate = previousForegroundPackage;
        boolean sawForegroundEvent = false;
        boolean overlayInterrupted = false;
        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(event);
            int type = event.getEventType();
            String eventPackageName = event.getPackageName();
            if (eventPackageName == null) {
                continue;
            }

            if (event.getTimeStamp() > previousQueryEndMs
                    && isOverlayInterruptionEvent(
                            type,
                            eventPackageName,
                            blockedPackage,
                            transientPackages
                    )) {
                overlayInterrupted = true;
                debugLog("overlay interruption event=" + type + " package=" + eventPackageName);
            }

            // A canceled recents gesture can pause an app without resuming a replacement.
            // Keep the last real foreground app until another foreground event names one.
            if (isForegroundEvent(type)) {
                sawForegroundEvent = true;
                candidate = eventPackageName;
            } else if (isBackgroundEvent(type)
                    && eventPackageName.equals(candidate)
                    && isTransientSystemSurface(eventPackageName, transientPackages)
                    && blockedPackage != null) {
                // Returning to the same task from recents often pauses/stops the launcher
                // without sending another resumed event for the underlying app.
                candidate = blockedPackage;
                debugLog("transient surface exited; restoring candidate=" + candidate);
            }
        }

        if (candidate != null) {
            return new ForegroundQueryResult(candidate, overlayInterrupted);
        }
        if (sawForegroundEvent) {
            return new ForegroundQueryResult(null, overlayInterrupted);
        }

        List<UsageStats> stats;
        try {
            stats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    now - 5 * 60_000L,
                    now
            );
        } catch (SecurityException ignored) {
            return new ForegroundQueryResult(previousForegroundPackage, overlayInterrupted);
        }
        if (stats == null || stats.isEmpty()) {
            return new ForegroundQueryResult(previousForegroundPackage, overlayInterrupted);
        }

        UsageStats mostRecent = null;
        for (UsageStats stat : stats) {
            if (mostRecent == null || stat.getLastTimeUsed() > mostRecent.getLastTimeUsed()) {
                mostRecent = stat;
            }
        }
        return new ForegroundQueryResult(
                mostRecent == null ? previousForegroundPackage : mostRecent.getPackageName(),
                overlayInterrupted
        );
    }

    private void requestUsageReconciliation() {
        if (stopping || reconciliationInFlight) {
            return;
        }
        SharedPreferences preferences = Preferences.prefs(this);
        if (!preferences.getBoolean(Preferences.KEY_ENABLED, false)
                || !AndroidPermissions.hasUsageAccess(this)) {
            scheduleNextUsageReconciliation();
            return;
        }
        if (Preferences.isEmergencyPauseActive(this)) {
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
        ensureUsageCache(selectedPackages);
        if (observedDay.equals(cachedUsageDay)) {
            mergeObservedUsage(observedUsage);
        }
        scheduleNextUsageReconciliation();
    }

    private void scheduleNextUsageReconciliation() {
        if (stopping) {
            return;
        }
        handler.removeCallbacks(reconciliationRunnable);
        handler.postDelayed(reconciliationRunnable, USAGE_RECONCILE_INTERVAL_MS);
    }

    private void ensureUsageCache(Set<String> selectedPackages) {
        String currentDay = Preferences.currentUsageDay();
        if (cachedUsageDay == null) {
            cachedUsageDay = currentDay;
        } else if (!cachedUsageDay.equals(currentDay)) {
            flushUsageTotals(true);
            cachedUsageDay = currentDay;
            usageTotalsMs.clear();
            dirtyUsagePackages.clear();
            lastUsagePersistElapsedMs = SystemClock.elapsedRealtime();
        }

        for (String packageName : selectedPackages) {
            if (!usageTotalsMs.containsKey(packageName)) {
                usageTotalsMs.put(
                        packageName,
                        Preferences.getUsageForDayMs(this, cachedUsageDay, packageName)
                );
            }
        }
    }

    private void addUsageToCache(String packageName, long deltaMs) {
        if (deltaMs <= 0L || deltaMs > 10_000L) {
            return;
        }
        long current = usageTotalsMs.containsKey(packageName)
                ? usageTotalsMs.get(packageName)
                : Preferences.getUsageForDayMs(this, cachedUsageDay, packageName);
        usageTotalsMs.put(packageName, current + deltaMs);
        dirtyUsagePackages.add(packageName);
    }

    private void mergeObservedUsage(Map<String, Long> observedUsage) {
        for (Map.Entry<String, Long> entry : observedUsage.entrySet()) {
            String packageName = entry.getKey();
            long observedMs = entry.getValue() == null ? 0L : entry.getValue();
            long currentMs = usageTotalsMs.containsKey(packageName)
                    ? usageTotalsMs.get(packageName)
                    : 0L;
            if (observedMs >= currentMs) {
                usageTotalsMs.put(packageName, observedMs);
                dirtyUsagePackages.remove(packageName);
            }
        }
    }

    private long usageTodayMs(String packageName) {
        Long cached = usageTotalsMs.get(packageName);
        return cached == null ? Preferences.getUsageTodayMs(this, packageName) : cached;
    }

    private boolean isOverLimit(String packageName) {
        long limitMs = Preferences.dailyLimitMinutes(this, packageName) * 60_000L;
        return usageTodayMs(packageName) >= limitMs;
    }

    private void flushUsageTotals(boolean force) {
        if (cachedUsageDay == null || dirtyUsagePackages.isEmpty()) {
            return;
        }
        long elapsedNow = SystemClock.elapsedRealtime();
        if (!force && elapsedNow - lastUsagePersistElapsedMs < USAGE_PERSIST_INTERVAL_MS) {
            return;
        }

        Map<String, Long> dirtyTotals = new HashMap<>();
        for (String packageName : dirtyUsagePackages) {
            Long usageMs = usageTotalsMs.get(packageName);
            if (usageMs != null) {
                dirtyTotals.put(packageName, usageMs);
            }
        }
        Preferences.saveUsageForDayMs(this, cachedUsageDay, dirtyTotals);
        dirtyUsagePackages.clear();
        lastUsagePersistElapsedMs = elapsedNow;
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

    private boolean isTransientRecoveryActive(long elapsedNow) {
        if (transientRecoveryStartedElapsedMs == 0L) {
            return false;
        }
        long recoveryElapsedMs = elapsedNow - transientRecoveryStartedElapsedMs;
        if (recoveryElapsedMs < RECOVERY_FAST_WINDOW_MS) {
            return true;
        }
        return isWaitingForBlockedAppReturn() && recoveryElapsedMs < RECOVERY_WARM_WINDOW_MS;
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
        flushUsageTotals(true);
        hideOverlay(false);
        lastForegroundPackage = null;
        lastTickElapsedMs = SystemClock.elapsedRealtime();
        transientRecoveryStartedElapsedMs = 0L;
        handler.removeCallbacks(pollRunnable);
        handler.postDelayed(
                pollRunnable,
                Math.max(200L, Math.min(EMERGENCY_PAUSE_CHECK_INTERVAL_MS, remainingMs))
        );
        return true;
    }

    private void scheduleNextForegroundPoll() {
        if (stopping) {
            return;
        }
        handler.removeCallbacks(pollRunnable);
        handler.postDelayed(pollRunnable, nextPollDelayMs());
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
                return;
            }
            debugLog("rebuilding overlay for " + packageName
                    + ", refresh=" + overlayNeedsRefresh
                    + ", attached=" + overlayView.isAttachedToWindow());
            hideOverlay(true, true);
        } else if (overlayView != null) {
            hideOverlay(true, true);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
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

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE;
        params.gravity = Gravity.CENTER;

        try {
            windowManager.addView(overlayView, params);
            overlayNeedsRefresh = false;
            debugLog("overlay added for " + packageName);
            OverlayFormState formState = overlayFormState(packageName);
            EditText firstInput = overlayView.findViewWithTag(
                    formState.lastRequestedMinutes > 0 ? "code_input" : "minutes_input"
            );
            if (firstInput != null) {
                showKeyboard(firstInput);
            }
        } catch (RuntimeException ignored) {
            overlayView = null;
            overlayPackageName = null;
            overlayNeedsRefresh = true;
            debugLog("overlay add failed for " + packageName);
        }
    }

    private View buildOverlay(String packageName) {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.setBackgroundColor(UiStyle.COLOR_OVERLAY_BACKGROUND);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(UiStyle.COLOR_OVERLAY_BACKGROUND);
        UiStyle.applySystemInsetsPadding(root, 20, 24, 20, 24);
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout card = UiStyle.overlayCard(this);
        root.addView(card, UiStyle.fullWidth(this, 0));

        String appLabel = appLabel(packageName);
        long usedMinutes = usageTodayMs(packageName) / 60_000L;
        int limitMinutes = Preferences.dailyLimitMinutes(this, packageName);
        OverlayFormState formState = overlayFormState(packageName);

        card.addView(UiStyle.overlayTitle(this, "The goose says time's up!"), UiStyle.fullWidth(this, 8));

        TextView detail = UiStyle.overlayBody(
                this,
                appLabel + "\nThe goose counted " + usedMinutes + " of "
                        + limitMinutes + " minutes today!"
        );
        detail.setGravity(Gravity.CENTER);
        card.addView(detail, UiStyle.fullWidth(this, 18));

        TextView requestStatus = UiStyle.statusText(this);
        if (formState.lastRequestedMinutes > 0) {
            requestStatus.setText(requestSentMessage(formState.lastRequestedMinutes));
            UiStyle.setStatus(requestStatus, UiStyle.STATUS_READY);
        } else {
            requestStatus.setText("Need more time? Ask the Keyholder for an approval code!");
            UiStyle.setStatus(requestStatus, UiStyle.STATUS_WARNING);
        }
        card.addView(requestStatus, UiStyle.fullWidth(this, 14));

        TextView errorText = UiStyle.statusText(this);

        card.addView(UiStyle.overlayStepLabel(this, "1. Ask for minutes!"), UiStyle.fullWidth(this, 6));
        EditText minutesInput = new EditText(this);
        minutesInput.setTag("minutes_input");
        minutesInput.setHint("Extra minutes");
        KeyboardHelper.prepareNumericInput(minutesInput);
        minutesInput.setSingleLine(true);
        minutesInput.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        minutesInput.setText(formState.requestedMinutesText);
        minutesInput.setSelectAllOnFocus(true);
        minutesInput.setGravity(Gravity.CENTER);
        UiStyle.styleOverlayInput(minutesInput, formState.minutesInputError);
        minutesInput.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                formState.requestedMinutesText = editable.toString();
                formState.minutesInputError = false;
                UiStyle.styleOverlayInput(minutesInput, false);
                hideOverlayError(errorText, formState);
            }
        });
        minutesInput.setOnTouchListener((v, event) -> {
            boolean handled = KeyboardHelper.showOnTouch(this, minutesInput, event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                scrollToInput(scrollView, minutesInput);
            }
            return handled;
        });
        minutesInput.setOnClickListener(v -> {
            showKeyboard(minutesInput);
            scrollToInput(scrollView, minutesInput);
        });
        minutesInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                showKeyboard(minutesInput);
                scrollToInput(scrollView, minutesInput);
            }
        });
        card.addView(minutesInput, UiStyle.fullWidth(this, 12));

        card.addView(UiStyle.overlayStepLabel(this, "2. Text the Keyholder!"), UiStyle.fullWidth(this, 6));
        Button textCodeButton = UiStyle.primaryButton(this, "Text the Keyholder!");
        card.addView(textCodeButton, UiStyle.buttonParams(this));

        card.addView(UiStyle.overlayStepLabel(this, "3. Enter approval code!"), UiStyle.fullWidth(this, 6));
        EditText codeInput = new EditText(this);
        codeInput.setTag("code_input");
        codeInput.setHint("Approval code");
        KeyboardHelper.prepareNumericInput(codeInput);
        codeInput.setSingleLine(true);
        codeInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        codeInput.setText(formState.approvalCodeText);
        codeInput.setGravity(Gravity.CENTER);
        UiStyle.styleOverlayInput(codeInput, formState.codeInputError);
        codeInput.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                formState.approvalCodeText = editable.toString();
                formState.codeInputError = false;
                UiStyle.styleOverlayInput(codeInput, false);
                hideOverlayError(errorText, formState);
            }
        });
        codeInput.setOnTouchListener((v, event) -> {
            boolean handled = KeyboardHelper.showOnTouch(this, codeInput, event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                scrollToInput(scrollView, codeInput);
            }
            return handled;
        });
        codeInput.setOnClickListener(v -> {
            showKeyboard(codeInput);
            scrollToInput(scrollView, codeInput);
        });
        codeInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                showKeyboard(codeInput);
                scrollToInput(scrollView, codeInput);
            }
        });
        card.addView(codeInput, UiStyle.fullWidth(this, 12));

        TextView emergencyCodeHint = UiStyle.helperText(
                this,
                "Have a one-time 8-digit emergency code? Enter it here to pause all goose blocking for 24 hours."
        );
        emergencyCodeHint.setGravity(Gravity.CENTER);
        card.addView(emergencyCodeHint, UiStyle.fullWidth(this, 10));

        if (formState.errorMessage.isEmpty()) {
            errorText.setVisibility(View.GONE);
        } else {
            errorText.setText(formState.errorMessage);
            UiStyle.setStatus(errorText, UiStyle.STATUS_REQUIRED);
            errorText.setVisibility(View.VISIBLE);
        }
        card.addView(errorText, UiStyle.fullWidth(this, 12));

        card.addView(UiStyle.overlayStepLabel(this, "4. Let the goose loose!"), UiStyle.fullWidth(this, 6));
        Button unlockButton = UiStyle.primaryButton(this, "Loose the Goose!");
        card.addView(unlockButton, UiStyle.buttonParams(this));

        Button leaveButton = UiStyle.overlaySecondaryButton(this, "Leave App!");
        card.addView(leaveButton, UiStyle.buttonParams(this));

        textCodeButton.setOnClickListener(v -> {
            int requestedMinutes = parsePositiveInt(minutesInput);
            keepOverlayUntilMs = System.currentTimeMillis() + OVERLAY_STICKY_MS;
            if (requestedMinutes <= 0) {
                formState.minutesInputError = true;
                UiStyle.styleOverlayInput(minutesInput, true);
                showOverlayError(
                        errorText,
                        formState,
                        "Tell the goose how many minutes to ask for!"
                );
                return;
            }
            UiStyle.styleOverlayInput(minutesInput, false);
            if (composeCodeSms(packageName, requestedMinutes)) {
                formState.requestedMinutesText = String.valueOf(requestedMinutes);
                formState.lastRequestedMinutes = requestedMinutes;
                formState.minutesInputError = false;
                formState.errorMessage = "";
                minutesInput.setText(formState.requestedMinutesText);
                requestStatus.setText(requestSentMessage(requestedMinutes));
                UiStyle.setStatus(requestStatus, UiStyle.STATUS_READY);
                hideOverlayError(errorText, formState);
                showKeyboard(codeInput);
            }
        });

        unlockButton.setOnClickListener(v -> {
            String entered = codeInput.getText().toString().trim();
            keepOverlayUntilMs = System.currentTimeMillis() + OVERLAY_STICKY_MS;
            if (entered.isEmpty()) {
                formState.codeInputError = true;
                UiStyle.styleOverlayInput(codeInput, true);
                showOverlayError(
                        errorText,
                        formState,
                        "Enter the approval code first! The goose is still guarding this app!"
                );
                return;
            }
            if (Preferences.consumeEmergencyCode(this, entered)) {
                formState.approvalCodeText = "";
                formState.errorMessage = "";
                showEmergencyPauseCelebration(card);
                return;
            }
            int approvedMinutes = Preferences.consumeApprovalCodeMinutesIfValid(this, packageName, entered);
            if (approvedMinutes > 0) {
                Preferences.grantExtraTime(this, packageName, approvedMinutes);
                showUnlockCelebration(card, approvedMinutes);
            } else {
                formState.codeInputError = true;
                UiStyle.styleOverlayInput(codeInput, true);
                showOverlayError(
                        errorText,
                        formState,
                        "That code did not honk! Request a new one from the Keyholder if needed!"
                );
            }
        });

        leaveButton.setOnClickListener(v -> {
            leaveAppGraceUntilElapsedMs = SystemClock.elapsedRealtime() + 1_500L;
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
            hideOverlay(true);
        });

        return scrollView;
    }

    private void showUnlockCelebration(LinearLayout card, int approvedMinutes) {
        unlockCelebrationRunning = true;
        keepOverlayUntilMs = System.currentTimeMillis() + OVERLAY_STICKY_MS;
        card.removeAllViews();
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        GooseCelebrationView gooseView = new GooseCelebrationView(this);
        card.addView(gooseView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiStyle.dp(this, 190)
        ));

        TextView title = UiStyle.overlayTitle(this, "The goose is loose!");
        card.addView(title, UiStyle.fullWidth(this, 8));

        TextView body = UiStyle.overlayBody(
                this,
                approvedMinutes + " minutes of extra time! Honk!"
        );
        body.setGravity(Gravity.CENTER);
        card.addView(body, UiStyle.fullWidth(this, 0));

        gooseView.start(() -> {
            unlockCelebrationRunning = false;
            hideOverlay(false);
            Toast.makeText(
                    this,
                    "The goose is loose for " + approvedMinutes + " minutes!",
                    Toast.LENGTH_LONG
            ).show();
        });
    }

    private void showEmergencyPauseCelebration(LinearLayout card) {
        unlockCelebrationRunning = true;
        clearStickyBlockedPackage();
        emergencyPauseNotificationShown = true;
        updateForegroundNotification();
        card.removeAllViews();
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        GooseCelebrationView gooseView = new GooseCelebrationView(this);
        card.addView(gooseView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiStyle.dp(this, 190)
        ));

        TextView title = UiStyle.overlayTitle(this, "The goose is taking the day off!");
        card.addView(title, UiStyle.fullWidth(this, 8));

        TextView body = UiStyle.overlayBody(
                this,
                "All goose blocking is paused for 24 hours. Duty resumes automatically!"
        );
        body.setGravity(Gravity.CENTER);
        card.addView(body, UiStyle.fullWidth(this, 0));

        gooseView.start(() -> {
            unlockCelebrationRunning = false;
            hideOverlay(false);
            Toast.makeText(
                    this,
                    "Emergency day pass active. Goose duty resumes in 24 hours!",
                    Toast.LENGTH_LONG
            ).show();
            handler.removeCallbacks(pollRunnable);
            handler.post(pollRunnable);
        });
    }

    private OverlayFormState overlayFormState(String packageName) {
        OverlayFormState state = overlayFormStates.get(packageName);
        if (state == null) {
            state = new OverlayFormState();
            overlayFormStates.put(packageName, state);
        }
        return state;
    }

    private String requestSentMessage(int requestedMinutes) {
        return "Goose request sent to the Keyholder for " + requestedMinutes
                + " minutes! Enter the approval code the Keyholder sends back!";
    }

    private void showOverlayError(TextView errorText, OverlayFormState formState, String message) {
        formState.errorMessage = "REQUIRED: " + message;
        errorText.setText(formState.errorMessage);
        UiStyle.setStatus(errorText, UiStyle.STATUS_REQUIRED);
        errorText.setVisibility(View.VISIBLE);
    }

    private void hideOverlayError(TextView errorText, OverlayFormState formState) {
        formState.errorMessage = "";
        errorText.setText("");
        errorText.setVisibility(View.GONE);
    }

    private void scrollToInput(ScrollView scrollView, View input) {
        input.postDelayed(() -> {
            Rect bounds = new Rect();
            input.getDrawingRect(bounds);
            scrollView.offsetDescendantRectToMyCoords(input, bounds);
            int targetY = Math.max(0, bounds.top - UiStyle.dp(this, 80));
            scrollView.smoothScrollTo(0, targetY);
        }, 250);
    }

    private void showKeyboard(EditText input) {
        KeyboardHelper.show(this, input);
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
                || !isOverLimit(packageName)
                || Preferences.isTemporarilyUnlocked(this, packageName)) {
            return null;
        }
        return packageName;
    }

    private boolean isForegroundEvent(int type) {
        return type == UsageEvents.Event.MOVE_TO_FOREGROUND
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && type == UsageEvents.Event.ACTIVITY_RESUMED);
    }

    private boolean isLifecycleEvent(int type) {
        return isForegroundEvent(type) || isBackgroundEvent(type);
    }

    private boolean isBackgroundEvent(int type) {
        return type == UsageEvents.Event.MOVE_TO_BACKGROUND
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && (type == UsageEvents.Event.ACTIVITY_PAUSED
                || type == UsageEvents.Event.ACTIVITY_STOPPED));
    }

    private boolean isOverlayInterruptionEvent(
            int type,
            String packageName,
            String blockedPackage,
            Set<String> transientPackages
    ) {
        if (blockedPackage == null) {
            return false;
        }
        if (blockedPackage.equals(packageName)) {
            return isLifecycleEvent(type);
        }
        return isForegroundEvent(type)
                && isTransientSystemSurface(packageName, transientPackages);
    }

    private boolean samePackage(String left, String right) {
        return left == null ? right == null : left.equals(right);
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

    private int parsePositiveInt(EditText input) {
        try {
            int parsed = Integer.parseInt(input.getText().toString().trim());
            return parsed > 0 ? parsed : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private boolean composeCodeSms(String packageName, int requestedMinutes) {
        String phone = Preferences.accountabilityPhoneNumber(this);
        if (phone.length() != 10) {
            Toast.makeText(this, "Add the Keyholder's 10-digit phone number first!", Toast.LENGTH_LONG).show();
            return false;
        }

        String requestCode = Preferences.createRequestCode(this, packageName, requestedMinutes);
        String message = "The Goose is asking for " + requestedMinutes
                + " minutes of extra time in " + appLabel(packageName) + "!"
                + " Request code: " + requestCode
                + ". If approved, send back the approval code for this Goose request.";
        Intent intent = new Intent(Intent.ACTION_SENDTO);
        intent.setData(Uri.parse("smsto:" + Uri.encode(phone)));
        intent.putExtra("sms_body", message);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
            return true;
        } catch (RuntimeException ignored) {
            Toast.makeText(this, "No SMS app found!", Toast.LENGTH_SHORT).show();
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
                overlayFormStates.remove(removedPackageName);
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
            overlayFormStates.remove(removedPackageName);
        }
    }

    private void stopMonitoring() {
        stopping = true;
        handler.removeCallbacks(pollRunnable);
        handler.removeCallbacks(reconciliationRunnable);
        flushUsageTotals(true);
        Preferences.prefs(this).edit().putBoolean(Preferences.KEY_ENABLED, false).apply();
        hideOverlay(false);
        overlayFormStates.clear();
        stopForeground(true);
        stopSelf();
    }

    private void debugLog(String message) {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            Log.d(TAG, message);
        }
    }

    private void updateForegroundNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "AirLock goose watch",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        boolean emergencyPaused = Preferences.isEmergencyPauseActive(this);
        return builder
                .setContentTitle(emergencyPaused
                        ? "The goose is taking the day off!"
                        : "The goose is on duty!")
                .setContentText(emergencyPaused
                        ? "Emergency pause active. Goose duty resumes automatically!"
                        : "Guarding selected app limits!")
                .setSmallIcon(R.drawable.ic_stat_lock_clock)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
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

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence text, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence text, int start, int before, int count) {
        }
    }

    private static final class ForegroundQueryResult {
        final String packageName;
        final boolean overlayInterrupted;

        ForegroundQueryResult(String packageName, boolean overlayInterrupted) {
            this.packageName = packageName;
            this.overlayInterrupted = overlayInterrupted;
        }
    }

    private static final class OverlayFormState {
        String requestedMinutesText = "5";
        String approvalCodeText = "";
        String errorMessage = "";
        boolean minutesInputError;
        boolean codeInputError;
        int lastRequestedMinutes = -1;
    }
}
