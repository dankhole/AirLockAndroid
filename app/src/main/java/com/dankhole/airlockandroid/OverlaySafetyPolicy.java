package com.dankhole.airlockandroid;

/** In-memory lifetime of one prepared/attached blocker. No Android queries or storage. */
final class OverlaySafetyPolicy {
    static final long CHECK_INTERVAL_MS = 200L;
    static final long ATTACHMENT_MAX_AGE_MS = 500L;
    static final long INITIAL_FOCUS_GRACE_MS = 500L;
    static final long FAST_CONFIRMATION_WINDOW_MS = 3_000L;

    private final long preparedElapsedMs;
    private long attachedElapsedMs = -1L;
    private long evidenceStartedElapsedMs = -1L;
    private boolean hadWindowFocus;

    OverlaySafetyPolicy(long preparedElapsedMs) {
        this.preparedElapsedMs = preparedElapsedMs;
    }

    boolean needsFastConfirmation(long nowElapsedMs) {
        long age = nowElapsedMs - preparedElapsedMs;
        return age >= 0L && age < FAST_CONFIRMATION_WINDOW_MS;
    }

    boolean canAttach(long queryStartedElapsedMs, long nowElapsedMs) {
        long age = nowElapsedMs - queryStartedElapsedMs;
        return queryStartedElapsedMs >= preparedElapsedMs
                && age >= 0L && age <= ATTACHMENT_MAX_AGE_MS;
    }

    void attached(long queryStartedElapsedMs, long nowElapsedMs) {
        attachedElapsedMs = nowElapsedMs;
        evidenceStartedElapsedMs = queryStartedElapsedMs;
    }

    void confirmForeground(long queryStartedElapsedMs) {
        evidenceStartedElapsedMs = Math.max(evidenceStartedElapsedMs, queryStartedElapsedMs);
    }

    void recordFocus(boolean hasFocus) {
        hadWindowFocus |= hasFocus;
    }

    boolean focusUnavailable(long nowElapsedMs, boolean hasFocus) {
        recordFocus(hasFocus);
        return !hasFocus && (hadWindowFocus
                || nowElapsedMs - attachedElapsedMs >= INITIAL_FOCUS_GRACE_MS);
    }

    boolean evidenceExpired(long nowElapsedMs) {
        return !ForegroundPollPolicy.isResultFresh(evidenceStartedElapsedMs, nowElapsedMs);
    }
}
