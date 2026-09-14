package com.dankhole.airlockandroid;

import android.annotation.SuppressLint;
import android.app.usage.UsageEvents;
import android.os.Build;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@SuppressLint("InlinedApi")
final class ForegroundEventPolicy {
    private ForegroundEventPolicy() {
    }

    static CandidateState unknownCandidate() {
        return new CandidateState(null, false);
    }

    static CandidateState knownCandidate(String packageName) {
        return new CandidateState(packageName, true);
    }

    static boolean isForegroundEvent(int type) {
        return isForegroundEvent(type, Build.VERSION.SDK_INT);
    }

    static boolean isForegroundEvent(int type, int sdkInt) {
        // ACTIVITY_RESUMED is the same numeric event as MOVE_TO_FOREGROUND.
        return type == UsageEvents.Event.MOVE_TO_FOREGROUND;
    }

    static boolean isBackgroundEvent(int type) {
        return isBackgroundEvent(type, Build.VERSION.SDK_INT);
    }

    static boolean isBackgroundEvent(int type, int sdkInt) {
        // ACTIVITY_PAUSED is the same numeric event as MOVE_TO_BACKGROUND.
        return type == UsageEvents.Event.MOVE_TO_BACKGROUND
                || (sdkInt >= Build.VERSION_CODES.Q
                && type == UsageEvents.Event.ACTIVITY_STOPPED);
    }

    static boolean isLifecycleEvent(int type) {
        return isLifecycleEvent(type, Build.VERSION.SDK_INT);
    }

    static boolean isLifecycleEvent(int type, int sdkInt) {
        return isForegroundEvent(type, sdkInt) || isBackgroundEvent(type, sdkInt);
    }

    static boolean isForegroundBoundaryEvent(int type, int sdkInt) {
        return (sdkInt >= Build.VERSION_CODES.P
                && (type == UsageEvents.Event.SCREEN_NON_INTERACTIVE
                || type == UsageEvents.Event.KEYGUARD_SHOWN))
                || (sdkInt >= Build.VERSION_CODES.Q
                && (type == UsageEvents.Event.DEVICE_SHUTDOWN
                || type == UsageEvents.Event.DEVICE_STARTUP));
    }

    static boolean shouldApplyLifecycleEvent(
            long eventTimestampMs,
            int type,
            String packageName,
            long latestProcessedTimestampMs,
            Set<String> processedKeysAtLatestTimestamp,
            int sdkInt
    ) {
        return shouldApplyLifecycleEvent(
                eventTimestampMs, type, packageName, null, latestProcessedTimestampMs,
                processedKeysAtLatestTimestamp, sdkInt
        );
    }

    static boolean shouldApplyLifecycleEvent(
            long eventTimestampMs,
            int type,
            String packageName,
            String className,
            long latestProcessedTimestampMs,
            Set<String> processedKeysAtLatestTimestamp,
            int sdkInt
    ) {
        if (!isForegroundBoundaryEvent(type, sdkInt)
                && (packageName == null || !isLifecycleEvent(type, sdkInt))) {
            return false;
        }
        if (eventTimestampMs > latestProcessedTimestampMs) {
            return true;
        }
        if (eventTimestampMs < latestProcessedTimestampMs) {
            // Overlap queries can deliver a previously unseen event late. Let the
            // timed reducer decide whether that older evidence can still affect
            // the candidate instead of dropping legitimate delayed resumes.
            return true;
        }
        return !processedKeysAtLatestTimestamp.contains(
                lifecycleEventKey(type, packageName, className)
        );
    }

    static String lifecycleEventKey(int type, String packageName) {
        return lifecycleEventKey(type, packageName, null);
    }

    static String lifecycleEventKey(int type, String packageName, String className) {
        return type + "\u0000" + activityKey(packageName, normalizeClassName(className));
    }

    static TimedCandidateState unknownTimedCandidate() {
        return unknownTimedCandidate(Long.MIN_VALUE);
    }

    static TimedCandidateState unknownTimedCandidate(long boundaryTimestampMs) {
        return new TimedCandidateState(
                null, null, false, Long.MIN_VALUE, Long.MIN_VALUE,
                null, null, boundaryTimestampMs, new HashMap<>()
        );
    }

    static TimedCandidateState knownTimedCandidate(
            String packageName,
            long candidateEventTimestampMs,
            long latestForegroundEventTimestampMs,
            Map<String, Long> latestBackgroundEventTimestamps
    ) {
        return knownTimedCandidate(
                packageName, candidateEventTimestampMs, latestForegroundEventTimestampMs,
                packageName, Long.MIN_VALUE, latestBackgroundEventTimestamps
        );
    }

    static TimedCandidateState knownTimedCandidate(
            String packageName,
            long candidateEventTimestampMs,
            long latestForegroundEventTimestampMs,
            String latestForegroundPackageName,
            long latestBoundaryEventTimestampMs,
            Map<String, Long> latestBackgroundEventTimestamps
    ) {
        return new TimedCandidateState(
                packageName, null, true, candidateEventTimestampMs, latestForegroundEventTimestampMs,
                latestForegroundPackageName, null, latestBoundaryEventTimestampMs,
                latestBackgroundEventTimestamps
        );
    }

    static TimedCandidateState applyTimedLifecycleEvent(
            TimedCandidateState state,
            int type,
            String packageName,
            long eventTimestampMs,
            int sdkInt
    ) {
        return applyTimedLifecycleEvent(
                state, type, packageName, null, eventTimestampMs, sdkInt
        );
    }

    static TimedCandidateState applyTimedLifecycleEvent(
            TimedCandidateState state,
            int type,
            String packageName,
            String className,
            long eventTimestampMs,
            int sdkInt
    ) {
        if (isForegroundBoundaryEvent(type, sdkInt)) {
            return applyTimedBoundary(state, eventTimestampMs);
        }
        if (packageName == null || !isLifecycleEvent(type, sdkInt)
                || eventTimestampMs <= state.latestBoundaryEventTimestampMs) {
            return state;
        }
        className = normalizeClassName(className);

        Map<String, Long> backgroundTimestamps =
                new HashMap<>(state.latestBackgroundEventTimestamps);
        if (isForegroundEvent(type, sdkInt)) {
            long latestActivityBackgroundMs = latestMatchingBackgroundTimestamp(
                    backgroundTimestamps, packageName, className
            );
            if (eventTimestampMs < state.latestForegroundEventTimestampMs) {
                return state;
            }
            if (eventTimestampMs == state.latestForegroundEventTimestampMs
                    && (!samePackage(packageName, state.latestForegroundPackageName)
                    || !samePackage(className, state.latestForegroundClassName))) {
                // Millisecond timestamps cannot establish which of two resumed
                // activities is current. Keep this ambiguity through overlap replay.
                return new TimedCandidateState(
                        null, null, true,
                        Math.max(state.candidateEventTimestampMs, eventTimestampMs),
                        eventTimestampMs, null, null, state.latestBoundaryEventTimestampMs,
                        backgroundTimestamps
                );
            }
            if (eventTimestampMs <= latestActivityBackgroundMs) {
                // A matching background wins a timestamp collision: replaying an
                // indistinguishable resume must never resurrect a departed app.
                boolean supersededCandidate = !state.known
                        || eventTimestampMs >= state.candidateEventTimestampMs;
                return new TimedCandidateState(
                        supersededCandidate ? null : state.packageName,
                        supersededCandidate ? null : state.className,
                        supersededCandidate || state.known,
                        supersededCandidate
                                ? latestActivityBackgroundMs
                                : state.candidateEventTimestampMs,
                        eventTimestampMs, packageName, className,
                        state.latestBoundaryEventTimestampMs,
                        backgroundTimestamps
                );
            }
            return new TimedCandidateState(
                    packageName, className, true, eventTimestampMs, eventTimestampMs,
                    packageName, className, state.latestBoundaryEventTimestampMs,
                    backgroundTimestamps
            );
        }

        String backgroundKey = activityKey(packageName, className);
        Long previousBackgroundMs = backgroundTimestamps.get(backgroundKey);
        if (previousBackgroundMs == null || eventTimestampMs > previousBackgroundMs) {
            backgroundTimestamps.put(backgroundKey, eventTimestampMs);
        }
        boolean clearsCandidate = !state.known
                || (samePackage(state.packageName, packageName)
                && mayMatchActivity(state.className, className)
                && eventTimestampMs >= state.candidateEventTimestampMs);
        return new TimedCandidateState(
                clearsCandidate ? null : state.packageName,
                clearsCandidate ? null : state.className,
                clearsCandidate || state.known,
                clearsCandidate ? eventTimestampMs : state.candidateEventTimestampMs,
                state.latestForegroundEventTimestampMs, state.latestForegroundPackageName,
                state.latestForegroundClassName,
                state.latestBoundaryEventTimestampMs, backgroundTimestamps
        );
    }

    static TimedCandidateState applyTimedBoundary(
            TimedCandidateState state,
            long eventTimestampMs
    ) {
        if (eventTimestampMs <= state.latestBoundaryEventTimestampMs) {
            return state;
        }
        boolean clearsCandidate = !state.known
                || state.candidateEventTimestampMs <= eventTimestampMs;
        return new TimedCandidateState(
                clearsCandidate ? null : state.packageName,
                clearsCandidate ? null : state.className,
                true,
                clearsCandidate ? eventTimestampMs : state.candidateEventTimestampMs,
                state.latestForegroundEventTimestampMs, state.latestForegroundPackageName,
                state.latestForegroundClassName,
                eventTimestampMs, state.latestBackgroundEventTimestamps
        );
    }

    static TimedCandidateState pruneTimedEvidence(
            TimedCandidateState state,
            long oldestRetainedTimestampMs
    ) {
        Map<String, Long> retained = new HashMap<>();
        String newestCloseKey = null;
        long newestCloseTimestampMs = Long.MIN_VALUE;
        for (Map.Entry<String, Long> entry
                : state.latestBackgroundEventTimestamps.entrySet()) {
            if (entry.getValue() >= oldestRetainedTimestampMs
                    || (samePackage(entry.getKey(), state.latestForegroundPackageName)
                    && entry.getValue() >= state.latestForegroundEventTimestampMs)) {
                // An unnamed close applies to every activity in its package.
                // Do not discard that wider evidence in favor of a named close.
                retained.put(entry.getKey(), entry.getValue());
            }
            if (matchesBackgroundKey(entry.getKey(), state.latestForegroundPackageName,
                    state.latestForegroundClassName)
                    && entry.getValue() >= state.latestForegroundEventTimestampMs
                    && (newestCloseKey == null || entry.getValue() > newestCloseTimestampMs
                    || (entry.getValue() == newestCloseTimestampMs
                    && entry.getKey().compareTo(newestCloseKey) < 0))) {
                newestCloseKey = entry.getKey();
                newestCloseTimestampMs = entry.getValue();
            }
        }
        if (newestCloseKey != null) {
            // Retain the close of the newest resume outside the overlap too.
            // One matching close is sufficient, even if that resume had no class.
            retained.put(newestCloseKey, newestCloseTimestampMs);
        }
        return new TimedCandidateState(
                state.packageName, state.className, state.known, state.candidateEventTimestampMs,
                state.latestForegroundEventTimestampMs, state.latestForegroundPackageName,
                state.latestForegroundClassName,
                state.latestBoundaryEventTimestampMs, retained
        );
    }

    static boolean hasForegroundAuthority(TimedCandidateState state) {
        return state.known
                && state.packageName != null
                && samePackage(state.packageName, state.latestForegroundPackageName)
                && samePackage(state.className, state.latestForegroundClassName)
                && state.candidateEventTimestampMs == state.latestForegroundEventTimestampMs
                && state.candidateEventTimestampMs > state.latestBoundaryEventTimestampMs
                && state.candidateEventTimestampMs > latestMatchingBackgroundTimestamp(
                        state.latestBackgroundEventTimestamps, state.packageName, state.className
                );
    }

    private static String normalizeClassName(String className) {
        return className == null || className.isEmpty() ? null : className;
    }

    private static String activityKey(String packageName, String className) {
        return className == null ? packageName : packageName + "\u0000" + className;
    }

    private static boolean mayMatchActivity(String leftClassName, String rightClassName) {
        // The public UsageEvents API identifies the class, not an activity instance.
        // Missing classes and repeated instances of one class must remain conservative.
        return leftClassName == null || rightClassName == null
                || leftClassName.equals(rightClassName);
    }

    private static boolean matchesBackgroundKey(
            String key,
            String packageName,
            String className
    ) {
        return packageName != null && (packageName.equals(key)
                || (className == null ? key.startsWith(packageName + "\u0000")
                : key.equals(activityKey(packageName, className))));
    }

    private static long latestMatchingBackgroundTimestamp(
            Map<String, Long> backgroundTimestamps,
            String packageName,
            String className
    ) {
        long latest = Long.MIN_VALUE;
        if (className != null) {
            Long unnamed = backgroundTimestamps.get(packageName);
            Long named = backgroundTimestamps.get(activityKey(packageName, className));
            return Math.max(unnamed == null ? latest : unnamed, named == null ? latest : named);
        }
        for (Map.Entry<String, Long> entry : backgroundTimestamps.entrySet()) {
            if (matchesBackgroundKey(entry.getKey(), packageName, null)) {
                latest = Math.max(latest, entry.getValue());
            }
        }
        return latest;
    }

    static boolean hasCandidateChanged(TimedCandidateState before, TimedCandidateState after) {
        // Background history can change without interrupting the resumed activity.
        return before.known != after.known
                || !samePackage(before.packageName, after.packageName)
                || !samePackage(before.className, after.className)
                || before.candidateEventTimestampMs != after.candidateEventTimestampMs;
    }

    static boolean isOverlayInterruptionEvent(
            int type,
            String packageName,
            String blockedPackage,
            Set<String> transientPackages,
            int sdkInt
    ) {
        if (blockedPackage == null) {
            return false;
        }
        if (isForegroundBoundaryEvent(type, sdkInt)) {
            return true;
        }
        if (blockedPackage.equals(packageName)) {
            return isLifecycleEvent(type, sdkInt);
        }
        return isForegroundEvent(type, sdkInt)
                && (packageName == null || transientPackages.contains(packageName));
    }

    static CandidateState applyLifecycleEvent(
            CandidateState state,
            int type,
            String packageName,
            int sdkInt
    ) {
        if (packageName == null || !isLifecycleEvent(type, sdkInt)) {
            return state;
        }
        if (isForegroundEvent(type, sdkInt)) {
            return knownCandidate(packageName);
        }
        if (!state.known || samePackage(state.packageName, packageName)) {
            // A background event proves the old candidate is no longer safe to cover.
            // Wait for a real foreground event instead of guessing which app resumes.
            return knownCandidate(null);
        }
        return state;
    }

    static boolean samePackage(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    static boolean shouldKeepCelebration(
            String celebratingPackage,
            CandidateState foregroundState,
            long elapsedRealtimeMs,
            long celebrationDeadlineElapsedMs
    ) {
        return celebratingPackage != null
                && foregroundState.known
                && celebratingPackage.equals(foregroundState.packageName)
                && elapsedRealtimeMs < celebrationDeadlineElapsedMs;
    }

    static final class CandidateState {
        final String packageName;
        final boolean known;

        private CandidateState(String packageName, boolean known) {
            this.packageName = packageName;
            this.known = known;
        }
    }

    static final class TimedCandidateState {
        final String packageName;
        final String className;
        final boolean known;
        final long candidateEventTimestampMs;
        final long latestForegroundEventTimestampMs;
        final String latestForegroundPackageName;
        final String latestForegroundClassName;
        final long latestBoundaryEventTimestampMs;
        // Package keys match any class; package + NUL + class keys match one class.
        final Map<String, Long> latestBackgroundEventTimestamps;

        private TimedCandidateState(
                String packageName,
                String className,
                boolean known,
                long candidateEventTimestampMs,
                long latestForegroundEventTimestampMs,
                String latestForegroundPackageName,
                String latestForegroundClassName,
                long latestBoundaryEventTimestampMs,
                Map<String, Long> latestBackgroundEventTimestamps
        ) {
            this.packageName = packageName;
            this.className = className;
            this.known = known;
            this.candidateEventTimestampMs = candidateEventTimestampMs;
            this.latestForegroundEventTimestampMs = latestForegroundEventTimestampMs;
            this.latestForegroundPackageName = latestForegroundPackageName;
            this.latestForegroundClassName = latestForegroundClassName;
            this.latestBoundaryEventTimestampMs = latestBoundaryEventTimestampMs;
            this.latestBackgroundEventTimestamps =
                    new HashMap<>(latestBackgroundEventTimestamps);
        }
    }
}
