package com.dankhole.airlockandroid;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

final class AndroidPermissions {
    private AndroidPermissions() {
    }

    static boolean hasUsageAccess(Context context) {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) {
            return false;
        }
        int mode;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mode = appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(),
                        context.getPackageName()
                );
            } else {
                mode = appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(),
                        context.getPackageName()
                );
            }
        } catch (RuntimeException ignored) {
            return false;
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    static boolean hasOverlayAccess(Context context) {
        try {
            return Settings.canDrawOverlays(context);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean hasNotificationAccess(Context context) {
        boolean runtimePermissionGranted = hasNotificationRuntimePermission(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return false;
        }
        try {
            boolean appNotificationsEnabled = manager.areNotificationsEnabled();
            NotificationChannel channel = manager.getNotificationChannel(
                    MonitoringService.CHANNEL_ID
            );
            if (channel == null) {
                return NotificationAccessPolicy.isVisible(
                        runtimePermissionGranted,
                        appNotificationsEnabled,
                        false,
                        false
                );
            }
            boolean channelBlocked =
                    channel.getImportance() == NotificationManager.IMPORTANCE_NONE;
            boolean groupBlocked = false;
            String groupId = channel.getGroup();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && groupId != null) {
                NotificationChannelGroup group = manager.getNotificationChannelGroup(groupId);
                groupBlocked = group != null && group.isBlocked();
            }
            return NotificationAccessPolicy.isVisible(
                    runtimePermissionGranted,
                    appNotificationsEnabled,
                    channelBlocked,
                    groupBlocked
            );
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean hasNotificationRuntimePermission(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean areAppNotificationsEnabled(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return false;
        }
        try {
            return manager.areNotificationsEnabled();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean isBackgroundRestricted(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return false;
        }
        ActivityManager activityManager = context.getSystemService(ActivityManager.class);
        if (activityManager == null) {
            return false;
        }
        try {
            return activityManager.isBackgroundRestricted();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
