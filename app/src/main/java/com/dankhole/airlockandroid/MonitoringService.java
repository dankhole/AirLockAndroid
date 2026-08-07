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
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
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
    private String homePackageName;
    private String lastForegroundPackage;
    private long lastTickElapsedMs;
    private long keepOverlayUntilMs;
    private boolean unlockCelebrationRunning;
    private final Map<String, OverlayFormState> overlayFormStates = new HashMap<>();

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
        hideOverlay(false);
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
        if (unlockCelebrationRunning) {
            keepOverlayUntilMs = System.currentTimeMillis() + OVERLAY_STICKY_MS;
            return;
        }

        long now = System.currentTimeMillis();
        long elapsedNow = SystemClock.elapsedRealtime();
        String foregroundPackage = findForegroundPackage(now);
        Set<String> selectedPackages = Preferences.selectedPackages(this);
        UsageTracker.reconcileTodayFromSystemStats(this, selectedPackages);

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
            hideOverlay(true);
            return;
        }

        boolean shouldBlock = Preferences.isOverLimit(this, foregroundPackage)
                && !Preferences.isTemporarilyUnlocked(this, foregroundPackage);
        if (shouldBlock) {
            showOverlay(foregroundPackage);
        } else {
            hideOverlay(false);
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

    private void showOverlay(String packageName) {
        if (overlayView != null && packageName.equals(overlayPackageName)) {
            keepOverlayUntilMs = System.currentTimeMillis() + OVERLAY_STICKY_MS;
            return;
        }
        hideOverlay(true);

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
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE;
        params.gravity = Gravity.CENTER;

        try {
            windowManager.addView(overlayView, params);
            OverlayFormState formState = overlayFormState(packageName);
            EditText firstInput = overlayView.findViewWithTag(
                    formState.lastRequestedMinutes > 0 ? "code_input" : "minutes_input"
            );
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
        OverlayFormState formState = overlayFormState(packageName);

        card.addView(UiStyle.overlayTitle(this, "The goose says time's up!"), UiStyle.fullWidth(this, 8));

        TextView detail = UiStyle.overlayBody(
                this,
                appLabel + "\nThe goose counted " + usedMinutes + " of "
                        + limitMinutes + " minutes today!"
        );
        detail.setGravity(Gravity.CENTER);
        card.addView(detail, UiStyle.fullWidth(this, 18));

        TextView requestStatus = UiStyle.statusText(this);
        if (formState.lastRequestedMinutes > 0) {
            requestStatus.setText(requestSentMessage(formState.lastRequestedMinutes));
            UiStyle.setStatus(requestStatus, UiStyle.STATUS_READY);
        } else {
            requestStatus.setText("Need more time? The goose needs an approval code first!");
            UiStyle.setStatus(requestStatus, UiStyle.STATUS_WARNING);
        }
        card.addView(requestStatus, UiStyle.fullWidth(this, 14));

        TextView errorText = UiStyle.statusText(this);

        card.addView(UiStyle.overlayStepLabel(this, "1. Ask for minutes!"), UiStyle.fullWidth(this, 6));
        EditText minutesInput = new EditText(this);
        minutesInput.setTag("minutes_input");
        minutesInput.setHint("Extra minutes");
        KeyboardHelper.prepareNumericInput(minutesInput);
        minutesInput.setSingleLine(true);
        minutesInput.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        minutesInput.setText(formState.requestedMinutesText);
        minutesInput.setSelectAllOnFocus(true);
        minutesInput.setGravity(Gravity.CENTER);
        UiStyle.styleOverlayInput(minutesInput, formState.minutesInputError);
        minutesInput.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                formState.requestedMinutesText = editable.toString();
                formState.minutesInputError = false;
                UiStyle.styleOverlayInput(minutesInput, false);
                hideOverlayError(errorText, formState);
            }
        });
        minutesInput.setOnTouchListener((v, event) -> {
            boolean handled = KeyboardHelper.showOnTouch(this, minutesInput, event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                scrollToInput(scrollView, minutesInput);
            }
            return handled;
        });
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

        card.addView(UiStyle.overlayStepLabel(this, "2. Text the goose!"), UiStyle.fullWidth(this, 6));
        Button textCodeButton = UiStyle.primaryButton(this, "Text the Goose!");
        card.addView(textCodeButton, UiStyle.buttonParams(this));

        card.addView(UiStyle.overlayStepLabel(this, "3. Enter approval code!"), UiStyle.fullWidth(this, 6));
        EditText codeInput = new EditText(this);
        codeInput.setTag("code_input");
        codeInput.setHint("Approval code");
        KeyboardHelper.prepareNumericInput(codeInput);
        codeInput.setSingleLine(true);
        codeInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        codeInput.setText(formState.approvalCodeText);
        codeInput.setGravity(Gravity.CENTER);
        UiStyle.styleOverlayInput(codeInput, formState.codeInputError);
        codeInput.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                formState.approvalCodeText = editable.toString();
                formState.codeInputError = false;
                UiStyle.styleOverlayInput(codeInput, false);
                hideOverlayError(errorText, formState);
            }
        });
        codeInput.setOnTouchListener((v, event) -> {
            boolean handled = KeyboardHelper.showOnTouch(this, codeInput, event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                scrollToInput(scrollView, codeInput);
            }
            return handled;
        });
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

        if (formState.errorMessage.isEmpty()) {
            errorText.setVisibility(View.GONE);
        } else {
            errorText.setText(formState.errorMessage);
            UiStyle.setStatus(errorText, UiStyle.STATUS_REQUIRED);
            errorText.setVisibility(View.VISIBLE);
        }
        card.addView(errorText, UiStyle.fullWidth(this, 12));

        card.addView(UiStyle.overlayStepLabel(this, "4. Let the goose loose!"), UiStyle.fullWidth(this, 6));
        Button unlockButton = UiStyle.primaryButton(this, "Loose the Goose!");
        card.addView(unlockButton, UiStyle.buttonParams(this));

        Button leaveButton = UiStyle.overlaySecondaryButton(this, "Leave App!");
        card.addView(leaveButton, UiStyle.buttonParams(this));

        textCodeButton.setOnClickListener(v -> {
            int requestedMinutes = parsePositiveInt(minutesInput);
            keepOverlayUntilMs = System.currentTimeMillis() + OVERLAY_STICKY_MS;
            if (requestedMinutes <= 0) {
                formState.minutesInputError = true;
                UiStyle.styleOverlayInput(minutesInput, true);
                showOverlayError(
                        errorText,
                        formState,
                        "Tell the goose how many minutes to ask for!"
                );
                return;
            }
            UiStyle.styleOverlayInput(minutesInput, false);
            if (composeCodeSms(packageName, requestedMinutes)) {
                formState.requestedMinutesText = String.valueOf(requestedMinutes);
                formState.lastRequestedMinutes = requestedMinutes;
                formState.minutesInputError = false;
                formState.errorMessage = "";
                minutesInput.setText(formState.requestedMinutesText);
                requestStatus.setText(requestSentMessage(requestedMinutes));
                UiStyle.setStatus(requestStatus, UiStyle.STATUS_READY);
                hideOverlayError(errorText, formState);
                showKeyboard(codeInput);
            }
        });

        unlockButton.setOnClickListener(v -> {
            String entered = codeInput.getText().toString().trim();
            keepOverlayUntilMs = System.currentTimeMillis() + OVERLAY_STICKY_MS;
            if (entered.isEmpty()) {
                formState.codeInputError = true;
                UiStyle.styleOverlayInput(codeInput, true);
                showOverlayError(
                        errorText,
                        formState,
                        "Enter the approval code first! The goose is still guarding this app!"
                );
                return;
            }
            int approvedMinutes = Preferences.consumeApprovalCodeMinutesIfValid(this, packageName, entered);
            if (approvedMinutes > 0) {
                Preferences.grantExtraTime(this, packageName, approvedMinutes);
                showUnlockCelebration(card, approvedMinutes);
            } else {
                formState.codeInputError = true;
                UiStyle.styleOverlayInput(codeInput, true);
                showOverlayError(
                        errorText,
                        formState,
                        "That code did not honk! Request a new one if needed!"
                );
            }
        });

        leaveButton.setOnClickListener(v -> {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
            hideOverlay(true);
        });

        return scrollView;
    }

    private void showUnlockCelebration(LinearLayout card, int approvedMinutes) {
        unlockCelebrationRunning = true;
        keepOverlayUntilMs = System.currentTimeMillis() + OVERLAY_STICKY_MS;
        card.removeAllViews();
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        GooseCelebrationView gooseView = new GooseCelebrationView(this);
        card.addView(gooseView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiStyle.dp(this, 190)
        ));

        TextView title = UiStyle.overlayTitle(this, "The goose is loose!");
        card.addView(title, UiStyle.fullWidth(this, 8));

        TextView body = UiStyle.overlayBody(
                this,
                approvedMinutes + " minutes of extra time! Honk!"
        );
        body.setGravity(Gravity.CENTER);
        card.addView(body, UiStyle.fullWidth(this, 0));

        gooseView.start(() -> {
            unlockCelebrationRunning = false;
            hideOverlay(false);
            Toast.makeText(
                    this,
                    "The goose is loose for " + approvedMinutes + " minutes!",
                    Toast.LENGTH_LONG
            ).show();
        });
    }

    private OverlayFormState overlayFormState(String packageName) {
        OverlayFormState state = overlayFormStates.get(packageName);
        if (state == null) {
            state = new OverlayFormState();
            overlayFormStates.put(packageName, state);
        }
        return state;
    }

    private String requestSentMessage(int requestedMinutes) {
        return "Goose request sent for " + requestedMinutes
                + " minutes! Each approval code unlocks the amount that goose asked for!";
    }

    private void showOverlayError(TextView errorText, OverlayFormState formState, String message) {
        formState.errorMessage = "REQUIRED: " + message;
        errorText.setText(formState.errorMessage);
        UiStyle.setStatus(errorText, UiStyle.STATUS_REQUIRED);
        errorText.setVisibility(View.VISIBLE);
    }

    private void hideOverlayError(TextView errorText, OverlayFormState formState) {
        formState.errorMessage = "";
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
        KeyboardHelper.show(this, input);
    }

    private boolean shouldKeepExistingOverlay(long now, String foregroundPackage) {
        if (overlayView == null || overlayPackageName == null) {
            return false;
        }
        if (foregroundPackage != null
                && foregroundPackage.equals(overlayPackageName)) {
            return Preferences.isOverLimit(this, overlayPackageName)
                    && !Preferences.isTemporarilyUnlocked(this, overlayPackageName);
        }
        if (foregroundPackage != null && foregroundPackage.equals(getPackageName())) {
            return Preferences.isOverLimit(this, overlayPackageName)
                    && !Preferences.isTemporarilyUnlocked(this, overlayPackageName);
        }
        if (now > keepOverlayUntilMs) {
            return false;
        }
        if (isTransientSystemSurface(foregroundPackage)) {
            return Preferences.isOverLimit(this, overlayPackageName)
                    && !Preferences.isTemporarilyUnlocked(this, overlayPackageName);
        }
        if (foregroundPackage != null
                && !foregroundPackage.equals(overlayPackageName)) {
            return false;
        }
        return Preferences.isOverLimit(this, overlayPackageName)
                && !Preferences.isTemporarilyUnlocked(this, overlayPackageName);
    }

    private boolean isTransientSystemSurface(String packageName) {
        if (packageName == null) {
            return true;
        }
        if ("com.android.systemui".equals(packageName)) {
            return true;
        }
        return packageName.equals(homePackageName());
    }

    private String homePackageName() {
        if (homePackageName != null) {
            return homePackageName;
        }
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolveInfo = getPackageManager().resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
        if (resolveInfo != null && resolveInfo.activityInfo != null) {
            homePackageName = resolveInfo.activityInfo.packageName;
        } else {
            homePackageName = "";
        }
        return homePackageName;
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
        String phone = Preferences.accountabilityPhoneNumber(this);
        if (phone.length() != 10) {
            Toast.makeText(this, "Add a 10-digit accountability number first!", Toast.LENGTH_LONG).show();
            return false;
        }

        String requestCode = Preferences.createRequestCode(this, packageName, requestedMinutes);
        String message = "A goose is asking for " + requestedMinutes
                + " minutes of extra time in " + appLabel(packageName) + "!"
                + ". Request code: " + requestCode
                + ". If approved, send back the approval code for this goose request.";
        Intent intent = new Intent(Intent.ACTION_SENDTO);
        intent.setData(Uri.parse("smsto:" + Uri.encode(phone)));
        intent.putExtra("sms_body", message);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
            return true;
        } catch (RuntimeException ignored) {
            Toast.makeText(this, "No SMS app found!", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void hideOverlay() {
        hideOverlay(true);
    }

    private void hideOverlay(boolean preserveFormState) {
        unlockCelebrationRunning = false;
        String removedPackageName = overlayPackageName;
        if (overlayView == null) {
            overlayPackageName = null;
            if (!preserveFormState && removedPackageName != null) {
                overlayFormStates.remove(removedPackageName);
            }
            return;
        }
        try {
            KeyboardHelper.hide(this, overlayView);
            windowManager.removeView(overlayView);
        } catch (RuntimeException ignored) {
            // The view may already be detached if Android revoked overlay permission.
        }
        overlayView = null;
        overlayPackageName = null;
        keepOverlayUntilMs = 0L;
        if (!preserveFormState && removedPackageName != null) {
            overlayFormStates.remove(removedPackageName);
        }
    }

    private void stopMonitoring() {
        Preferences.prefs(this).edit().putBoolean(Preferences.KEY_ENABLED, false).apply();
        hideOverlay(false);
        overlayFormStates.clear();
        stopForeground(true);
        stopSelf();
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "AirLock goose watch",
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
                .setContentTitle("The goose is on duty!")
                .setContentText("Guarding selected app limits!")
                .setSmallIcon(R.drawable.ic_stat_lock_clock)
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

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence text, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence text, int start, int before, int count) {
        }
    }

    private static final class OverlayFormState {
        String requestedMinutesText = "5";
        String approvalCodeText = "";
        String errorMessage = "";
        boolean minutesInputError;
        boolean codeInputError;
        int lastRequestedMinutes = -1;
    }
}
