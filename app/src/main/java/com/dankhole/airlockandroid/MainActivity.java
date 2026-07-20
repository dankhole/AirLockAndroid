package com.dankhole.airlockandroid;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import android.app.Activity;

public class MainActivity extends Activity {
    private TextView statusText;
    private TextView selectedText;
    private Switch enabledSwitch;
    private EditText limitMinutesInput;
    private EditText extraMinutesInput;
    private EditText phoneInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private ScrollView buildContent() {
        SharedPreferences preferences = Preferences.prefs(this);
        int padding = dp(20);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText("AirLock Android");
        title.setTextSize(28);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Limit selected apps and require an accountability code for extra time.");
        subtitle.setTextSize(16);
        subtitle.setPadding(0, dp(8), 0, dp(16));
        root.addView(subtitle);

        statusText = new TextView(this);
        statusText.setTextSize(15);
        root.addView(statusText);

        Button usageAccessButton = button("Grant Usage Access");
        usageAccessButton.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        root.addView(usageAccessButton);

        Button overlayButton = button("Grant Display Over Other Apps");
        overlayButton.setOnClickListener(v -> {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(intent);
        });
        root.addView(overlayButton);

        enabledSwitch = new Switch(this);
        enabledSwitch.setText("Monitoring enabled");
        enabledSwitch.setTextSize(16);
        enabledSwitch.setChecked(preferences.getBoolean(Preferences.KEY_ENABLED, false));
        enabledSwitch.setOnCheckedChangeListener(this::onEnabledChanged);
        root.addView(enabledSwitch);

        limitMinutesInput = numberInput("Daily limit minutes", Preferences.dailyLimitMinutes(this));
        root.addView(limitMinutesInput);

        extraMinutesInput = numberInput("Extra time minutes", Preferences.extraTimeMinutes(this));
        root.addView(extraMinutesInput);

        phoneInput = new EditText(this);
        phoneInput.setHint("Accountability phone number");
        phoneInput.setInputType(InputType.TYPE_CLASS_PHONE);
        phoneInput.setText(preferences.getString(Preferences.KEY_ACCOUNTABILITY_NUMBER, ""));
        root.addView(phoneInput);

        Button saveButton = button("Save Settings");
        saveButton.setOnClickListener(v -> {
            saveSettings();
            refresh();
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
        });
        root.addView(saveButton);

        selectedText = new TextView(this);
        selectedText.setTextSize(15);
        selectedText.setPadding(0, dp(18), 0, dp(4));
        root.addView(selectedText);

        Button selectAppsButton = button("Select Apps");
        selectAppsButton.setOnClickListener(v -> startActivity(new Intent(this, AppSelectionActivity.class)));
        root.addView(selectAppsButton);

        Button startButton = button("Start Monitoring");
        startButton.setOnClickListener(v -> {
            saveSettings();
            ensureNotificationPermission();
            Intent intent = new Intent(this, MonitoringService.class);
            intent.setAction(MonitoringService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            refresh();
        });
        root.addView(startButton);

        Button stopButton = button("Stop Monitoring");
        stopButton.setOnClickListener(v -> {
            Preferences.prefs(this).edit().putBoolean(Preferences.KEY_ENABLED, false).apply();
            enabledSwitch.setChecked(false);
            Intent intent = new Intent(this, MonitoringService.class);
            intent.setAction(MonitoringService.ACTION_STOP);
            startService(intent);
            refresh();
        });
        root.addView(stopButton);

        return scrollView;
    }

    private void onEnabledChanged(CompoundButton button, boolean checked) {
        Preferences.prefs(this).edit().putBoolean(Preferences.KEY_ENABLED, checked).apply();
    }

    private EditText numberInput(String hint, int value) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(value));
        return input;
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        return button;
    }

    private void saveSettings() {
        Preferences.prefs(this).edit()
                .putBoolean(Preferences.KEY_ENABLED, enabledSwitch.isChecked())
                .putInt(Preferences.KEY_DAILY_LIMIT_MINUTES, parsePositiveInt(limitMinutesInput, 15))
                .putInt(Preferences.KEY_EXTRA_TIME_MINUTES, parsePositiveInt(extraMinutesInput, 5))
                .putString(Preferences.KEY_ACCOUNTABILITY_NUMBER, phoneInput.getText().toString().trim())
                .apply();
    }

    private int parsePositiveInt(EditText input, int fallback) {
        try {
            int parsed = Integer.parseInt(input.getText().toString().trim());
            return Math.max(1, parsed);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void refresh() {
        boolean usage = hasUsageAccess();
        boolean overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        boolean enabled = Preferences.prefs(this).getBoolean(Preferences.KEY_ENABLED, false);
        int selectedCount = Preferences.selectedPackages(this).size();

        statusText.setText(
                "Usage Access: " + status(usage) + "\n"
                        + "Overlay: " + status(overlay) + "\n"
                        + "Monitoring: " + status(enabled)
        );
        selectedText.setText("Selected apps: " + selectedCount);
    }

    private String status(boolean enabled) {
        return enabled ? "on" : "off";
    }

    private boolean hasUsageAccess() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mode = appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    getPackageName()
            );
        } else {
            mode = appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    getPackageName()
            );
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
