package com.dankhole.airlockandroid;

/** Time bounds for evidence that can authorize a system-wide blocking window. */
final class ForegroundPollPolicy {
    static final long OVERLAP_MS = 10_000L;
    static final long LOOKBACK_MS = 5 * 60_000L;
    static final long MAX_RESULT_AGE_MS = 2_000L;

    private ForegroundPollPolicy() {
    }

    static long queryStartMs(
            long now,
            long previousEnd,
            long evidenceStart,
            boolean needsLookback
    ) {
        long start = needsLookback || previousEnd <= 0L || previousEnd > now
                ? now - LOOKBACK_MS
                : Math.min(now - OVERLAP_MS, previousEnd - OVERLAP_MS);
        // Cover the interval since the last query, including bounded scheduler gaps.
        // Never read across the current boot/suspension/clock-change boundary.
        return Math.max(0L, Math.max(evidenceStart, Math.max(now - LOOKBACK_MS, start)));
    }

    static boolean isResultFresh(long startedElapsedMs, long nowElapsedMs) {
        long age = nowElapsedMs - startedElapsedMs;
        return age >= 0L && age <= MAX_RESULT_AGE_MS;
    }
}
