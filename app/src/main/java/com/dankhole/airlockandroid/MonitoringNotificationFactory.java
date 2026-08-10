package com.dankhole.airlockandroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

final class MonitoringNotificationFactory {
    private static final String LEGACY_CHANNEL_ID = "airlock_monitoring";

    private MonitoringNotificationFactory() {
    }

    static Notification build(Context context, String channelId, String monitoringIssue) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    context.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(context.getString(R.string.notification_channel_description));
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.setVibrationPattern(null);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
            if (!LEGACY_CHANNEL_ID.equals(channelId)) {
                manager.deleteNotificationChannel(LEGACY_CHANNEL_ID);
            }
        }

        PendingIntent openAirLock = PendingIntent.getActivity(
                context,
                0,
                new Intent(context, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        boolean emergencyPaused = Preferences.isEmergencyPauseActive(context);
        boolean starting = monitoringIssue.equals(
                context.getString(R.string.monitoring_issue_starting)
        ) && !emergencyPaused;
        boolean needsAttention = !monitoringIssue.isEmpty() && !emergencyPaused && !starting;

        return new Notification.Builder(context, channelId)
                .setContentTitle(emergencyPaused
                        ? context.getString(R.string.notification_emergency_title)
                        : starting
                        ? context.getString(R.string.notification_starting_title)
                        : needsAttention
                        ? context.getString(R.string.notification_attention_title)
                        : context.getString(R.string.notification_on_title))
                .setContentText(emergencyPaused
                        ? context.getString(R.string.notification_emergency_text)
                        : starting
                        ? context.getString(R.string.notification_starting_text)
                        : needsAttention
                        ? monitoringIssue
                        : context.getString(R.string.notification_on_text))
                .setSmallIcon(R.drawable.ic_stat_lock_clock)
                .setContentIntent(openAirLock)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setDefaults(0)
                .setSound(null)
                .setVibrate(null)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setShowWhen(false)
                .build();
    }
}
