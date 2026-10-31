package com.dankhole.airlockandroid;

import java.util.ArrayDeque;
import java.util.Iterator;

/** Pure action safety/budget policy. Times are monotonic, never wall-clock time. */
final class ContentActionPolicy {
    static final int MIN_FRESH_AUTHORITY_SDK = 33;
    static final long EVENT_MAX_AGE_MS = 1_500L;
    static final long WORK_TIMEOUT_MS = 1_500L;
    static final long EVIDENCE_MAX_AGE_MS = 250L;
    static final long ACTION_COOLDOWN_MS = 2_000L;
    static final long ACTION_WINDOW_MS = 60_000L;
    static final int MAX_ACTIONS_PER_WINDOW = 3;
    private static final int MAX_LATCHES = 8;

    static final class Match {
        final String packageName;
        final int windowId;
        final ContentFilter filter;
        final long generation;
        final long capturedElapsedMs;

        Match(String packageName, int windowId, ContentFilter filter,
                long generation, long capturedElapsedMs) {
            this.packageName = packageName;
            this.windowId = windowId;
            this.filter = filter;
            this.generation = generation;
            this.capturedElapsedMs = capturedElapsedMs;
        }
    }

    private final ArrayDeque<Long> actions = new ArrayDeque<>();
    private final ArrayDeque<Match> latchedMatches = new ArrayDeque<>();

    static boolean supportsFreshAuthority(int sdk) {
        return sdk >= MIN_FRESH_AUTHORITY_SDK;
    }

    static boolean hasFreshAuthority(int sdk, boolean disableSucceeded,
            boolean cacheEnabled, boolean clearSucceeded) {
        return supportsFreshAuthority(sdk) && disableSucceeded && !cacheEnabled && clearSucceeded;
    }

    static boolean shouldReadDescription(String packageName, boolean visible,
            boolean positiveBounds, boolean password, boolean editable) {
        return ContentFilter.FACEBOOK_REELS.packageName().equals(packageName)
                && visible && positiveBounds && !password && !editable;
    }

    static boolean isFresh(long startedMs, long nowMs, long maximumAgeMs) {
        return startedMs >= 0L && nowMs >= startedMs && nowMs - startedMs <= maximumAgeMs;
    }

    static boolean sameTarget(Match first, Match fresh, long currentGeneration, long nowMs) {
        return first != null && fresh != null
                && first.packageName != null && first.packageName.equals(fresh.packageName)
                && first.windowId >= 0 && first.windowId == fresh.windowId
                && first.filter != null && first.filter == fresh.filter
                && first.generation == currentGeneration && fresh.generation == currentGeneration
                && isFresh(first.capturedElapsedMs, nowMs, WORK_TIMEOUT_MS)
                && isFresh(fresh.capturedElapsedMs, nowMs, EVIDENCE_MAX_AGE_MS);
    }

    /** Consume the budget before the Binder call, including a failed Back attempt. */
    synchronized boolean reserve(Match first, Match fresh, long generation,
            long nowMs, boolean currentGatesAllowAction) {
        if (!currentGatesAllowAction || !sameTarget(first, fresh, generation, nowMs)) {
            return false;
        }
        if (!canConsider(fresh, nowMs)) return false;
        actions.addLast(nowMs);
        if (latchedMatches.size() == MAX_LATCHES) {
            latchedMatches.removeFirst();
        }
        latchedMatches.addLast(fresh);
        return true;
    }

    /** Cheap preflight avoids another traversal/handler discovery for a fused target. */
    synchronized boolean canConsider(Match target, long nowMs) {
        if (target == null || target.packageName == null || target.windowId < 0
                || target.filter == null || !isFresh(target.capturedElapsedMs, nowMs,
                        WORK_TIMEOUT_MS)) return false;
        while (!actions.isEmpty() && nowMs - actions.peekFirst() >= ACTION_WINDOW_MS) {
            actions.removeFirst();
        }
        if (actions.size() >= MAX_ACTIONS_PER_WINDOW
                || (!actions.isEmpty() && nowMs - actions.peekLast() < ACTION_COOLDOWN_MS)) {
            return false;
        }
        for (Match previous : latchedMatches) {
            if (previous.windowId == target.windowId
                    && previous.packageName.equals(target.packageName)
                    && previous.filter == target.filter) {
                return false;
            }
        }
        return true;
    }

    /** Only a complete, fresh non-feed scan can rearm this window, not another event. */
    synchronized void observedClear(String packageName, int windowId) {
        Iterator<Match> iterator = latchedMatches.iterator();
        while (iterator.hasNext()) {
            Match previous = iterator.next();
            if (previous.windowId == windowId && previous.packageName.equals(packageName)) {
                iterator.remove();
            }
        }
    }
}
