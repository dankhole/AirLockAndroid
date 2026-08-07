package com.dankhole.airlockandroid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class AppSelectionActivity extends Activity {
    static final String EXTRA_AUTHORIZED_WHILE_MONITORING =
            "com.dankhole.airlockandroid.AUTHORIZED_WHILE_MONITORING";

    private final Set<String> batchPackages = new HashSet<>();
    private List<AppEntry> apps;
    private TextView selectionSummary;
    private boolean showingLimitStep;
    private boolean authorizedWhileMonitoring;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiStyle.applyWindow(this);
        authorizedWhileMonitoring = getIntent().getBooleanExtra(EXTRA_AUTHORIZED_WHILE_MONITORING, false);
        if (isLockedByActiveMonitoring()) {
            showMonitoringLockedScreen();
            return;
        }
        if (!AndroidPermissions.hasUsageAccess(this)) {
            showAccessRequiredScreen();
            return;
        }
        apps = loadLaunchableApps();
        showSelectionStep();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isLockedByActiveMonitoring()) {
            showMonitoringLockedScreen();
            return;
        }
        if (!AndroidPermissions.hasUsageAccess(this)) {
            showAccessRequiredScreen();
            return;
        }
        if (apps == null) {
            apps = loadLaunchableApps();
            showSelectionStep();
        }
    }

    @Override
    public void onBackPressed() {
        if (showingLimitStep) {
            setContentView(selectionContent(false));
            showingLimitStep = false;
            return;
        }
        super.onBackPressed();
    }

    private void showAccessRequiredScreen() {
        showingLimitStep = false;
        apps = null;

        ScrollView scrollView = UiStyle.screenScroll(this);
        LinearLayout root = UiStyle.screenRoot(this);
        UiStyle.applySystemInsetsPadding(root, 20, 20, 20, 20);
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        root.addView(UiStyle.screenTitle(this, "Goose Limits"), UiStyle.fullWidth(this, 4));
        root.addView(new GooseMascotView(this), UiStyle.gooseBannerParams(this));
        root.addView(UiStyle.bodyText(
                this,
                "Usage Access is required before the goose can choose guarded apps!"
        ), UiStyle.fullWidth(this, 18));

        LinearLayout card = UiStyle.card(this);
        TextView required = UiStyle.statusText(this);
        required.setText("REQUIRED BEFORE WADDLING FORWARD: Usage Access is off!\n"
                + "Open Usage Access, choose AirLock Goose, and enable usage access before returning to the goose wizard!");
        UiStyle.setStatus(required, UiStyle.STATUS_REQUIRED);
        card.addView(required, UiStyle.fullWidth(this, 12));

        Button settingsButton = UiStyle.primaryButton(this, "Open Usage Access Settings!");
        settingsButton.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        card.addView(settingsButton, UiStyle.buttonParams(this));

        Button backButton = UiStyle.secondaryButton(this, "Back to Goose Settings!");
        backButton.setOnClickListener(v -> finish());
        card.addView(backButton, UiStyle.buttonParams(this));

        root.addView(card, UiStyle.fullWidth(this));
        setContentView(scrollView);
    }

    private void showMonitoringLockedScreen() {
        showingLimitStep = false;
        apps = null;

        ScrollView scrollView = UiStyle.screenScroll(this);
        LinearLayout root = UiStyle.screenRoot(this);
        UiStyle.applySystemInsetsPadding(root, 20, 20, 20, 20);
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        root.addView(UiStyle.screenTitle(this, "Goose Limits"), UiStyle.fullWidth(this, 4));
        root.addView(new GooseMascotView(this), UiStyle.gooseBannerParams(this));
        root.addView(UiStyle.bodyText(
                this,
                "Goose duty is active, so guarded apps are locked behind the master PIN!"
        ), UiStyle.fullWidth(this, 18));

        LinearLayout card = UiStyle.card(this);
        TextView required = UiStyle.statusText(this);
        required.setText("LOCKED: Return to AirLock Goose and use Change Goose List! The master PIN is required while goose duty is on.");
        UiStyle.setStatus(required, UiStyle.STATUS_REQUIRED);
        card.addView(required, UiStyle.fullWidth(this, 12));

        Button backButton = UiStyle.primaryButton(this, "Back to AirLock Goose!");
        backButton.setOnClickListener(v -> finish());
        card.addView(backButton, UiStyle.buttonParams(this));

        root.addView(card, UiStyle.fullWidth(this));
        setContentView(scrollView);
    }

    private boolean isLockedByActiveMonitoring() {
        return Preferences.prefs(this).getBoolean(Preferences.KEY_ENABLED, false)
                && !authorizedWhileMonitoring;
    }

    private void showSelectionStep() {
        batchPackages.clear();
        showingLimitStep = false;
        setContentView(selectionContent(true));
    }

    private ScrollView selectionContent(boolean clearSelection) {
        if (clearSelection) {
            batchPackages.clear();
        }

        ScrollView scrollView = UiStyle.screenScroll(this);
        LinearLayout root = UiStyle.screenRoot(this);
        UiStyle.applySystemInsetsPadding(root, 20, 20, 20, 20);
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        root.addView(UiStyle.screenTitle(this, "Goose Limits"), UiStyle.fullWidth(this, 4));
        root.addView(new GooseMascotView(this), UiStyle.gooseBannerParams(this));
        root.addView(wizardHeader("Step 1 of 2", "Pick apps!"), UiStyle.fullWidth(this, 12));
        root.addView(UiStyle.bodyText(
                this,
                "Pick one app or a flock of apps, then set when the goose should step in!"
        ), UiStyle.fullWidth(this, 16));

        selectionSummary = UiStyle.statusText(this);
        root.addView(selectionSummary, UiStyle.fullWidth(this, 12));
        refreshSelectionSummary();

        LinearLayout listCard = UiStyle.card(this);
        for (AppEntry app : apps) {
            listCard.addView(appRow(app), UiStyle.fullWidth(this, 10));
        }
        root.addView(listCard, UiStyle.fullWidth(this, 14));

        Button nextButton = UiStyle.primaryButton(this, "Continue to Goose Timer!");
        nextButton.setOnClickListener(v -> {
            if (batchPackages.isEmpty()) {
                Toast.makeText(this, "Pick at least one app for the goose!", Toast.LENGTH_SHORT).show();
                refreshSelectionSummary();
                return;
            }
            showLimitStep();
        });
        root.addView(nextButton, UiStyle.buttonParams(this));

        Button removeButton = UiStyle.secondaryButton(this, "Remove Goose Limit from Selected!");
        removeButton.setOnClickListener(v -> {
            if (batchPackages.isEmpty()) {
                Toast.makeText(this, "Pick at least one app first!", Toast.LENGTH_SHORT).show();
                refreshSelectionSummary();
                return;
            }
            Preferences.removeLimitsForPackages(this, batchPackages);
            Toast.makeText(this, "Goose limits removed!", Toast.LENGTH_SHORT).show();
            showSelectionStep();
        });
        root.addView(removeButton, UiStyle.buttonParams(this));

        return scrollView;
    }

    private LinearLayout appRow(AppEntry app) {
        Set<String> limitedPackages = Preferences.selectedPackages(this);
        boolean limited = limitedPackages.contains(app.packageName);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(UiStyle.dp(this, 72));
        row.setPadding(UiStyle.dp(this, 12), UiStyle.dp(this, 10), UiStyle.dp(this, 8), UiStyle.dp(this, 10));

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        icon.setContentDescription(app.label);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                UiStyle.dp(this, 44),
                UiStyle.dp(this, 44)
        );
        iconParams.setMargins(0, 0, UiStyle.dp(this, 12), 0);
        row.addView(icon, iconParams);

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );

        TextView label = UiStyle.sectionTitle(this, app.label);
        label.setTextSize(16);
        textColumn.addView(label);

        TextView detail = UiStyle.helperText(this, limited
                ? "Daily goose limit: " + Preferences.dailyLimitMinutes(this, app.packageName) + " min"
                : "No goose limit yet");
        detail.setTextColor(limited ? UiStyle.COLOR_PRIMARY : UiStyle.COLOR_TEXT_MUTED);
        detail.setPadding(0, UiStyle.dp(this, 2), 0, 0);
        textColumn.addView(detail);

        TextView selectedBadge = UiStyle.badge(this, "Goose picked!", UiStyle.STATUS_READY);
        selectedBadge.setVisibility(View.GONE);
        textColumn.addView(selectedBadge, compactBadgeParams());

        row.addView(textColumn, textParams);

        CheckBox checkBox = new CheckBox(this);
        checkBox.setMinWidth(UiStyle.dp(this, 48));
        checkBox.setMinHeight(UiStyle.dp(this, 48));
        checkBox.setContentDescription("Select " + app.label);
        checkBox.setChecked(batchPackages.contains(app.packageName));
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                batchPackages.add(app.packageName);
            } else {
                batchPackages.remove(app.packageName);
            }
            updateAppRowState(row, selectedBadge, app, isChecked);
            refreshSelectionSummary();
        });
        row.addView(checkBox);

        row.setOnClickListener(v -> checkBox.setChecked(!checkBox.isChecked()));
        updateAppRowState(row, selectedBadge, app, checkBox.isChecked());
        return row;
    }

    private void updateAppRowState(LinearLayout row, TextView selectedBadge, AppEntry app, boolean selected) {
        UiStyle.styleSelectableRow(row, selected);
        selectedBadge.setVisibility(selected ? View.VISIBLE : View.GONE);
        row.setContentDescription(app.label + (selected ? ", selected" : ", not selected"));
    }

    private void showLimitStep() {
        showingLimitStep = true;

        ScrollView scrollView = UiStyle.screenScroll(this);
        LinearLayout root = UiStyle.screenRoot(this);
        UiStyle.applySystemInsetsPadding(root, 20, 20, 20, 20);
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        root.addView(UiStyle.screenTitle(this, "Goose Timer"), UiStyle.fullWidth(this, 4));
        root.addView(new GooseMascotView(this), UiStyle.gooseBannerParams(this));
        root.addView(wizardHeader("Step 2 of 2", "Set minutes!"), UiStyle.fullWidth(this, 12));
        root.addView(UiStyle.bodyText(this, selectedAppsSummary()), UiStyle.fullWidth(this, 16));

        LinearLayout panel = UiStyle.card(this);
        panel.addView(UiStyle.fieldLabel(this, "Daily goose limit in minutes"), UiStyle.fullWidth(this, 6));

        EditText limitInput = UiStyle.inputField(this, "Minutes");
        limitInput.setInputType(InputType.TYPE_CLASS_PHONE);
        limitInput.setSingleLine(true);
        limitInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        limitInput.setText("15");
        limitInput.setSelectAllOnFocus(true);
        limitInput.setOnClickListener(v -> {
            showKeyboard(limitInput);
            scrollInputIntoView(scrollView, limitInput);
        });
        limitInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                showKeyboard(limitInput);
                scrollInputIntoView(scrollView, limitInput);
            }
        });
        panel.addView(limitInput, UiStyle.fullWidth(this, 8));

        TextView validationText = UiStyle.statusText(this);
        validationText.setText("Enter a whole number greater than 0! The goose applies it to every picked app.");
        UiStyle.setStatus(validationText, UiStyle.STATUS_NEUTRAL);
        panel.addView(validationText);
        root.addView(panel, UiStyle.fullWidth(this, 14));

        Button saveButton = UiStyle.primaryButton(this, "Save Goose Limit!");
        saveButton.setOnClickListener(v -> {
            int minutes = parsePositiveInt(limitInput);
            if (minutes <= 0) {
                UiStyle.styleInput(limitInput, true);
                validationText.setText("REQUIRED: Give the goose a daily limit greater than 0 minutes!");
                UiStyle.setStatus(validationText, UiStyle.STATUS_REQUIRED);
                limitInput.setError("Enter a number greater than 0");
                return;
            }
            Preferences.saveLimitForPackages(this, batchPackages, minutes);
            Toast.makeText(this, "Goose limits saved!", Toast.LENGTH_SHORT).show();
            finish();
        });
        root.addView(saveButton, UiStyle.buttonParams(this));

        Button backButton = UiStyle.secondaryButton(this, "Back to Goose List!");
        backButton.setOnClickListener(v -> {
            showingLimitStep = false;
            setContentView(selectionContent(false));
        });
        root.addView(backButton, UiStyle.buttonParams(this));

        setContentView(scrollView);
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

    private void scrollInputIntoView(ScrollView scrollView, View input) {
        input.postDelayed(() -> {
            Rect bounds = new Rect();
            input.getDrawingRect(bounds);
            scrollView.offsetDescendantRectToMyCoords(input, bounds);
            int targetY = Math.max(0, bounds.top - UiStyle.dp(this, 80));
            scrollView.smoothScrollTo(0, targetY);
        }, 250);
    }

    private LinearLayout wizardHeader(String step, String title) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView badge = UiStyle.badge(this, step, UiStyle.STATUS_WARNING);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        badgeParams.setMargins(0, 0, UiStyle.dp(this, 10), 0);
        row.addView(badge, badgeParams);

        TextView text = UiStyle.sectionTitle(this, title);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(text);
        return row;
    }

    private String selectedAppsSummary() {
        if (batchPackages.size() == 1) {
            return "1 app picked! Save a goose limit for this app.";
        }
        return batchPackages.size() + " apps picked! Save one goose limit for this flock.";
    }

    private List<AppEntry> loadLaunchableApps() {
        PackageManager packageManager = getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> resolved = packageManager.queryIntentActivities(intent, 0);
        List<AppEntry> apps = new ArrayList<>();
        Set<String> seenPackages = new HashSet<>();
        for (ResolveInfo info : resolved) {
            String packageName = info.activityInfo.packageName;
            if (getPackageName().equals(packageName) || !seenPackages.add(packageName)) {
                continue;
            }
            CharSequence label = info.loadLabel(packageManager);
            Drawable icon = info.loadIcon(packageManager);
            if (icon == null) {
                icon = packageManager.getDefaultActivityIcon();
            }
            apps.add(new AppEntry(label == null ? packageName : label.toString(), packageName, icon));
        }
        apps.sort(Comparator.comparing(app -> app.label.toLowerCase(Locale.US)));
        return apps;
    }

    private void refreshSelectionSummary() {
        if (selectionSummary == null) {
            return;
        }
        boolean hasSelection = !batchPackages.isEmpty();
        selectionSummary.setText(hasSelection
                ? "READY: " + batchPackages.size() + " picked! Continue to set the goose timer."
                : "REQUIRED: Pick at least one app before the goose can continue!");
        UiStyle.setStatus(selectionSummary, hasSelection ? UiStyle.STATUS_READY : UiStyle.STATUS_REQUIRED);
    }

    private LinearLayout.LayoutParams compactBadgeParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, UiStyle.dp(this, 6), 0, 0);
        return params;
    }

    private int parsePositiveInt(EditText input) {
        try {
            int parsed = Integer.parseInt(input.getText().toString().trim());
            return parsed > 0 ? parsed : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static final class AppEntry {
        final String label;
        final String packageName;
        final Drawable icon;

        AppEntry(String label, String packageName, Drawable icon) {
            this.label = label;
            this.packageName = packageName;
            this.icon = icon;
        }
    }
}
