package com.dankhole.airlockandroid;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.SuppressLint;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.graphics.Insets;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Optional, local-only feed recognition. Never used by the ordinary usage monitor. */
public final class ContentFilterService extends AccessibilityService {
    static final int MAX_NODES = 256;
    static final int MAX_DEPTH = 24;
    static final int MAX_CHILDREN = 32;
    static final int MAX_WINDOWS = 32;
    static final long SCAN_BUDGET_MS = 150L;
    static final long COALESCE_MS = 500L;
    private static final int EVENT_TYPES = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            | AccessibilityEvent.TYPE_VIEW_SCROLLED | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
    private static final ContentActionPolicy ACTIONS = new ContentActionPolicy();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean connected;
    private volatile boolean destroyed;
    private boolean cleanedUp;
    private boolean cacheDisabled;
    private boolean configurationDirty = true;
    private boolean workInFlight;
    private boolean dispatchScheduled;
    private String activeWorkPackage;
    private int activeWorkWindow = -1;
    private int rejectedAttempts;
    private long workId;
    private EventHint pendingEvent;
    private Set<String> subscribedPackages = new HashSet<>();
    private BroadcastReceiver screenReceiver;
    private BroadcastReceiver packageReceiver;
    private ContentObserver accessObserver;
    private SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;
    private Runnable watchdog;
    private final Runnable dispatch = this::dispatchWork;

    @Override
    public void onCreate() {
        super.onCreate();
        registerObserversSafely();
    }

    private boolean registerObserversSafely() {
        try {
            registerObservers();
            return true;
        } catch (RuntimeException ignored) {
            cleanup();
            return false;
        }
    }

    private void registerObservers() {
        preferenceListener = (preferences, key) -> {
            if (ContentFilterAccess.affectsConfiguration(key)) {
                // Invalidate immediately, even if the callback is not on the main thread.
                generation.incrementAndGet();
                main.post(this::configurationChanged);
            }
        };
        Preferences.prefs(this).registerOnSharedPreferenceChangeListener(preferenceListener);
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                generation.incrementAndGet();
                pendingEvent = null;
                main.removeCallbacks(dispatch);
                dispatchScheduled = false;
                if (!Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    configurationChanged();
                }
            }
        };
        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_USER_PRESENT);
        // The platform broadcast has no public SDK constant; action-time discovery
        // remains authoritative even on versions that do not deliver it.
        screenFilter.addAction("android.intent.action.ACTION_PREFERRED_ACTIVITY_CHANGED");
        screenFilter.addAction(TelecomManager.ACTION_DEFAULT_DIALER_CHANGED);
        screenFilter.addAction(Telephony.Sms.Intents.ACTION_DEFAULT_SMS_PACKAGE_CHANGED);
        registerLocalReceiver(screenReceiver, screenFilter);
        packageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                generation.incrementAndGet();
                configurationChanged();
            }
        };
        IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");
        registerLocalReceiver(packageReceiver, packageFilter);
        accessObserver = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                generation.incrementAndGet();
                configurationChanged();
            }
        };
        getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, accessObserver);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag") // Pre-33 branch: protected system broadcasts only.
    private void registerLocalReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if (cleanedUp) {
            cleanedUp = false;
            destroyed = false;
            if (!registerObserversSafely()) return;
        }
        connected = true;
        cacheDisabled = false;
        generation.incrementAndGet();
        ContentFilterAccess.connected(this);
        configurationChanged();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            handleEvent(event);
        } catch (RuntimeException ignored) {
            generation.incrementAndGet();
            pendingEvent = null;
            ContentFilterAccess.workFinished(this, false);
        }
    }

    private void handleEvent(AccessibilityEvent event) {
        if (!connected || destroyed || event == null
                || !ContentFilterAccess.isPlatformSupported()) return;
        int type = event.getEventType();
        if ((type & EVENT_TYPES) == 0) return;
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            generation.incrementAndGet();
        }
        // Never obtain an event source. Metadata is only a hint for a later fresh window query.
        CharSequence eventPackage = event.getPackageName();
        String packageName = eventPackage == null ? null : eventPackage.toString();
        if (workInFlight && (packageName == null || !packageName.equals(activeWorkPackage)
                || event.getWindowId() != activeWorkWindow)) {
            generation.incrementAndGet();
        }
        if (!isConfiguredPackage(packageName) || !ContentFilterSettings.hasConsent(this)
                || !Preferences.isMonitoringRequested(this)
                || Preferences.isEmergencyPauseActive(this)) {
            pendingEvent = null;
            return;
        }
        pendingEvent = new EventHint(packageName, event.getWindowId(), event.getEventTime());
        rejectedAttempts = 0;
        scheduleWork();
    }

    private boolean isConfiguredPackage(String packageName) {
        if (packageName == null) return false;
        for (ContentFilter filter : ContentFilterSettings.configuredFilters(this)) {
            if (packageName.equals(filter.packageName())) return true;
        }
        return false;
    }

    private void configurationChanged() {
        if (destroyed) return;
        configurationDirty = true;
        pendingEvent = null;
        rejectedAttempts = 0;
        scheduleWork();
    }

    private void scheduleWork() {
        if (!connected || destroyed || workInFlight || dispatchScheduled) return;
        dispatchScheduled = true;
        main.postDelayed(dispatch, COALESCE_MS);
    }

    private void dispatchWork() {
        dispatchScheduled = false;
        if (!connected || destroyed || workInFlight) return;
        EventHint hint = pendingEvent;
        pendingEvent = null;
        boolean configure = configurationDirty;
        configurationDirty = false;
        if (hint == null && !configure) return;
        long identity = ++workId;
        long epoch = generation.get();
        long started = SystemClock.elapsedRealtime();
        workInFlight = true;
        activeWorkPackage = hint == null ? null : hint.packageName;
        activeWorkWindow = hint == null ? -1 : hint.windowId;
        ContentFilterAccess.workStarted(this, started);
        watchdog = () -> {
            if (workInFlight && workId == identity) {
                generation.incrementAndGet();
                ContentFilterAccess.workFinished(this, false);
                // Do not replace a blocked Binder worker or start a retry loop.
            }
        };
        main.postDelayed(watchdog, ContentActionPolicy.WORK_TIMEOUT_MS);
        boolean accepted = ContentFilterAccess.WORKER.tryExecute(() -> {
            boolean healthy = false;
            try {
                healthy = inspect(hint, configure, epoch, started);
            } catch (RuntimeException ignored) {
                // No screen content, package history, or exception details are logged.
            } finally {
                boolean result = healthy && workCurrent(epoch, started);
                main.post(() -> finishWork(identity, result && workCurrent(epoch, started)));
            }
        });
        if (!accepted) {
            workInFlight = false;
            main.removeCallbacks(watchdog);
            ContentFilterAccess.workFinished(this, false);
            configurationDirty |= configure;
            pendingEvent = hint;
            // A transient UI status probe may own the worker. Only three finite retries.
            if (++rejectedAttempts <= 3) {
                dispatchScheduled = true;
                main.postDelayed(dispatch, COALESCE_MS);
            }
        }
    }

    private void finishWork(long identity, boolean healthy) {
        if (identity != workId) return;
        workInFlight = false;
        if (watchdog != null) main.removeCallbacks(watchdog);
        watchdog = null;
        if (destroyed) return;
        ContentFilterAccess.workFinished(this, healthy);
        if (configurationDirty || pendingEvent != null) scheduleWork();
    }

    private boolean inspect(EventHint hint, boolean configure, long epoch, long started) {
        if (!workCurrent(epoch, started)) return false;
        if (!ContentFilterAccess.isPlatformSupported()) {
            configurePackages(new HashSet<>());
            return false;
        }
        if (!prepareUncachedAuthority() || !workCurrent(epoch, started)) return false;
        Set<ContentFilter> configured = ContentFilterSettings.configuredFilters(this);
        if (!ContentFilterSettings.hasConsent(this) || configured.isEmpty()) {
            if (configure) configurePackages(new HashSet<>());
            return true;
        }
        // A cold or failed handler discovery cannot authorize even an initial tree read.
        if (configure || !CriticalApps.hasReliableSnapshot()) CriticalApps.refresh(this);
        if (!CriticalApps.hasReliableSnapshot()) {
            // Metadata hints from selected supported packages permit event-driven recovery.
            // No root is requested until discovery becomes reliable and all gates pass.
            if (workCurrent(epoch, started)) configurePackages(configured);
            return false;
        }
        Set<ContentFilter> filters = ContentFilterSettings.enabledFilters(this);
        if (!workCurrent(epoch, started)) return false;
        configurePackages(filters);
        if (filters.isEmpty()) {
            ContentFilterAccess.refreshPlatformSnapshot(this);
            return false;
        }
        boolean platformReady = ContentFilterAccess.refreshPlatformSnapshot(this);
        if (hint == null || !platformReady || !localInspectionAllowed(filters, epoch, started)
                || !ContentActionPolicy.isFresh(hint.uptimeMs, SystemClock.uptimeMillis(),
                        ContentActionPolicy.EVENT_MAX_AGE_MS)) return true;
        if (!hasPackage(filters, hint.packageName)) return true;
        ContentActionPolicy.Match first;
        try (WindowSnapshot window = focusedWindow(filters, hint.packageName, hint.windowId,
                epoch, started)) {
            if (window == null || !hint.packageName.equals(window.packageName)
                    || hint.windowId < 0 || hint.windowId != window.windowId) return true;
            ScanResult scan = scan(window, filters, epoch, started);
            if (!scan.complete) return false;
            if (scan.filter == null) {
                if (localInspectionAllowed(filters, epoch, started)) {
                    ACTIONS.observedClear(window.packageName, window.windowId);
                }
                return true;
            }
            first = new ContentActionPolicy.Match(window.packageName, window.windowId,
                    scan.filter, epoch, window.capturedElapsedMs);
        }
        if (!ACTIONS.canConsider(first, SystemClock.elapsedRealtime())) return false;
        // A camera/messaging/default-role change must win even over a previously safe match.
        CriticalApps.refresh(this);
        if (!CriticalApps.hasReliableSnapshot()
                || !ContentFilterAccess.refreshPlatformSnapshot(this)
                || !localInspectionAllowed(filters, epoch, started)) return true;
        ContentActionPolicy.Match fresh;
        try (WindowSnapshot window = focusedWindow(filters, first.packageName, first.windowId,
                epoch, started)) {
            if (window == null || !first.packageName.equals(window.packageName)
                    || first.windowId != window.windowId) return true;
            ScanResult scan = scan(window, filters, epoch, started);
            if (!scan.complete) return false;
            if (scan.filter != first.filter) return true;
            fresh = new ContentActionPolicy.Match(window.packageName, window.windowId,
                    scan.filter, epoch, window.capturedElapsedMs);
        }
        // The final inventory is fresh after traversal, preventing known Home/shade/IME races.
        try (WindowSnapshot window = focusedWindow(filters, first.packageName, first.windowId,
                epoch, started)) {
            if (window == null || !first.packageName.equals(window.packageName)
                    || first.windowId != window.windowId
                    || !localInspectionAllowed(filters, epoch, started) || !localPrerequisites()
                    || !ContentActionPolicy.isFresh(window.capturedElapsedMs,
                            SystemClock.elapsedRealtime(), ContentActionPolicy.EVIDENCE_MAX_AGE_MS)) {
                return true;
            }
            if (!ACTIONS.reserve(first, fresh, generation.get(), SystemClock.elapsedRealtime(),
                    localInspectionAllowed(filters, epoch, started) && localPrerequisites())) return false;
            if (!performGlobalAction(GLOBAL_ACTION_BACK)) return false;
        }
        main.post(() -> {
            try {
                if (!destroyed) Toast.makeText(this, R.string.content_filter_blocked_notice,
                        Toast.LENGTH_SHORT).show();
            } catch (RuntimeException ignored) {
                // Feedback failure cannot affect ordinary monitoring or repeat an action.
            }
        });
        return true;
    }

    private void configurePackages(Set<ContentFilter> filters) {
        Set<String> packages = new HashSet<>();
        if (ContentFilterSettings.hasConsent(this)) {
            for (ContentFilter filter : filters) packages.add(filter.packageName());
        }
        // An empty array means ALL packages on Android, so use a nonexistent sentinel instead.
        if (packages.isEmpty()) packages.add(getPackageName() + ".content_filter_disabled");
        if (packages.equals(subscribedPackages)) return;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) throw new IllegalStateException("Service not connected");
        info.packageNames = packages.toArray(new String[0]);
        info.eventTypes = EVENT_TYPES;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 150L;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
        subscribedPackages = packages;
    }

    private boolean workCurrent(long epoch, long started) {
        return connected && !destroyed && generation.get() == epoch
                && ContentActionPolicy.isFresh(started, SystemClock.elapsedRealtime(),
                        ContentActionPolicy.WORK_TIMEOUT_MS);
    }

    private boolean localInspectionAllowed(Set<ContentFilter> filters, long epoch, long started) {
        return workCurrent(epoch, started) && CriticalApps.hasReliableSnapshot()
                && ContentFilterSettings.hasConsent(this) && Preferences.isMonitoringRequested(this)
                && !Preferences.isEmergencyPauseActive(this)
                && filters.equals(ContentFilterSettings.enabledFilters(this));
    }

    private boolean localPrerequisites() {
        return Preferences.hasAccountabilityNumber(this) && Preferences.hasMasterPin(this)
                && Preferences.isApprovalCalculatorReady(this)
                && ContentFilterAccess.hasSafeLimitedApp(this);
    }

    private boolean enforcementAllowed(Set<ContentFilter> filters, long epoch, long started) {
        if (!localInspectionAllowed(filters, epoch, started) || !localPrerequisites()) return false;
        PowerManager power = getSystemService(PowerManager.class);
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        return power != null && power.isInteractive()
                && keyguard != null && !keyguard.isKeyguardLocked()
                && AndroidPermissions.hasUsageAccess(this)
                && AndroidPermissions.hasOverlayAccess(this)
                && AndroidPermissions.hasNotificationAccess(this)
                && workCurrent(epoch, started);
    }

    private static boolean hasPackage(Set<ContentFilter> filters, String packageName) {
        for (ContentFilter filter : filters) {
            if (filter.packageName().equals(packageName)) return true;
        }
        return false;
    }

    /** API 33 is the first public node AND window cache-control API. Worker-only. */
    private boolean prepareUncachedAuthority() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false;
        if (!cacheDisabled || isCacheEnabled()) {
            cacheDisabled = false;
            if (!setCacheEnabled(false) || isCacheEnabled()) return false;
            cacheDisabled = true;
        }
        // Also clear any prior connection data; a false result means no valid cache authority.
        boolean cleared = clearCache();
        return ContentActionPolicy.hasFreshAuthority(Build.VERSION.SDK_INT, cacheDisabled,
                isCacheEnabled(), cleared);
    }

    private ContentWindowPolicy.SystemBars currentSystemBars() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null;
        try {
            WindowManager manager = getSystemService(WindowManager.class);
            if (manager == null) return null;
            WindowMetrics metrics = manager.getCurrentWindowMetrics();
            Rect display = metrics.getBounds();
            WindowInsets insets = metrics.getWindowInsets();
            Insets status = insets.getInsets(WindowInsets.Type.statusBars());
            Insets navigation = insets.getInsets(WindowInsets.Type.navigationBars());
            return new ContentWindowPolicy.SystemBars(display.left, display.top,
                    display.right, display.bottom,
                    new ContentWindowPolicy.Insets(status.left, status.top, status.right, status.bottom),
                    new ContentWindowPolicy.Insets(navigation.left, navigation.top,
                            navigation.right, navigation.bottom));
        } catch (RuntimeException ignored) {
            // Without trustworthy bar geometry, overlapping windows remain obscurers.
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private WindowSnapshot focusedWindow(Set<ContentFilter> filters, String expectedPackage,
            int expectedWindowId, long epoch, long started) {
        if (!enforcementAllowed(filters, epoch, started)) return null;
        if (!prepareUncachedAuthority()) throw new IllegalStateException("Fresh authority unavailable");
        long captured = SystemClock.elapsedRealtime();
        ContentWindowPolicy.SystemBars bars = currentSystemBars();
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) return null;
        AccessibilityNodeInfo root = null;
        try {
            if (windows.size() > MAX_WINDOWS) return null;
            List<ContentWindowPolicy.Window> inventory = new ArrayList<>();
            for (AccessibilityWindowInfo window : windows) {
                Rect bounds = new Rect();
                window.getBoundsInScreen(bounds);
                ContentWindowPolicy.Kind kind = window.getType()
                        == AccessibilityWindowInfo.TYPE_APPLICATION
                        ? ContentWindowPolicy.Kind.APPLICATION
                        : window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                        ? ContentWindowPolicy.Kind.INPUT_METHOD
                        : window.getType() == AccessibilityWindowInfo.TYPE_SYSTEM
                        ? ContentWindowPolicy.Kind.SYSTEM : ContentWindowPolicy.Kind.OTHER;
                inventory.add(new ContentWindowPolicy.Window(kind, window.isActive(),
                        window.isFocused(), window.isInPictureInPictureMode(), window.getLayer(),
                        bounds.left, bounds.top, bounds.right, bounds.bottom));
            }
            int index = ContentWindowPolicy.actionWindowIndex(inventory, bars);
            if (index < 0 || !workCurrent(epoch, started)) return null;
            AccessibilityWindowInfo focused = windows.get(index);
            // Reject known other windows before requesting even their root node.
            if (expectedWindowId < 0 || focused.getId() != expectedWindowId
                    || !hasPackage(filters, expectedPackage)
                    || !CriticalApps.canFilterFromSnapshot(expectedPackage)) return null;
            root = focused.getRoot();
            if (root == null || !root.refresh() || root.getWindowId() != focused.getId()) return null;
            CharSequence packageValue = root.getPackageName();
            String packageName = packageValue == null ? null : packageValue.toString();
            if (!expectedPackage.equals(packageName) || !hasPackage(filters, packageName)
                    || !CriticalApps.canFilterFromSnapshot(packageName)
                    || !workCurrent(epoch, started)) return null;
            WindowSnapshot result = new WindowSnapshot(packageName, focused.getId(), root, captured);
            root = null;
            return result;
        } finally {
            if (root != null) root.recycle();
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
    }

    @SuppressWarnings("deprecation")
    private ScanResult scan(WindowSnapshot window, Set<ContentFilter> filters,
            long epoch, long started) {
        long scanStarted = SystemClock.elapsedRealtime();
        ArrayDeque<NodeAtDepth> pending = new ArrayDeque<>();
        List<ContentDetectionPolicy.NodeEvidence> evidence = new ArrayList<>();
        AccessibilityNodeInfo root = window.root;
        window.root = null;
        if (root == null) return new ScanResult(false, null);
        pending.addLast(new NodeAtDepth(root, 0));
        int visited = 0;
        try {
            while (!pending.isEmpty()) {
                if (!localInspectionAllowed(filters, epoch, started) || ++visited > MAX_NODES
                        || !ContentActionPolicy.isFresh(scanStarted, SystemClock.elapsedRealtime(),
                                SCAN_BUDGET_MS)) return new ScanResult(false, null);
                NodeAtDepth entry = pending.removeLast();
                AccessibilityNodeInfo node = entry.node;
                try {
                    CharSequence packageValue = node.getPackageName();
                    if (packageValue == null || !window.packageName.contentEquals(packageValue)
                            || node.getWindowId() != window.windowId) return new ScanResult(false, null);
                    boolean visible = node.isVisibleToUser();
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    boolean positiveBounds = bounds.width() > 0 && bounds.height() > 0;
                    String description = "";
                    if (ContentActionPolicy.shouldReadDescription(window.packageName,
                            visible, positiveBounds, node.isPassword(), node.isEditable())) {
                        CharSequence value = node.getContentDescription();
                        if (value != null) description = value.subSequence(0,
                                Math.min(ContentDetectionPolicy.MAX_DESCRIPTION_LENGTH,
                                        value.length())).toString();
                    }
                    evidence.add(new ContentDetectionPolicy.NodeEvidence(window.packageName,
                            node.getViewIdResourceName(), description, visible,
                            positiveBounds, node.isSelected()));
                    int children = node.getChildCount();
                    if (children > MAX_CHILDREN || (children > 0 && entry.depth >= MAX_DEPTH)
                            || visited + pending.size() + children > MAX_NODES) {
                        return new ScanResult(false, null);
                    }
                    for (int index = 0; index < children; index++) {
                        if (!localInspectionAllowed(filters, epoch, started)
                                || !ContentActionPolicy.isFresh(scanStarted,
                                        SystemClock.elapsedRealtime(), SCAN_BUDGET_MS)) {
                            return new ScanResult(false, null);
                        }
                        AccessibilityNodeInfo child = node.getChild(index);
                        if (child == null) return new ScanResult(false, null);
                        pending.addLast(new NodeAtDepth(child, entry.depth + 1));
                    }
                } finally {
                    node.recycle();
                }
            }
            if (!workCurrent(epoch, started) || !ContentActionPolicy.isFresh(scanStarted,
                    SystemClock.elapsedRealtime(), SCAN_BUDGET_MS)) return new ScanResult(false, null);
            for (ContentFilter filter : filters) {
                if (filter.packageName().equals(window.packageName)
                        && ContentDetectionPolicy.matches(filter, evidence)) {
                    return new ScanResult(true, filter);
                }
            }
            return new ScanResult(true, null);
        } finally {
            for (NodeAtDepth entry : pending) entry.node.recycle();
        }
    }

    @Override
    public void onInterrupt() {
        generation.incrementAndGet();
        pendingEvent = null;
        ContentFilterAccess.workFinished(this, false);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        cleanup();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private void cleanup() {
        if (cleanedUp) return;
        cleanedUp = true;
        destroyed = true;
        connected = false;
        generation.incrementAndGet();
        pendingEvent = null;
        main.removeCallbacksAndMessages(null);
        dispatchScheduled = false;
        ContentFilterAccess.disconnected(this);
        try {
            if (preferenceListener != null) {
                Preferences.prefs(this).unregisterOnSharedPreferenceChangeListener(preferenceListener);
            }
        } catch (RuntimeException ignored) {
            // Continue all independent cleanup even after a platform failure.
        }
        try {
            if (accessObserver != null) getContentResolver().unregisterContentObserver(accessObserver);
        } catch (RuntimeException ignored) {
            // The service is already disconnected and cannot inspect or act.
        }
        unregisterSafely(screenReceiver);
        unregisterSafely(packageReceiver);
    }

    private void unregisterSafely(BroadcastReceiver receiver) {
        try {
            if (receiver != null) unregisterReceiver(receiver);
        } catch (RuntimeException ignored) {
            // Includes partial lifecycle setup or Android already removing a receiver.
        }
    }

    private static final class EventHint {
        final String packageName;
        final int windowId;
        final long uptimeMs;

        EventHint(String packageName, int windowId, long uptimeMs) {
            this.packageName = packageName;
            this.windowId = windowId;
            this.uptimeMs = uptimeMs;
        }
    }

    private static final class NodeAtDepth {
        final AccessibilityNodeInfo node;
        final int depth;

        NodeAtDepth(AccessibilityNodeInfo node, int depth) {
            this.node = node;
            this.depth = depth;
        }
    }

    private static final class ScanResult {
        final boolean complete;
        final ContentFilter filter;

        ScanResult(boolean complete, ContentFilter filter) {
            this.complete = complete;
            this.filter = filter;
        }
    }

    private static final class WindowSnapshot implements AutoCloseable {
        final String packageName;
        final int windowId;
        final long capturedElapsedMs;
        AccessibilityNodeInfo root;

        WindowSnapshot(String packageName, int windowId, AccessibilityNodeInfo root,
                long capturedElapsedMs) {
            this.packageName = packageName;
            this.windowId = windowId;
            this.root = root;
            this.capturedElapsedMs = capturedElapsedMs;
        }

        @Override
        @SuppressWarnings("deprecation")
        public void close() {
            if (root != null) root.recycle();
            root = null;
        }
    }
}
