package com.dankhole.airlockandroid;

final class ApprovalCodePolicy {
    private ApprovalCodePolicy() {
    }

    static int approvedMinutes(
            boolean codeIsPending,
            long expiresAtMs,
            int storedRequestedMinutes,
            long nowMs
    ) {
        return codeIsPending && expiresAtMs > nowMs && storedRequestedMinutes > 0
                ? storedRequestedMinutes
                : Preferences.APPROVAL_REDEMPTION_INVALID;
    }

    static long unlockUntilMs(long nowMs, int approvedMinutes) {
        return nowMs + Math.max(1, approvedMinutes) * 60_000L;
    }

    static String approvalCodeForNormalizedRequest(String normalizedRequestCode) {
        StringBuilder builder = new StringBuilder(normalizedRequestCode.length());
        for (int i = 0; i < normalizedRequestCode.length(); i++) {
            int digit = normalizedRequestCode.charAt(i) - '0';
            builder.append((digit + 5) % 10);
        }
        return builder.toString();
    }
}
