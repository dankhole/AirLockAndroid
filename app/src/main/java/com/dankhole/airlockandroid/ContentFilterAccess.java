package com.dankhole.airlockandroid;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional-filter health, deliberately separate from ordinary monitoring health. */
final class ContentFilterAccess {
    enum State {
        DISABLED, DUTY_OFF, PAUSED, CONSENT_REQUIRED, ACCESS_REQUIRED,
        PREREQUISITES_REQUIRED, UNSUPPORTED, UNAVAILABLE, PARTIAL, READY
    }

    enum AndroidAccessState { UNKNOWN, ENABLED, DISABLED }

    // Shared across service recreation AND status probes: one worker, no waiting queue.
    static final BoundedTaskExecutor WORKER = new BoundedTaskExecutor(1, 30_000L, task -> {
        Thread thread = new Thread(() -> {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            } catch (RuntimeException ignored) {
                // Work remains off main even if priority adjustment is denied.
            }
            task.run();
        }, "AirlockContentFilter");
        thread.setDaemon(true);
        return thread;
    });
    private static final long PROBE_REFRESH_MS = 5_000L;
    private static final long PROBE_MAX_AGE_MS = 30_000L;
    private static final AtomicBoolean PROBE_PENDING = new AtomicBoolean();
    private static final Set<Runnable> LISTENERS = new CopyOnWriteArraySet<>();
    private static final AtomicBoolean CHANGE_PENDING = new AtomicBoolean();
    private static volatile PlatformSnapshot platformSnapshot;
    private static volatile long probeStartedElapsedMs = -1L;
    private static volatile long probeRequestedElapsedMs = -1L;
    private static Object serviceOwner;
    private static boolean serviceHealthy;
    private static long workStartedElapsedMs = -1L;

    private ContentFilterAccess() {
    }

    /** Fast UI API: only local settings/cache reads. Binder probes run on WORKER. */
    static State state(Context context) {
        Set<ContentFilter> configured = ContentFilterSettings.configuredFilters(context);
        if (configured.isEmpty()) {
            return State.DISABLED;
        }
        if (!isPlatformSupported()) return State.UNSUPPORTED;
        if (!Preferences.isMonitoringRequested(context)) {
            return State.DUTY_OFF;
        }
        if (Preferences.isEmergencyPauseActive(context)) {
            return State.PAUSED;
        }
        if (!ContentFilterSettings.hasConsent(context)) {
            return State.CONSENT_REQUIRED;
        }
        PlatformSnapshot snapshot = platformSnapshot;
        long now = SystemClock.elapsedRealtime();
        if (snapshot == null || !snapshot.configured.equals(configured)
                || !ContentActionPolicy.isFresh(snapshot.elapsedMs, now, PROBE_REFRESH_MS)) {
            requestRefresh(context);
        }
        if (snapshot == null || !snapshot.configured.equals(configured)
                || !snapshotUsable(snapshot, now)) return State.UNAVAILABLE;
        Set<ContentFilter> safeFilters = ContentFilterSettings.enabledFilters(context);
        State result = resolveState(true, true, false, true, snapshot.access,
                snapshot.prerequisites && localPrerequisites(context)
                        && CriticalApps.hasReliableSnapshot() && hasSafeLimitedApp(context)
                        && !safeFilters.isEmpty(), serviceAvailable(now), true);
        return configurationState(result, configured, safeFilters);
    }

    private static boolean snapshotUsable(PlatformSnapshot snapshot, long nowMs) {
        if (ContentActionPolicy.isFresh(snapshot.elapsedMs, nowMs, PROBE_MAX_AGE_MS)) return true;
        // A bounded in-progress renewal must not make the silent notification flicker every
        // ordinary 30-second health check. Never extend a snapshot by more than one work budget.
        return PROBE_PENDING.get() && ContentActionPolicy.isFresh(probeRequestedElapsedMs,
                nowMs, ContentActionPolicy.WORK_TIMEOUT_MS)
                && ContentActionPolicy.isFresh(snapshot.elapsedMs, nowMs,
                        PROBE_MAX_AGE_MS + ContentActionPolicy.WORK_TIMEOUT_MS);
    }

    static boolean isPlatformSupported() {
        return ContentActionPolicy.supportsFreshAuthority(Build.VERSION.SDK_INT);
    }

    static State resolveState(boolean configured, boolean duty, boolean paused,
            boolean consent, boolean enabled, boolean prerequisites, boolean live) {
        return resolveState(configured, duty, paused, consent, enabled ? AndroidAccessState.ENABLED
                : AndroidAccessState.DISABLED, prerequisites, live, true);
    }

    static State resolveState(boolean configured, boolean duty, boolean paused,
            boolean consent, AndroidAccessState access, boolean prerequisites,
            boolean live, boolean supported) {
        if (!configured) return State.DISABLED;
        if (!supported) return State.UNSUPPORTED;
        if (!duty) return State.DUTY_OFF;
        if (paused) return State.PAUSED;
        if (!consent) return State.CONSENT_REQUIRED;
        if (access == null || access == AndroidAccessState.UNKNOWN) return State.UNAVAILABLE;
        if (access == AndroidAccessState.DISABLED) return State.ACCESS_REQUIRED;
        if (!prerequisites) return State.PREREQUISITES_REQUIRED;
        return live ? State.READY : State.UNAVAILABLE;
    }

    static boolean executionAllowed(AndroidAccessState access, boolean prerequisites,
            Set<ContentFilter> safeFilters) {
        return access == AndroidAccessState.ENABLED && prerequisites
                && safeFilters != null && !safeFilters.isEmpty();
    }

    static boolean configurationComplete(Set<ContentFilter> configured,
            Set<ContentFilter> safeFilters) {
        return configured != null && safeFilters != null && configured.equals(safeFilters);
    }

    static State configurationState(State runtimeState, Set<ContentFilter> configured,
            Set<ContentFilter> safeFilters) {
        return runtimeState == State.READY && !configurationComplete(configured, safeFilters)
                ? State.PARTIAL : runtimeState;
    }

    static String statusText(Context context) {
        return textForState(context, state(context));
    }

    static String warning(Context context) {
        State state = state(context);
        return needsAttention(state) ? textForState(context, state) : "";
    }

    static boolean needsAttention(State state) {
        return state == State.CONSENT_REQUIRED || state == State.ACCESS_REQUIRED
                || state == State.PREREQUISITES_REQUIRED || state == State.UNSUPPORTED
                || state == State.UNAVAILABLE || state == State.PARTIAL;
    }

    private static String textForState(Context context, State state) {
        switch (state) {
            case DISABLED: return context.getString(R.string.content_filter_status_disabled);
            case DUTY_OFF: return context.getString(R.string.content_filter_status_duty_off);
            case PAUSED: return context.getString(R.string.content_filter_status_paused);
            case CONSENT_REQUIRED:
                return context.getString(R.string.content_filter_status_consent_required);
            case ACCESS_REQUIRED:
                return context.getString(R.string.content_filter_status_access_required);
            case PREREQUISITES_REQUIRED:
                return context.getString(R.string.content_filter_status_prerequisites_required);
            case UNSUPPORTED: return context.getString(R.string.content_filter_status_unsupported);
            case PARTIAL: return context.getString(R.string.content_filter_status_partial);
            case READY: return context.getString(R.string.content_filter_status_ready);
            default: return context.getString(R.string.content_filter_status_unavailable);
        }
    }

    /** Cached UI indication only. Missing, stale or failed probes are never a negative answer. */
    static AndroidAccessState androidAccessState(Context context) {
        PlatformSnapshot snapshot = platformSnapshot;
        long now = SystemClock.elapsedRealtime();
        if (snapshot == null || snapshot.access == AndroidAccessState.UNKNOWN
                || !ContentActionPolicy.isFresh(snapshot.elapsedMs, now, PROBE_REFRESH_MS)) {
            requestRefresh(context);
        }
        return snapshot == null ? AndroidAccessState.UNKNOWN : accessForSnapshot(snapshot.access,
                snapshot.elapsedMs, now, PROBE_PENDING.get() && ContentActionPolicy.isFresh(
                        probeRequestedElapsedMs, now, ContentActionPolicy.WORK_TIMEOUT_MS));
    }

    static AndroidAccessState accessForSnapshot(AndroidAccessState recorded,
            long capturedMs, long nowMs, boolean freshRenewalInFlight) {
        long maxAge = PROBE_MAX_AGE_MS
                + (freshRenewalInFlight ? ContentActionPolicy.WORK_TIMEOUT_MS : 0L);
        return recorded != null && ContentActionPolicy.isFresh(capturedMs, nowMs, maxAge)
                ? recorded : AndroidAccessState.UNKNOWN;
    }

    static boolean openSettings(Context context) {
        // Direct callers cannot bypass the prominent consent step.
        if (!ContentFilterSettings.hasConsent(context)) return false;
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean affectsConfiguration(String key) {
        return key == null || key.startsWith("content_filter_")
                || Preferences.KEY_ENABLED.equals(key)
                || Preferences.KEY_SELECTED_PACKAGES.equals(key)
                || Preferences.KEY_ACCOUNTABILITY_NUMBER.equals(key)
                || key.startsWith("master_pin_")
                || Preferences.KEY_MASTER_APPROVAL_TABLE.equals(key)
                || "emergency_pause_until".equals(key);
    }

    static void addListener(Runnable listener) {
        LISTENERS.add(listener);
    }

    static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    static void requestRefresh(Context context) {
        if (!PROBE_PENDING.compareAndSet(false, true)) return;
        probeRequestedElapsedMs = SystemClock.elapsedRealtime();
        Context app = context.getApplicationContext();
        if (!WORKER.tryExecute(() -> {
            long started = SystemClock.elapsedRealtime();
            probeStartedElapsedMs = started;
            Handler main = new Handler(Looper.getMainLooper());
            Runnable watchdog = () -> {
                if (PROBE_PENDING.get() && probeStartedElapsedMs == started) notifyChanged();
            };
            main.postDelayed(watchdog, ContentActionPolicy.WORK_TIMEOUT_MS + 1L);
            try {
                refreshPlatformSnapshot(app);
            } finally {
                boolean timedOut = !ContentActionPolicy.isFresh(started,
                        SystemClock.elapsedRealtime(), ContentActionPolicy.WORK_TIMEOUT_MS);
                probeStartedElapsedMs = -1L;
                probeRequestedElapsedMs = -1L;
                PROBE_PENDING.set(false);
                main.removeCallbacks(watchdog);
                if (timedOut) notifyChanged();
            }
        })) {
            probeRequestedElapsedMs = -1L;
            PROBE_PENDING.set(false);
        }
    }

    /** Worker-only. A fresh platform/settings check, never a cached action gate. */
    static boolean refreshPlatformSnapshot(Context context) {
        long captured = SystemClock.elapsedRealtime();
        AndroidAccessState access = probeAndroidAccess(context);
        boolean prerequisites = false;
        Set<ContentFilter> configured = ContentFilterSettings.configuredFilters(context);
        Set<ContentFilter> safeFilters = ContentFilterSettings.enabledFilters(context);
        try {
            prerequisites = CriticalApps.hasReliableSnapshot() && localPrerequisites(context)
                    && hasSafeLimitedApp(context)
                    && AndroidPermissions.hasUsageAccess(context)
                    && AndroidPermissions.hasOverlayAccess(context)
                    && AndroidPermissions.hasNotificationAccess(context);
        } catch (RuntimeException ignored) {
            // Failed prerequisite queries do not change the independent Android access result.
        }
        PlatformSnapshot previous = platformSnapshot;
        platformSnapshot = new PlatformSnapshot(configured, safeFilters, access, prerequisites, captured);
        if (previous == null || !ContentActionPolicy.isFresh(previous.elapsedMs,
                SystemClock.elapsedRealtime(), PROBE_REFRESH_MS)
                || previous.access != access || previous.prerequisites != prerequisites
                || !previous.configured.equals(configured) || !previous.safeFilters.equals(safeFilters)) {
            notifyChanged();
        }
        return executionAllowed(access, prerequisites, safeFilters);
    }

    private static AndroidAccessState probeAndroidAccess(Context context) {
        try {
            // This row describes the configured Android setting, not a live connection.
            // UiAutomation can suppress the manager's operational service list without
            // changing either setting; live readiness is tracked independently below.
            String global = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
            String encoded = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            ComponentName target = new ComponentName(context, ContentFilterService.class);
            boolean selected = false;
            boolean complete = true;
            if (encoded != null && !encoded.isEmpty()) {
                for (String id : encoded.split(":")) {
                    ComponentName component = ComponentName.unflattenFromString(id);
                    selected |= target.equals(component);
                    complete &= component != null;
                }
            }
            return configuredAndroidAccess(global, selected, complete);
        } catch (RuntimeException ignored) {
            return AndroidAccessState.UNKNOWN;
        }
    }

    static AndroidAccessState configuredAndroidAccess(String global,
            boolean ownComponentSelected, boolean complete) {
        if ("0".equals(global)) return AndroidAccessState.DISABLED;
        if (!"1".equals(global)) return AndroidAccessState.UNKNOWN;
        if (ownComponentSelected) return AndroidAccessState.ENABLED;
        return complete ? AndroidAccessState.DISABLED : AndroidAccessState.UNKNOWN;
    }

    static boolean hasSafeLimitedApp(Context context) {
        for (String packageName : Preferences.prefs(context).getStringSet(
                Preferences.KEY_SELECTED_PACKAGES, Collections.emptySet())) {
            if (CriticalApps.canFilterFromSnapshot(packageName)) return true;
        }
        return false;
    }

    private static boolean localPrerequisites(Context context) {
        return Preferences.hasAccountabilityNumber(context) && Preferences.hasMasterPin(context)
                && Preferences.isApprovalCalculatorReady(context)
                && !Preferences.prefs(context).getStringSet(Preferences.KEY_SELECTED_PACKAGES,
                        Collections.emptySet()).isEmpty();
    }

    static synchronized void connected(Object owner) {
        serviceOwner = owner;
        serviceHealthy = false;
        workStartedElapsedMs = -1L;
        notifyChanged();
    }

    static synchronized void disconnected(Object owner) {
        if (serviceOwner != owner) return;
        serviceOwner = null;
        serviceHealthy = false;
        workStartedElapsedMs = -1L;
        platformSnapshot = null;
        notifyChanged();
    }

    static synchronized void workStarted(Object owner, long startedMs) {
        if (serviceOwner == owner) workStartedElapsedMs = startedMs;
    }

    static synchronized void workFinished(Object owner, boolean healthy) {
        if (serviceOwner != owner) return;
        workStartedElapsedMs = -1L;
        boolean changed = serviceHealthy != healthy;
        serviceHealthy = healthy;
        if (changed) notifyChanged();
    }

    static synchronized boolean serviceAvailable(long nowMs) {
        return serviceOwner != null && serviceHealthy
                && (probeStartedElapsedMs < 0L || ContentActionPolicy.isFresh(
                        probeStartedElapsedMs, nowMs, ContentActionPolicy.WORK_TIMEOUT_MS))
                && (workStartedElapsedMs < 0L || ContentActionPolicy.isFresh(
                        workStartedElapsedMs, nowMs, ContentActionPolicy.WORK_TIMEOUT_MS));
    }

    private static void notifyChanged() {
        if (LISTENERS.isEmpty() || !CHANGE_PENDING.compareAndSet(false, true)) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            CHANGE_PENDING.set(false);
            for (Runnable listener : LISTENERS) {
                try {
                    listener.run();
                } catch (RuntimeException ignored) {
                    // Optional-filter observers cannot take down ordinary enforcement.
                }
            }
        });
    }

    private static final class PlatformSnapshot {
        final Set<ContentFilter> configured;
        final Set<ContentFilter> safeFilters;
        final AndroidAccessState access;
        final boolean prerequisites;
        final long elapsedMs;

        PlatformSnapshot(Set<ContentFilter> configured, Set<ContentFilter> safeFilters,
                AndroidAccessState access, boolean prerequisites, long elapsedMs) {
            this.configured = Collections.unmodifiableSet(new HashSet<>(configured));
            this.safeFilters = Collections.unmodifiableSet(new HashSet<>(safeFilters));
            this.access = access;
            this.prerequisites = prerequisites;
            this.elapsedMs = elapsedMs;
        }
    }
}
