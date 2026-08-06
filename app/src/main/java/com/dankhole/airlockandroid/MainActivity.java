package com.dankhole.airlockandroid;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private TextView statusText;
    private TextView usageAccessRequiredText;
    private TextView overlayAccessRequiredText;
    private TextView accountabilityRequiredText;
    private TextView masterPinRequiredText;
    private TextView appLimitsRequiredText;
    private TextView monitoringStateText;
    private TextView selectedText;
    private TextView controlsBlockedText;
    private Switch enabledSwitch;
    private EditText phoneInput;
    private EditText currentPinInput;
    private EditText newPinInput;
    private EditText confirmPinInput;
    private Button selectAppsButton;
    private Button startButton;
    private Button stopButton;
    private Button saveMasterPinButton;
    private boolean suppressSwitchChange;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiStyle.applyWindow(this);
        setContentView(buildContent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        ensureEnabledServiceRunning();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            View focused = getCurrentFocus();
            if (focused instanceof EditText) {
                Rect bounds = new Rect();
                focused.getGlobalVisibleRect(bounds);
                if (!bounds.contains((int) event.getRawX(), (int) event.getRawY())) {
                    clearInputFocus((EditText) focused);
                }
            }
        }
        return super.dispatchTouchEvent(event);
    }

    private ScrollView buildContent() {
        SharedPreferences preferences = Preferences.prefs(this);

        ScrollView scrollView = UiStyle.screenScroll(this);
        LinearLayout root = UiStyle.screenRoot(this);
        UiStyle.applySystemInsetsPadding(root, 20, 20, 20, 20);
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        root.addView(UiStyle.screenTitle(this, "AirLock Android"), UiStyle.fullWidth(this, 2));
        root.addView(UiStyle.bodyText(
                this,
                "Limit selected apps after a daily allowance and require an accountability code for extra time."
        ), UiStyle.fullWidth(this, 18));

        root.addView(buildMonitoringStatusCard(), UiStyle.fullWidth(this, 14));
        root.addView(buildPermissionsCard(), UiStyle.fullWidth(this, 14));
        root.addView(buildAccountabilityCard(preferences), UiStyle.fullWidth(this, 14));
        root.addView(buildMasterPinCard(), UiStyle.fullWidth(this, 14));
        root.addView(buildAppLimitsCard(), UiStyle.fullWidth(this, 14));
        root.addView(buildMonitoringControlsCard(preferences), UiStyle.fullWidth(this, 4));

        TextWatcher watcher = autoSaveWatcher();
        phoneInput.addTextChangedListener(watcher);
        refresh();

        return scrollView;
    }

    private LinearLayout buildMonitoringStatusCard() {
        LinearLayout card = sectionCard(
                "Monitoring status",
                "This is the current enforcement state for the selected app limits."
        );

        statusText = UiStyle.statusText(this);
        statusText.setTextSize(16);
        card.addView(statusText, UiStyle.fullWidth(this, 10));

        monitoringStateText = UiStyle.bodyText(this, "");
        card.addView(monitoringStateText);
        return card;
    }

    private LinearLayout buildPermissionsCard() {
        LinearLayout card = sectionCard(
                "Required permissions",
                "These Android settings must be granted before setup can move forward."
        );

        usageAccessRequiredText = UiStyle.statusText(this);
        card.addView(usageAccessRequiredText, UiStyle.fullWidth(this, 10));

        Button usageAccessButton = UiStyle.primaryButton(this, "Open Usage Access Settings");
        usageAccessButton.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        card.addView(usageAccessButton, UiStyle.buttonParams(this));

        overlayAccessRequiredText = UiStyle.statusText(this);
        card.addView(overlayAccessRequiredText, UiStyle.fullWidth(this, 10));

        Button overlayButton = UiStyle.secondaryButton(this, "Open Display Over Other Apps");
        overlayButton.setOnClickListener(v -> startActivity(overlaySettingsIntent()));
        card.addView(overlayButton, UiStyle.buttonParams(this));
        return card;
    }

    private LinearLayout buildAccountabilityCard(SharedPreferences preferences) {
        LinearLayout card = sectionCard(
                "Accountability setup",
                "AirLock opens your SMS app with a generated code request. It does not request SMS permissions."
        );

        card.addView(UiStyle.fieldLabel(this, "Accountability phone number"), UiStyle.fullWidth(this, 6));
        phoneInput = UiStyle.inputField(this, "Phone number");
        phoneInput.setInputType(InputType.TYPE_CLASS_PHONE);
        phoneInput.setText(preferences.getString(Preferences.KEY_ACCOUNTABILITY_NUMBER, ""));
        configureEditableInput(phoneInput);
        card.addView(phoneInput, UiStyle.fullWidth(this, 10));

        accountabilityRequiredText = UiStyle.statusText(this);
        card.addView(accountabilityRequiredText);
        return card;
    }

    private LinearLayout buildMasterPinCard() {
        LinearLayout card = sectionCard(
                "Master PIN",
                "The PIN is required to start, stop, or change monitoring controls. Enter the new PIN twice whenever setting or changing it."
        );

        currentPinInput = pinInput("Current PIN");
        card.addView(currentPinInput, UiStyle.fullWidth(this, 10));

        card.addView(UiStyle.fieldLabel(this, "New master PIN"), UiStyle.fullWidth(this, 6));
        newPinInput = pinInput("New PIN");
        card.addView(newPinInput, UiStyle.fullWidth(this, 10));

        confirmPinInput = pinInput("Confirm new PIN");
        card.addView(confirmPinInput, UiStyle.fullWidth(this, 10));

        saveMasterPinButton = UiStyle.secondaryButton(this, "Set Master PIN");
        saveMasterPinButton.setOnClickListener(v -> saveMasterPin());
        card.addView(saveMasterPinButton, UiStyle.buttonParams(this));

        masterPinRequiredText = UiStyle.statusText(this);
        card.addView(masterPinRequiredText);
        return card;
    }

    private LinearLayout buildAppLimitsCard() {
        LinearLayout card = sectionCard(
                "App limits",
                "Choose launchable apps and set the daily minutes that should trigger the blocking overlay."
        );

        selectedText = UiStyle.bodyText(this, "");
        card.addView(selectedText, UiStyle.fullWidth(this, 10));

        appLimitsRequiredText = UiStyle.statusText(this);
        card.addView(appLimitsRequiredText, UiStyle.fullWidth(this, 10));

        selectAppsButton = UiStyle.primaryButton(this, "Set App Limits");
        selectAppsButton.setOnClickListener(v -> {
            if (!AndroidPermissions.hasUsageAccess(this)) {
                Toast.makeText(this, "Usage Access is required before choosing apps", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                return;
            }
            if (Preferences.prefs(this).getBoolean(Preferences.KEY_ENABLED, false)) {
                promptForMasterPin("Change App Limits", () -> openAppSelection(true), null);
                return;
            }
            openAppSelection(false);
        });
        card.addView(selectAppsButton, UiStyle.buttonParams(this));
        return card;
    }

    private LinearLayout buildMonitoringControlsCard(SharedPreferences preferences) {
        LinearLayout card = sectionCard(
                "Monitoring controls",
                "Start and stop require the master PIN so the settings cannot be changed accidentally."
        );

        enabledSwitch = new Switch(this);
        enabledSwitch.setText("Monitoring toggle");
        enabledSwitch.setTextSize(16);
        enabledSwitch.setTextColor(UiStyle.COLOR_TEXT_PRIMARY);
        enabledSwitch.setMinHeight(UiStyle.dp(this, 48));
        enabledSwitch.setPadding(0, 0, 0, UiStyle.dp(this, 8));
        enabledSwitch.setChecked(preferences.getBoolean(Preferences.KEY_ENABLED, false));
        enabledSwitch.setOnCheckedChangeListener(this::onEnabledChanged);
        card.addView(enabledSwitch, UiStyle.fullWidth(this, 4));

        controlsBlockedText = UiStyle.statusText(this);
        card.addView(controlsBlockedText, UiStyle.fullWidth(this, 10));

        startButton = UiStyle.primaryButton(this, "Start Monitoring");
        startButton.setOnClickListener(v -> {
            if (!requireMonitoringPrerequisites()) {
                return;
            }
            promptForMasterPin("Start Monitoring", this::startMonitoring, null);
        });
        card.addView(startButton, UiStyle.buttonParams(this));

        stopButton = UiStyle.dangerButton(this, "Stop Monitoring");
        stopButton.setOnClickListener(v -> promptForMasterPin("Stop Monitoring", this::stopMonitoring, null));
        card.addView(stopButton, UiStyle.buttonParams(this));
        return card;
    }

    private LinearLayout sectionCard(String title, String helper) {
        LinearLayout card = UiStyle.card(this);
        card.addView(UiStyle.sectionTitle(this, title), UiStyle.fullWidth(this, 4));
        card.addView(UiStyle.helperText(this, helper), UiStyle.fullWidth(this, 14));
        return card;
    }

    private void onEnabledChanged(CompoundButton button, boolean checked) {
        if (suppressSwitchChange) {
            return;
        }
        boolean enabled = Preferences.prefs(this).getBoolean(Preferences.KEY_ENABLED, false);
        if (checked == enabled) {
            return;
        }
        if (checked) {
            if (!requireMonitoringPrerequisites()) {
                setMonitoringSwitchChecked(false);
                return;
            }
            promptForMasterPin("Start Monitoring", this::startMonitoring, () -> setMonitoringSwitchChecked(false));
        } else {
            promptForMasterPin("Stop Monitoring", this::stopMonitoring, () -> setMonitoringSwitchChecked(true));
        }
    }

    private EditText pinInput(String hint) {
        EditText input = UiStyle.inputField(this, hint);
        input.setInputType(InputType.TYPE_CLASS_PHONE);
        input.setTransformationMethod(PasswordTransformationMethod.getInstance());
        configureEditableInput(input);
        return input;
    }

    private void configureEditableInput(EditText input) {
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setSelectAllOnFocus(true);
        UiStyle.styleInput(input, false);
        input.setOnClickListener(v -> {
            showKeyboard(input);
            scrollInputIntoView(input);
        });
        input.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                showKeyboard(input);
                scrollInputIntoView(input);
            }
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                clearInputFocus(input);
                return true;
            }
            return false;
        });
    }

    private void saveSettings() {
        if (!settingsValuesValid()) {
            Preferences.prefs(this).edit().putBoolean(Preferences.KEY_ENABLED, false).apply();
            return;
        }
        Preferences.prefs(this).edit()
                .putBoolean(Preferences.KEY_ENABLED, enabledSwitch.isChecked())
                .putString(Preferences.KEY_ACCOUNTABILITY_NUMBER, phoneInput.getText().toString().trim())
                .apply();
    }

    private void saveValidSettings() {
        SharedPreferences.Editor editor = Preferences.prefs(this).edit()
                .putString(Preferences.KEY_ACCOUNTABILITY_NUMBER, phoneInput.getText().toString().trim());

        if (!monitoringPrerequisitesMet()) {
            editor.putBoolean(Preferences.KEY_ENABLED, false);
        }
        editor.apply();
    }

    private void saveMasterPin() {
        boolean hasMasterPin = Preferences.hasMasterPin(this);
        String currentPin = currentPinInput.getText().toString().trim();
        String newPin = newPinInput.getText().toString().trim();
        String confirmPin = confirmPinInput.getText().toString().trim();

        UiStyle.styleInput(currentPinInput, false);
        UiStyle.styleInput(newPinInput, false);
        UiStyle.styleInput(confirmPinInput, false);

        if (hasMasterPin && !Preferences.verifyMasterPin(this, currentPin)) {
            UiStyle.styleInput(currentPinInput, true);
            currentPinInput.setError("Current PIN required");
            Toast.makeText(this, "Enter the current master PIN", Toast.LENGTH_LONG).show();
            return;
        }
        if (!isValidPin(newPin)) {
            UiStyle.styleInput(newPinInput, true);
            newPinInput.setError("Use at least 4 digits");
            Toast.makeText(this, "Enter a new PIN with at least 4 digits", Toast.LENGTH_LONG).show();
            return;
        }
        if (!newPin.equals(confirmPin)) {
            UiStyle.styleInput(confirmPinInput, true);
            confirmPinInput.setError("PINs do not match");
            Toast.makeText(this, "Enter the same PIN twice", Toast.LENGTH_LONG).show();
            return;
        }

        Preferences.setMasterPin(this, newPin);
        currentPinInput.setText("");
        newPinInput.setText("");
        confirmPinInput.setText("");
        clearInputFocus(newPinInput);
        Toast.makeText(this, hasMasterPin ? "Master PIN changed" : "Master PIN set", Toast.LENGTH_SHORT).show();
        refresh();
    }

    private TextWatcher autoSaveWatcher() {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                saveValidSettings();
                refresh();
            }
        };
    }

    private boolean hasAccountabilityNumber() {
        return !phoneInput.getText().toString().trim().isEmpty();
    }

    private boolean monitoringPrerequisitesMet() {
        return AndroidPermissions.hasUsageAccess(this)
                && AndroidPermissions.hasOverlayAccess(this)
                && settingsValuesValid()
                && Preferences.hasMasterPin(this)
                && Preferences.hasLimitedApps(this);
    }

    private boolean settingsValuesValid() {
        return hasAccountabilityNumber();
    }

    private boolean isValidPin(String pin) {
        if (pin.length() < 4) {
            return false;
        }
        for (int i = 0; i < pin.length(); i++) {
            if (!Character.isDigit(pin.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private void refresh() {
        boolean usage = AndroidPermissions.hasUsageAccess(this);
        boolean overlay = AndroidPermissions.hasOverlayAccess(this);
        boolean hasAccountabilityNumber = hasAccountabilityNumber();
        boolean hasMasterPin = Preferences.hasMasterPin(this);
        boolean hasLimitedApps = Preferences.hasLimitedApps(this);
        boolean readyToMonitor = usage && overlay && hasAccountabilityNumber && hasMasterPin && hasLimitedApps;
        boolean enabled = Preferences.prefs(this).getBoolean(Preferences.KEY_ENABLED, false);
        int selectedCount = Preferences.selectedPackages(this).size();
        if (!readyToMonitor && enabled) {
            enabled = false;
            Preferences.prefs(this).edit().putBoolean(Preferences.KEY_ENABLED, false).apply();
        }

        statusText.setText(enabled
                ? "ON: AirLock is actively monitoring selected app limits."
                : readyToMonitor
                ? "READY: All requirements are complete. Monitoring is off."
                : "SETUP REQUIRED: Complete the required items below before monitoring can start.");
        UiStyle.setStatus(statusText, enabled || readyToMonitor ? UiStyle.STATUS_READY : UiStyle.STATUS_REQUIRED);

        monitoringStateText.setText(enabled
                ? "Selected apps will show the blocking overlay once today's limit is reached."
                : readyToMonitor
                ? "Use Monitoring controls below when you are ready to turn enforcement on."
                : "The controls stay locked until permissions, accountability, PIN, and at least one app limit are ready.");

        setRequirement(
                usageAccessRequiredText,
                usage,
                usage ? "READY: Usage Access granted" : "REQUIRED BEFORE APP LIMIT SETUP: Usage Access is off",
                usage
                        ? "AirLock can read foreground app usage for selected apps."
                        : "Open Usage Access, choose AirLock Android, then enable usage access before selecting apps or starting monitoring."
        );
        setRequirement(
                overlayAccessRequiredText,
                overlay,
                overlay ? "READY: Display Over Other Apps granted" : "REQUIRED BEFORE MONITORING: Display Over Other Apps is off",
                overlay
                        ? "AirLock can show the blocking overlay after a limit is reached."
                        : "Open Display Over Other Apps and allow AirLock Android so the blocker can appear over limited apps."
        );
        String accountabilityDetail = hasAccountabilityNumber
                ? enabled
                ? "Locked while monitoring is on. Stop monitoring with the master PIN before changing it."
                : "Access code requests will be addressed to this number."
                : "Enter the phone number that should receive generated access-code requests.";
        setRequirement(
                accountabilityRequiredText,
                hasAccountabilityNumber,
                hasAccountabilityNumber ? "READY: Accountability number saved" : "REQUIRED BEFORE MONITORING: Accountability number missing",
                accountabilityDetail
        );
        phoneInput.setEnabled(!enabled);
        UiStyle.styleInput(phoneInput, !hasAccountabilityNumber);

        currentPinInput.setVisibility(hasMasterPin ? View.VISIBLE : View.GONE);
        confirmPinInput.setVisibility(View.VISIBLE);
        currentPinInput.setError(null);
        newPinInput.setError(null);
        confirmPinInput.setError(null);
        saveMasterPinButton.setText(hasMasterPin ? "Change Master PIN" : "Set Master PIN");
        setRequirement(
                masterPinRequiredText,
                hasMasterPin,
                hasMasterPin ? "READY: Master PIN set" : "REQUIRED BEFORE MONITORING: Master PIN missing",
                hasMasterPin
                        ? "The PIN is required before changing monitoring state or replacing the PIN."
                        : "Set a PIN of at least 4 digits before monitoring can start."
        );

        selectedText.setText(selectedCount == 1
                ? "Configured app limits: 1 app"
                : "Configured app limits: " + selectedCount + " apps");
        setRequirement(
                appLimitsRequiredText,
                hasLimitedApps,
                hasLimitedApps ? "READY: App limits configured" : "REQUIRED BEFORE MONITORING: No app limits configured",
                usage
                        ? hasLimitedApps
                        ? "Selected apps have daily limits."
                        : "Choose at least one app and save its daily limit."
                        : "Usage Access must be granted before the app-limit wizard can open."
        );
        selectAppsButton.setText(usage
                ? enabled ? "Change App Limits (PIN Required)" : "Set App Limits"
                : "Open Usage Access Before App Limits");

        enabledSwitch.setText(enabled ? "Monitoring is ON" : "Monitoring is OFF");
        enabledSwitch.setEnabled(readyToMonitor || enabled);
        setMonitoringSwitchChecked(enabled);

        controlsBlockedText.setText(readyToMonitor
                ? "READY: Monitoring can be started with the master PIN."
                : "LOCKED: " + firstMissingRequirement(usage, overlay, hasAccountabilityNumber, hasMasterPin, hasLimitedApps));
        UiStyle.setStatus(controlsBlockedText, readyToMonitor ? UiStyle.STATUS_READY : UiStyle.STATUS_REQUIRED);

        startButton.setText(enabled ? "Monitoring Running" : "Start Monitoring");
        startButton.setEnabled(readyToMonitor && !enabled);
        stopButton.setEnabled(enabled);
    }

    private void setRequirement(TextView textView, boolean met, String title, String detail) {
        textView.setText(title + "\n" + detail);
        UiStyle.setStatus(textView, met ? UiStyle.STATUS_READY : UiStyle.STATUS_REQUIRED);
    }

    private String firstMissingRequirement(
            boolean usage,
            boolean overlay,
            boolean accountability,
            boolean masterPin,
            boolean appLimits
    ) {
        if (!usage) {
            return "Grant Usage Access before moving forward.";
        }
        if (!overlay) {
            return "Grant Display Over Other Apps before monitoring can start.";
        }
        if (!accountability) {
            return "Add the accountability phone number.";
        }
        if (!masterPin) {
            return "Set the master PIN.";
        }
        if (!appLimits) {
            return "Save at least one app limit.";
        }
        return "Complete required setup.";
    }

    private boolean requireMonitoringPrerequisites() {
        if (!AndroidPermissions.hasUsageAccess(this)) {
            Preferences.prefs(this).edit().putBoolean(Preferences.KEY_ENABLED, false).apply();
            Toast.makeText(this, "Grant Usage Access before continuing", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            refresh();
            return false;
        }
        if (!AndroidPermissions.hasOverlayAccess(this)) {
            Preferences.prefs(this).edit().putBoolean(Preferences.KEY_ENABLED, false).apply();
            Toast.makeText(this, "Grant Display Over Other Apps before monitoring", Toast.LENGTH_LONG).show();
            startActivity(overlaySettingsIntent());
            refresh();
            return false;
        }
        if (!hasAccountabilityNumber()) {
            Preferences.prefs(this).edit().putBoolean(Preferences.KEY_ENABLED, false).apply();
            UiStyle.styleInput(phoneInput, true);
            phoneInput.setError("Required before monitoring");
            Toast.makeText(this, "Add the accountability phone number before starting monitoring", Toast.LENGTH_LONG).show();
            refresh();
            return false;
        }
        if (!Preferences.hasMasterPin(this)) {
            Preferences.prefs(this).edit().putBoolean(Preferences.KEY_ENABLED, false).apply();
            Toast.makeText(this, "Set a master PIN before starting monitoring", Toast.LENGTH_LONG).show();
            refresh();
            return false;
        }
        if (!Preferences.hasLimitedApps(this)) {
            Preferences.prefs(this).edit().putBoolean(Preferences.KEY_ENABLED, false).apply();
            Toast.makeText(this, "Set at least one app limit before starting monitoring", Toast.LENGTH_LONG).show();
            refresh();
            return false;
        }
        return true;
    }

    private void promptForMasterPin(String title, Runnable onVerified, Runnable onCanceled) {
        if (!Preferences.hasMasterPin(this)) {
            Toast.makeText(this, "Set a master PIN first", Toast.LENGTH_LONG).show();
            if (onCanceled != null) {
                onCanceled.run();
            }
            refresh();
            return;
        }

        EditText input = dialogPinInput();
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("Enter your master PIN.")
                .setView(input)
                .setNegativeButton("Cancel", (d, which) -> {
                    if (onCanceled != null) {
                        onCanceled.run();
                    }
                })
                .setPositiveButton("Continue", null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String pin = input.getText().toString().trim();
                if (!Preferences.verifyMasterPin(this, pin)) {
                    input.setError("Invalid PIN");
                    return;
                }
                dialog.dismiss();
                onVerified.run();
            });
            input.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                    return true;
                }
                return false;
            });
            input.requestFocus();
            showKeyboard(input);
        });
        dialog.setOnCancelListener(d -> {
            if (onCanceled != null) {
                onCanceled.run();
            }
        });
        dialog.show();
    }

    private EditText dialogPinInput() {
        EditText input = new EditText(this);
        input.setHint("PIN");
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setInputType(InputType.TYPE_CLASS_PHONE);
        input.setTransformationMethod(PasswordTransformationMethod.getInstance());
        input.setTextColor(UiStyle.COLOR_TEXT_PRIMARY);
        input.setHintTextColor(UiStyle.COLOR_TEXT_MUTED);
        input.setOnClickListener(v -> showKeyboard(input));
        return input;
    }

    private void startMonitoring() {
        saveSettings();
        Preferences.prefs(this).edit().putBoolean(Preferences.KEY_ENABLED, true).apply();
        setMonitoringSwitchChecked(true);
        ensureNotificationPermission();
        startMonitoringService();
        refresh();
    }

    private void ensureEnabledServiceRunning() {
        if (Preferences.prefs(this).getBoolean(Preferences.KEY_ENABLED, false)
                && monitoringPrerequisitesMet()) {
            startMonitoringService();
        }
    }

    private void startMonitoringService() {
        Intent intent = new Intent(this, MonitoringService.class);
        intent.setAction(MonitoringService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void stopMonitoring() {
        Preferences.prefs(this).edit().putBoolean(Preferences.KEY_ENABLED, false).apply();
        Intent intent = new Intent(this, MonitoringService.class);
        intent.setAction(MonitoringService.ACTION_STOP);
        startService(intent);
        refresh();
    }

    private void setMonitoringSwitchChecked(boolean checked) {
        suppressSwitchChange = true;
        enabledSwitch.setChecked(checked);
        suppressSwitchChange = false;
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
    }

    private Intent overlaySettingsIntent() {
        return new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())
        );
    }

    private void openAppSelection(boolean authorizedWhileMonitoring) {
        Intent intent = new Intent(this, AppSelectionActivity.class);
        intent.putExtra(AppSelectionActivity.EXTRA_AUTHORIZED_WHILE_MONITORING, authorizedWhileMonitoring);
        startActivity(intent);
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

    private void scrollInputIntoView(EditText input) {
        input.postDelayed(() -> {
            android.view.ViewParent parent = input.getParent();
            while (parent instanceof View && !(parent instanceof ScrollView)) {
                parent = parent.getParent();
            }
            if (!(parent instanceof ScrollView)) {
                return;
            }

            ScrollView scrollView = (ScrollView) parent;
            Rect bounds = new Rect();
            input.getDrawingRect(bounds);
            scrollView.offsetDescendantRectToMyCoords(input, bounds);
            int targetY = Math.max(0, bounds.top - UiStyle.dp(this, 80));
            scrollView.smoothScrollTo(0, targetY);
        }, 250);
    }

    private void clearInputFocus(EditText input) {
        saveValidSettings();
        refresh();
        input.clearFocus();
        hideKeyboard(input);
    }

    private void hideKeyboard(View view) {
        InputMethodManager inputMethodManager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        inputMethodManager.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }
}
