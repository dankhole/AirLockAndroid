package com.dankhole.airlockandroid;

import android.annotation.SuppressLint;
import android.app.usage.UsageEvents;
import android.os.Build;

import java.util.Set;

@SuppressLint("InlinedApi")
final class ForegroundEventPolicy {
    private ForegroundEventPolicy() {
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

    static boolean samePackage(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
