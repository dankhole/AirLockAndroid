package com.dankhole.airlockandroid;

final class RequiredAccessPolicy {
    static final int REQUIREMENT_COUNT = 3;

    private RequiredAccessPolicy() {
    }

    static int readyCount(boolean usageAccess, boolean overlayAccess, boolean notifications) {
        int count = 0;
        if (usageAccess) {
            count++;
        }
        if (overlayAccess) {
            count++;
        }
        if (notifications) {
            count++;
        }
        return count;
    }

    static boolean isComplete(
            boolean usageAccess,
            boolean overlayAccess,
            boolean notifications
    ) {
        return readyCount(usageAccess, overlayAccess, notifications) == REQUIREMENT_COUNT;
    }
}
