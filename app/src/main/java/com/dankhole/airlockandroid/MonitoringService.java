package com.dankhole.airlockandroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MonitoringService extends Service {
    public static final String ACTION_START = "com.dankhole.airlockandroid.START";
    public static final String ACTION_STOP = "com.dankhole.airlockandroid.STOP";

    private static final String CHANNEL_ID = "airlock_monitoring";
    private static final int NOTIFICATION_ID = 42;
    private static final long POLL_INTERVAL_MS = 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private View overlayView;
    private String overlayPackageName;
    private String lastForegroundPackage;
    private long lastTickMs;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            poll();
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        startForeground(NOTIFICATION_ID, buildNotification());
        lastTickMs = System.currentTimeMillis();
        handler.post(pollRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopMonitoring();
            return START_NOT_STICKY;
        }
        Preferences.prefs(this).edit().putBoolean(Preferences.KEY_ENABLED, true).apply();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(pollRunnable);
        hideOverlay();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void poll() {
        SharedPreferences preferences = Preferences.prefs(this);
        if (!preferences.getBoolean(Preferences.KEY_ENABLED, false)) {
            stopMonitoring();
            return;
        }

        long now = System.currentTimeMillis();
        String foregroundPackage = findForegroundPackage(now);
        Set<String> selectedPackages = Preferences.selectedPackages(this);

        if (foregroundPackage != null
                && selectedPackages.contains(foregroundPackage)
                && foregroundPackage.equals(lastForegroundPackage)) {
            Preferences.addUsageTodayMs(this, foregroundPackage, now - lastTickMs);
        }

        lastForegroundPackage = foregroundPackage;
        lastTickMs = now;

        if (foregroundPackage == null || !selectedPackages.contains(foregroundPackage)) {
            hideOverlay();
            return;
        }

        boolean shouldBlock = Preferences.isOverLimit(this, foregroundPackage)
                && !Preferences.isTemporarilyUnlocked(this, foregroundPackage);
        if (shouldBlock) {
            showOverlay(foregroundPackage);
        } else {
            hideOverlay();
        }
    }

    private String findForegroundPackage(long now) {
        UsageStatsManager usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usageStatsManager == null) {
            return lastForegroundPackage;
        }

        UsageEvents events;
        try {
            events = usageStatsManager.queryEvents(now - 10_000L, now);
        } catch (SecurityException ignored) {
            return lastForegroundPackage;
        }

        UsageEvents.Event event = new UsageEvents.Event();
        String candidate = null;
        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(event);
            int type = event.getEventType();
            String eventPackageName = event.getPackageName();
            if (eventPackageName == null) {
                continue;
            }
            if (type == UsageEvents.Event.MOVE_TO_FOREGROUND
                    || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && type == UsageEvents.Event.ACTIVITY_RESUMED)) {
                candidate = eventPackageName;
            } else if (type == UsageEvents.Event.MOVE_TO_BACKGROUND
                    || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && type == UsageEvents.Event.ACTIVITY_PAUSED)) {
                if (eventPackageName.equals(candidate)) {
                    candidate = null;
                }
            }
        }

        if (candidate != null) {
            return candidate;
        }

        List<UsageStats> stats;
        try {
            stats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    now - 5 * 60_000L,
                    now
            );
        } catch (SecurityException ignored) {
            return lastForegroundPackage;
        }
        if (stats == null || stats.isEmpty()) {
            return lastForegroundPackage;
        }

        UsageStats mostRecent = null;
        for (UsageStats stat : stats) {
            if (mostRecent == null || stat.getLastTimeUsed() > mostRecent.getLastTimeUsed()) {
                mostRecent = stat;
            }
        }
        return mostRecent == null ? lastForegroundPackage : mostRecent.getPackageName();
    }

    private void showOverlay(String packageName) {
        if (overlayView != null && packageName.equals(overlayPackageName)) {
            return;
        }
        hideOverlay();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return;
        }

        overlayPackageName = packageName;
        overlayView = buildOverlay(packageName);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.CENTER;

        try {
            windowManager.addView(overlayView, params);
            EditText codeInput = overlayView.findViewWithTag("code_input");
            if (codeInput != null) {
                codeInput.requestFocus();
                InputMethodManager inputMethodManager =
                        (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                inputMethodManager.showSoftInput(codeInput, InputMethodManager.SHOW_IMPLICIT);
            }
        } catch (RuntimeException ignored) {
            overlayView = null;
            overlayPackageName = null;
        }
    }

    private View buildOverlay(String packageName) {
        int padding = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(Color.rgb(18, 24, 31));

        TextView title = text("Time limit reached", 28, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, fullWidth());

        long usedMinutes = Preferences.getUsageTodayMs(this, packageName) / 60_000L;
        TextView detail = text(
                packageName + "\nUsed " + usedMinutes + " of "
                        + Preferences.dailyLimitMinutes(this) + " minutes today.",
                16,
                false
        );
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(0, dp(12), 0, dp(20));
        root.addView(detail, fullWidth());

        EditText codeInput = new EditText(this);
        codeInput.setTag("code_input");
        codeInput.setHint("Access code");
        codeInput.setSingleLine(true);
        codeInput.setTextColor(Color.WHITE);
        codeInput.setHintTextColor(Color.LTGRAY);
        codeInput.setGravity(Gravity.CENTER);
        root.addView(codeInput, fullWidth());

        Button textCodeButton = overlayButton("Text access code");
        textCodeButton.setOnClickListener(v -> composeCodeSms(packageName));
        root.addView(textCodeButton, fullWidth());

        Button unlockButton = overlayButton("Unlock "
                + Preferences.extraTimeMinutes(this) + " minutes");
        unlockButton.setOnClickListener(v -> {
            String entered = codeInput.getText().toString().trim();
            if (Preferences.consumeCodeIfValid(this, packageName, entered)) {
                Preferences.grantExtraTime(this, packageName);
                hideOverlay();
                Toast.makeText(this, "Extra time granted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Invalid or expired code", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(unlockButton, fullWidth());

        Button leaveButton = overlayButton("Close app");
        leaveButton.setOnClickListener(v -> {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
            hideOverlay();
        });
        root.addView(leaveButton, fullWidth());

        return root;
    }

    private void composeCodeSms(String packageName) {
        String phone = Preferences.prefs(this)
                .getString(Preferences.KEY_ACCOUNTABILITY_NUMBER, "")
                .trim();
        if (phone.isEmpty()) {
            Toast.makeText(this, "Add an accountability number in settings", Toast.LENGTH_LONG).show();
            return;
        }

        String code = Preferences.getOrCreateCode(this, packageName);
        String message = "AirLock Android access code for " + packageName + ": " + code
                + ". Share this only if extra time is OK.";
        Intent intent = new Intent(Intent.ACTION_SENDTO);
        intent.setData(Uri.parse("smsto:" + Uri.encode(phone)));
        intent.putExtra("sms_body", message);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (RuntimeException ignored) {
            Toast.makeText(this, "No SMS app available", Toast.LENGTH_SHORT).show();
        }
    }

    private void hideOverlay() {
        if (overlayView == null) {
            overlayPackageName = null;
            return;
        }
        try {
            windowManager.removeView(overlayView);
        } catch (RuntimeException ignored) {
            // The view may already be detached if Android revoked overlay permission.
        }
        overlayView = null;
        overlayPackageName = null;
    }

    private void stopMonitoring() {
        Preferences.prefs(this).edit().putBoolean(Preferences.KEY_ENABLED, false).apply();
        hideOverlay();
        stopForeground(true);
        stopSelf();
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "AirLock monitoring",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("AirLock is monitoring")
                .setContentText("Selected app limits are active.")
                .setSmallIcon(R.drawable.ic_stat_lock)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(sp);
        textView.setTextColor(Color.WHITE);
        if (bold) {
            textView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        return textView;
    }

    private Button overlayButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        return button;
    }

    private LinearLayout.LayoutParams fullWidth() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(6), 0, dp(6));
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
