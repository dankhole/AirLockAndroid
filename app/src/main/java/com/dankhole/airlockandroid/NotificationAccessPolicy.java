package com.dankhole.airlockandroid;

final class NotificationAccessPolicy {
    private NotificationAccessPolicy() {
    }

    static boolean isVisible(
            boolean runtimePermissionGranted,
            boolean appNotificationsEnabled,
            boolean monitoringChannelBlocked,
            boolean channelGroupBlocked
    ) {
        return runtimePermissionGranted
                && appNotificationsEnabled
                && !monitoringChannelBlocked
                && !channelGroupBlocked;
    }
}
