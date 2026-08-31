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

    static boolean shouldApplyLifecycleEvent(
            long eventTimestampMs,
            int type,
            String packageName,
            long latestProcessedTimestampMs,
            Set<String> processedKeysAtLatestTimestamp,
            int sdkInt
    ) {
        if (packageName == null || !isLifecycleEvent(type, sdkInt)) {
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
        return new TimedCandidateState(
                null,
                false,
                Long.MIN_VALUE,
                Long.MIN_VALUE,
                new HashMap<>()
        );
    }

    static TimedCandidateState knownTimedCandidate(
            String packageName,
            long candidateEventTimestampMs,
            long latestForegroundEventTimestampMs,
            Map<String, Long> latestBackgroundEventTimestamps
    ) {
        return new TimedCandidateState(
                packageName,
                true,
                candidateEventTimestampMs,
                latestForegroundEventTimestampMs,
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
        if (packageName == null || !isLifecycleEvent(type, sdkInt)) {
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
            if (eventTimestampMs < latestPackageBackgroundMs) {
                // The resume no longer names the current candidate, but it still
                // superseded every earlier foreground event chronologically. If
                // it also superseded the retained candidate, the later matching
                // background proves that no foreground candidate remains.
                boolean supersededCandidate = !state.known
                        || eventTimestampMs >= state.candidateEventTimestampMs;
                return new TimedCandidateState(
                        supersededCandidate ? null : state.packageName,
                        supersededCandidate || state.known,
                        supersededCandidate
                                ? latestPackageBackgroundMs
                                : state.candidateEventTimestampMs,
                        Math.max(state.latestForegroundEventTimestampMs, eventTimestampMs),
                        backgroundTimestamps
                );
            }
            return new TimedCandidateState(
                    packageName,
                    true,
                    eventTimestampMs,
                    Math.max(state.latestForegroundEventTimestampMs, eventTimestampMs),
                    backgroundTimestamps
            );
        }

        Long previousBackgroundMs = backgroundTimestamps.get(packageName);
        if (previousBackgroundMs == null || eventTimestampMs > previousBackgroundMs) {
            backgroundTimestamps.put(packageName, eventTimestampMs);
        }
        boolean clearsCandidate = !state.known
                || (samePackage(state.packageName, packageName)
                && eventTimestampMs >= state.candidateEventTimestampMs);
        if (!clearsCandidate) {
            return new TimedCandidateState(
                    state.packageName,
                    state.known,
                    state.candidateEventTimestampMs,
                    state.latestForegroundEventTimestampMs,
                    backgroundTimestamps
            );
        }
        return new TimedCandidateState(
                null,
                true,
                eventTimestampMs,
                state.latestForegroundEventTimestampMs,
                backgroundTimestamps
        );
    }

    static TimedCandidateState seedTimedCandidate(
            TimedCandidateState state,
            String packageName
    ) {
        if (state.known || packageName == null) {
            return state;
        }
        return new TimedCandidateState(
                packageName,
                true,
                Long.MIN_VALUE,
                state.latestForegroundEventTimestampMs,
                state.latestBackgroundEventTimestamps
        );
    }

    static TimedCandidateState pruneTimedEvidence(
            TimedCandidateState state,
            long oldestRetainedTimestampMs
    ) {
        Map<String, Long> retained = new HashMap<>();
        for (Map.Entry<String, Long> entry
                : state.latestBackgroundEventTimestamps.entrySet()) {
            if (entry.getValue() >= oldestRetainedTimestampMs) {
                retained.put(entry.getKey(), entry.getValue());
            }
        }
        return new TimedCandidateState(
                state.packageName,
                state.known,
                state.candidateEventTimestampMs,
                state.latestForegroundEventTimestampMs,
                retained
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

    static boolean shouldSeedFromUsageSummary(
            CandidateState state,
            boolean runSanityCheck
    ) {
        return runSanityCheck && !state.known;
    }

    static boolean shouldSeedFromUsageSummary(
            TimedCandidateState state,
            boolean runSanityCheck
    ) {
        return runSanityCheck && !state.known;
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
        final Map<String, Long> latestBackgroundEventTimestamps;

        private TimedCandidateState(
                String packageName,
                boolean known,
                long candidateEventTimestampMs,
                long latestForegroundEventTimestampMs,
                Map<String, Long> latestBackgroundEventTimestamps
        ) {
            this.packageName = packageName;
            this.known = known;
            this.candidateEventTimestampMs = candidateEventTimestampMs;
            this.latestForegroundEventTimestampMs = latestForegroundEventTimestampMs;
            this.latestBackgroundEventTimestamps =
                    new HashMap<>(latestBackgroundEventTimestamps);
        }
    }
}
