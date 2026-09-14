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
        return !processedKeysAtLatestTimestamp.contains(lifecycleEventKey(type, packageName));
    }

    static String lifecycleEventKey(int type, String packageName) {
        return type + "\u0000" + packageName;
    }

    static TimedCandidateState unknownTimedCandidate() {
        return unknownTimedCandidate(Long.MIN_VALUE);
    }

    static TimedCandidateState unknownTimedCandidate(long boundaryTimestampMs) {
        return new TimedCandidateState(
                null, false, Long.MIN_VALUE, Long.MIN_VALUE,
                null, boundaryTimestampMs, new HashMap<>()
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
                packageName, true, candidateEventTimestampMs, latestForegroundEventTimestampMs,
                latestForegroundPackageName, latestBoundaryEventTimestampMs,
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
        if (isForegroundBoundaryEvent(type, sdkInt)) {
            return applyTimedBoundary(state, eventTimestampMs);
        }
        if (packageName == null || !isLifecycleEvent(type, sdkInt)
                || eventTimestampMs <= state.latestBoundaryEventTimestampMs) {
            return state;
        }

        Map<String, Long> backgroundTimestamps =
                new HashMap<>(state.latestBackgroundEventTimestamps);
        if (isForegroundEvent(type, sdkInt)) {
            long latestPackageBackgroundMs = backgroundTimestamps.containsKey(packageName)
                    ? backgroundTimestamps.get(packageName)
                    : Long.MIN_VALUE;
            if (eventTimestampMs < state.latestForegroundEventTimestampMs) {
                return state;
            }
            if (eventTimestampMs == state.latestForegroundEventTimestampMs
                    && !samePackage(packageName, state.latestForegroundPackageName)) {
                // Millisecond timestamps cannot establish which of two resumed
                // packages is current. Keep this ambiguity through overlap replay.
                return new TimedCandidateState(
                        null, true,
                        Math.max(state.candidateEventTimestampMs, eventTimestampMs),
                        eventTimestampMs, null, state.latestBoundaryEventTimestampMs,
                        backgroundTimestamps
                );
            }
            if (eventTimestampMs <= latestPackageBackgroundMs) {
                // A matching background wins a timestamp collision: replaying an
                // indistinguishable resume must never resurrect a departed app.
                boolean supersededCandidate = !state.known
                        || eventTimestampMs >= state.candidateEventTimestampMs;
                return new TimedCandidateState(
                        supersededCandidate ? null : state.packageName,
                        supersededCandidate || state.known,
                        supersededCandidate
                                ? latestPackageBackgroundMs
                                : state.candidateEventTimestampMs,
                        eventTimestampMs, packageName, state.latestBoundaryEventTimestampMs,
                        backgroundTimestamps
                );
            }
            return new TimedCandidateState(
                    packageName, true, eventTimestampMs, eventTimestampMs,
                    packageName, state.latestBoundaryEventTimestampMs, backgroundTimestamps
            );
        }

        Long previousBackgroundMs = backgroundTimestamps.get(packageName);
        if (previousBackgroundMs == null || eventTimestampMs > previousBackgroundMs) {
            backgroundTimestamps.put(packageName, eventTimestampMs);
        }
        boolean clearsCandidate = !state.known
                || (samePackage(state.packageName, packageName)
                && eventTimestampMs >= state.candidateEventTimestampMs);
        return new TimedCandidateState(
                clearsCandidate ? null : state.packageName,
                clearsCandidate || state.known,
                clearsCandidate ? eventTimestampMs : state.candidateEventTimestampMs,
                state.latestForegroundEventTimestampMs, state.latestForegroundPackageName,
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
                true,
                clearsCandidate ? eventTimestampMs : state.candidateEventTimestampMs,
                state.latestForegroundEventTimestampMs, state.latestForegroundPackageName,
                eventTimestampMs, state.latestBackgroundEventTimestamps
        );
    }

    static TimedCandidateState pruneTimedEvidence(
            TimedCandidateState state,
            long oldestRetainedTimestampMs
    ) {
        Map<String, Long> retained = new HashMap<>();
        for (Map.Entry<String, Long> entry
                : state.latestBackgroundEventTimestamps.entrySet()) {
            if (entry.getValue() >= oldestRetainedTimestampMs
                    || (samePackage(entry.getKey(), state.latestForegroundPackageName)
                    && entry.getValue() >= state.latestForegroundEventTimestampMs)) {
                // Retain the close of the newest resume even outside the query
                // overlap, so widening a later query cannot resurrect that resume.
                retained.put(entry.getKey(), entry.getValue());
            }
        }
        return new TimedCandidateState(
                state.packageName, state.known, state.candidateEventTimestampMs,
                state.latestForegroundEventTimestampMs, state.latestForegroundPackageName,
                state.latestBoundaryEventTimestampMs, retained
        );
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
        final boolean known;
        final long candidateEventTimestampMs;
        final long latestForegroundEventTimestampMs;
        final String latestForegroundPackageName;
        final long latestBoundaryEventTimestampMs;
        final Map<String, Long> latestBackgroundEventTimestamps;

        private TimedCandidateState(
                String packageName,
                boolean known,
                long candidateEventTimestampMs,
                long latestForegroundEventTimestampMs,
                String latestForegroundPackageName,
                long latestBoundaryEventTimestampMs,
                Map<String, Long> latestBackgroundEventTimestamps
        ) {
            this.packageName = packageName;
            this.known = known;
            this.candidateEventTimestampMs = candidateEventTimestampMs;
            this.latestForegroundEventTimestampMs = latestForegroundEventTimestampMs;
            this.latestForegroundPackageName = latestForegroundPackageName;
            this.latestBoundaryEventTimestampMs = latestBoundaryEventTimestampMs;
            this.latestBackgroundEventTimestamps =
                    new HashMap<>(latestBackgroundEventTimestamps);
        }
    }
}
