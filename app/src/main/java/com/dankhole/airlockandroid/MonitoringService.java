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
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MonitoringService extends Service {
    public static final String ACTION_START = "com.dankhole.airlockandroid.START";
    public static final String ACTION_STOP = "com.dankhole.airlockandroid.STOP";

    private static final String CHANNEL_ID = "airlock_monitoring";
    private static final int NOTIFICATION_ID = 42;
    private static final long POLL_INTERVAL_MS = 1000L;
    private static final long OVERLAY_STICKY_MS = 5 * 60 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private View overlayView;
    private String overlayPackageName;
    private String lastForegroundPackage;
    private long lastTickElapsedMs;
    private long keepOverlayUntilMs;

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
        lastTickElapsedMs = SystemClock.elapsedRealtime();
        handler.post(pollRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopMonitoring();
            return START_NOT_STICKY;
        }
        if (!AndroidPermissions.hasUsageAccess(this)) {
            stopMonitoring();
            return START_NOT_STICKY;
        }
        if (!AndroidPermissions.hasOverlayAccess(this)) {
            stopMonitoring();
            return START_NOT_STICKY;
        }
        if (!Preferences.hasAccountabilityNumber(this)) {
            stopMonitoring();
            return START_NOT_STICKY;
        }
        if (!Preferences.hasMasterPin(this)) {
            stopMonitoring();
            return START_NOT_STICKY;
        }
        if (!Preferences.hasLimitedApps(this)) {
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
        if (!AndroidPermissions.hasUsageAccess(this)) {
            stopMonitoring();
            return;
        }
        if (!AndroidPermissions.hasOverlayAccess(this)) {
            stopMonitoring();
            return;
        }
        if (!Preferences.hasAccountabilityNumber(this)) {
            stopMonitoring();
            return;
        }
        if (!Preferences.hasMasterPin(this)) {
            stopMonitoring();
            return;
        }
        if (!Preferences.hasLimitedApps(this)) {
            stopMonitoring();
            return;
        }

        long now = System.currentTimeMillis();
        long elapsedNow = SystemClock.elapsedRealtime();
        String foregroundPackage = findForegroundPackage(now);
        Set<String> selectedPackages = Preferences.selectedPackages(this);
        reconcileDailyUsageFromSystemStats(selectedPackages, now);

        if (foregroundPackage != null
                && selectedPackages.contains(foregroundPackage)
                && foregroundPackage.equals(lastForegroundPackage)) {
            Preferences.addUsageTodayMs(this, foregroundPackage, elapsedNow - lastTickElapsedMs);
        }

        lastForegroundPackage = foregroundPackage;
        lastTickElapsedMs = elapsedNow;

        if (foregroundPackage == null || !selectedPackages.contains(foregroundPackage)) {
            if (shouldKeepExistingOverlay(now, foregroundPackage)) {
                return;
            }
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
        String candidate = lastForegroundPackage;
        boolean sawEvent = false;
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
                sawEvent = true;
                candidate = eventPackageName;
            } else if (type == UsageEvents.Event.MOVE_TO_BACKGROUND
                    || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && type == UsageEvents.Event.ACTIVITY_PAUSED)) {
                sawEvent = true;
                if (eventPackageName.equals(candidate)) {
                    candidate = null;
                }
            }
        }

        if (candidate != null) {
            return candidate;
        }
        if (sawEvent) {
            return null;
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

    private void reconcileDailyUsageFromSystemStats(Set<String> selectedPackages, long now) {
        if (selectedPackages.isEmpty()) {
            return;
        }
        UsageStatsManager usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usageStatsManager == null) {
            return;
        }

        Map<String, UsageStats> stats;
        try {
            stats = usageStatsManager.queryAndAggregateUsageStats(startOfTodayMs(now), now);
        } catch (SecurityException ignored) {
            return;
        }
        if (stats == null || stats.isEmpty()) {
            return;
        }

        for (String packageName : selectedPackages) {
            UsageStats stat = stats.get(packageName);
            if (stat != null) {
                Preferences.reconcileUsageTodayMs(this, packageName, stat.getTotalTimeInForeground());
            }
        }
    }

    private long startOfTodayMs(long now) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(now);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private void showOverlay(String packageName) {
        if (overlayView != null && packageName.equals(overlayPackageName)) {
            keepOverlayUntilMs = System.currentTimeMillis() + OVERLAY_STICKY_MS;
            return;
        }
        hideOverlay();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return;
        }

        overlayPackageName = packageName;
        keepOverlayUntilMs = System.currentTimeMillis() + OVERLAY_STICKY_MS;
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        params.gravity = Gravity.CENTER;

        try {
            windowManager.addView(overlayView, params);
            EditText firstInput = overlayView.findViewWithTag("minutes_input");
            if (firstInput != null) {
                showKeyboard(firstInput);
            }
        } catch (RuntimeException ignored) {
            overlayView = null;
            overlayPackageName = null;
        }
    }

    private View buildOverlay(String packageName) {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.setBackgroundColor(UiStyle.COLOR_OVERLAY_BACKGROUND);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(UiStyle.COLOR_OVERLAY_BACKGROUND);
        UiStyle.applySystemInsetsPadding(root, 20, 24, 20, 24);
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout card = UiStyle.overlayCard(this);
        root.addView(card, UiStyle.fullWidth(this, 0));

        String appLabel = appLabel(packageName);
        long usedMinutes = Preferences.getUsageTodayMs(this, packageName) / 60_000L;
        int limitMinutes = Preferences.dailyLimitMinutes(this, packageName);

        card.addView(UiStyle.overlayTitle(this, "Time limit reached"), UiStyle.fullWidth(this, 8));

        TextView detail = UiStyle.overlayBody(
                this,
                appLabel + "\nUsed " + usedMinutes + " of " + limitMinutes + " minutes today."
        );
        detail.setGravity(Gravity.CENTER);
        card.addView(detail, UiStyle.fullWidth(this, 18));

        TextView requestStatus = UiStyle.statusText(this);
        requestStatus.setText("Extra time requires an approval code from the accountability number.");
        UiStyle.setStatus(requestStatus, UiStyle.STATUS_WARNING);
        card.addView(requestStatus, UiStyle.fullWidth(this, 14));

        card.addView(UiStyle.overlayStepLabel(this, "1. Request minutes"), UiStyle.fullWidth(this, 6));
        EditText minutesInput = new EditText(this);
        minutesInput.setTag("minutes_input");
        minutesInput.setHint("Extra minutes");
        minutesInput.setInputType(InputType.TYPE_CLASS_PHONE);
        minutesInput.setSingleLine(true);
        minutesInput.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        minutesInput.setText("5");
        minutesInput.setSelectAllOnFocus(true);
        minutesInput.setGravity(Gravity.CENTER);
        UiStyle.styleOverlayInput(minutesInput, false);
        minutesInput.setOnClickListener(v -> {
            showKeyboard(minutesInput);
            scrollToInput(scrollView, minutesInput);
        });
        minutesInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                showKeyboard(minutesInput);
                scrollToInput(scrollView, minutesInput);
            }
        });
        card.addView(minutesInput, UiStyle.fullWidth(this, 12));

        card.addView(UiStyle.overlayStepLabel(this, "2. Text request code"), UiStyle.fullWidth(this, 6));
        Button textCodeButton = UiStyle.primaryButton(this, "Text Request Code");
        card.addView(textCodeButton, UiStyle.buttonParams(this));

        card.addView(UiStyle.overlayStepLabel(this, "3. Enter approval code"), UiStyle.fullWidth(this, 6));
        EditText codeInput = new EditText(this);
        codeInput.setTag("code_input");
        codeInput.setHint("Approval code");
        codeInput.setInputType(InputType.TYPE_CLASS_PHONE);
        codeInput.setSingleLine(true);
        codeInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        codeInput.setGravity(Gravity.CENTER);
        UiStyle.styleOverlayInput(codeInput, false);
        codeInput.setOnClickListener(v -> {
            showKeyboard(codeInput);
            scrollToInput(scrollView, codeInput);
        });
        codeInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                showKeyboard(codeInput);
                scrollToInput(scrollView, codeInput);
            }
        });
        card.addView(codeInput, UiStyle.fullWidth(this, 12));

        TextView errorText = UiStyle.statusText(this);
        errorText.setVisibility(View.GONE);
        card.addView(errorText, UiStyle.fullWidth(this, 12));

        card.addView(UiStyle.overlayStepLabel(this, "4. Unlock"), UiStyle.fullWidth(this, 6));
        Button unlockButton = UiStyle.primaryButton(this, "Unlock Extra Time");
        card.addView(unlockButton, UiStyle.buttonParams(this));

        Button leaveButton = UiStyle.overlaySecondaryButton(this, "Leave App");
        card.addView(leaveButton, UiStyle.buttonParams(this));

        textCodeButton.setOnClickListener(v -> {
            int requestedMinutes = parsePositiveInt(minutesInput);
            keepOverlayUntilMs = System.currentTimeMillis() + OVERLAY_STICKY_MS;
            if (requestedMinutes <= 0) {
                UiStyle.styleOverlayInput(minutesInput, true);
                showOverlayError(errorText, "Enter requested minutes greater than 0 before requesting a code.");
                return;
            }
            UiStyle.styleOverlayInput(minutesInput, false);
            hideOverlayError(errorText);
            if (composeCodeSms(packageName, requestedMinutes)) {
                requestStatus.setText("Request code sent for " + requestedMinutes
                        + " minutes. Enter the approval code after it is sent back.");
                UiStyle.setStatus(requestStatus, UiStyle.STATUS_READY);
                showKeyboard(codeInput);
            }
        });

        unlockButton.setOnClickListener(v -> {
            String entered = codeInput.getText().toString().trim();
            keepOverlayUntilMs = System.currentTimeMillis() + OVERLAY_STICKY_MS;
            if (entered.isEmpty()) {
                UiStyle.styleOverlayInput(codeInput, true);
                showOverlayError(errorText, "Enter the approval code before unlocking. The app remains blocked.");
                return;
            }
            int approvedMinutes = Preferences.consumeApprovalCodeMinutesIfValid(this, packageName, entered);
            if (approvedMinutes > 0) {
                Preferences.grantExtraTime(this, packageName, approvedMinutes);
                hideOverlay();
                Toast.makeText(this, approvedMinutes + " extra minutes granted", Toast.LENGTH_SHORT).show();
            } else {
                UiStyle.styleOverlayInput(codeInput, true);
                showOverlayError(errorText, "Invalid or expired approval code. Request a new code if needed.");
            }
        });

        leaveButton.setOnClickListener(v -> {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
            hideOverlay();
        });

        return scrollView;
    }

    private void showOverlayError(TextView errorText, String message) {
        errorText.setText("REQUIRED: " + message);
        UiStyle.setStatus(errorText, UiStyle.STATUS_REQUIRED);
        errorText.setVisibility(View.VISIBLE);
    }

    private void hideOverlayError(TextView errorText) {
        errorText.setText("");
        errorText.setVisibility(View.GONE);
    }

    private void scrollToInput(ScrollView scrollView, View input) {
        input.postDelayed(() -> {
            Rect bounds = new Rect();
            input.getDrawingRect(bounds);
            scrollView.offsetDescendantRectToMyCoords(input, bounds);
            int targetY = Math.max(0, bounds.top - UiStyle.dp(this, 80));
            scrollView.smoothScrollTo(0, targetY);
        }, 250);
    }

    private void showKeyboard(EditText input) {
        input.post(() -> {
            input.requestFocus();
            InputMethodManager inputMethodManager =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (inputMethodManager != null) {
                inputMethodManager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private boolean shouldKeepExistingOverlay(long now, String foregroundPackage) {
        if (overlayView == null || overlayPackageName == null) {
            return false;
        }
        if (foregroundPackage != null
                && foregroundPackage.equals(getPackageName())) {
            return Preferences.isOverLimit(this, overlayPackageName)
                    && !Preferences.isTemporarilyUnlocked(this, overlayPackageName);
        }
        if (now > keepOverlayUntilMs) {
            return false;
        }
        if (foregroundPackage != null
                && !foregroundPackage.equals(getPackageName())
                && !foregroundPackage.equals(overlayPackageName)) {
            return false;
        }
        return Preferences.isOverLimit(this, overlayPackageName)
                && !Preferences.isTemporarilyUnlocked(this, overlayPackageName);
    }

    private int parsePositiveInt(EditText input) {
        try {
            int parsed = Integer.parseInt(input.getText().toString().trim());
            return parsed > 0 ? parsed : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private boolean composeCodeSms(String packageName, int requestedMinutes) {
        String phone = Preferences.prefs(this)
                .getString(Preferences.KEY_ACCOUNTABILITY_NUMBER, "")
                .trim();
        if (phone.isEmpty()) {
            Toast.makeText(this, "Add an accountability number in settings", Toast.LENGTH_LONG).show();
            return false;
        }

        String requestCode = Preferences.createRequestCode(this, packageName, requestedMinutes);
        String message = "AirLock Android extra-time request for " + appLabel(packageName)
                + ". Requested minutes: " + requestedMinutes
                + ". Request code: " + requestCode
                + ". Convert with the temporary test rule and send back the approval code only if this is OK.";
        Intent intent = new Intent(Intent.ACTION_SENDTO);
        intent.setData(Uri.parse("smsto:" + Uri.encode(phone)));
        intent.putExtra("sms_body", message);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
            return true;
        } catch (RuntimeException ignored) {
            Toast.makeText(this, "No SMS app available", Toast.LENGTH_SHORT).show();
            return false;
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
        keepOverlayUntilMs = 0L;
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

    private String appLabel(String packageName) {
        PackageManager packageManager = getPackageManager();
        try {
            CharSequence label = packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(packageName, 0)
            );
            return label == null ? packageName : label.toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            return packageName;
        }
    }
}
