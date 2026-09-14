package com.dankhole.airlockandroid;

import android.util.Log;

import java.util.concurrent.atomic.AtomicReference;

/** One-shot worker delay for deterministic debug-only foreground races. */
final class MonitoringTestHooks {
    private static final AtomicReference<DelayedResult> NEXT_RESULT = new AtomicReference<>();

    private MonitoringTestHooks() {
    }

    static boolean delayNextForegroundResult(String targetPackage, int delayMs, String token) {
        if (targetPackage == null || targetPackage.trim().isEmpty()
                || delayMs < 1 || delayMs > 8_000
                || token == null || !token.matches("[A-Za-z0-9._-]{1,100}")) {
            return false;
        }
        return NEXT_RESULT.compareAndSet(null, new DelayedResult(targetPackage, delayMs, token));
    }

    static void reset() {
        NEXT_RESULT.set(null);
    }

    static void afterForegroundQuery(String candidatePackage) {
        DelayedResult delay = NEXT_RESULT.get();
        if (delay == null || !delay.targetPackage.equals(candidatePackage)
                || !NEXT_RESULT.compareAndSet(delay, null)) {
            return;
        }
        Log.d("AirLockMonitor", "debug foreground result delay started token=" + delay.token
                + " delay_ms=" + delay.delayMs + " package=" + candidatePackage);
        try {
            Thread.sleep(delay.delayMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            Log.d("AirLockMonitor", "debug foreground result delay completed token=" + delay.token);
        }
    }

    private static final class DelayedResult {
        final String targetPackage;
        final int delayMs;
        final String token;

        DelayedResult(String targetPackage, int delayMs, String token) {
            this.targetPackage = targetPackage;
            this.delayMs = delayMs;
            this.token = token;
        }
    }
}
