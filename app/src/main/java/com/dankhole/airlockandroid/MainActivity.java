package com.dankhole.airlockandroid;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 1001;
    private static final String STATE_ACCOUNTABILITY_EXPANDED = "accountability_expanded";
    private static final String STATE_DIAGNOSTICS_EXPANDED = "diagnostics_expanded";
    private static final String STATE_EMERGENCY_EXPANDED = "emergency_expanded";
    private static final String STATE_MASTER_PIN_EXPANDED = "master_pin_expanded";
    private static final String STATE_PERMISSIONS_EXPANDED = "permissions_expanded";
    private static final BoundedTaskExecutor USAGE_REFRESH_EXECUTOR =
            new BoundedTaskExecutor(1, 30_000L, MainActivity::newUsageRefreshThread);

    private TextView statusText;
    private TextView monitoringDiagnosticsText;
    private TextView permissionsSummaryText;
    private TextView usageAccessRequiredText;
    private TextView overlayAccessRequiredText;
    private TextView notificationAccessText;
    private TextView batteryRestrictionText;
    private TextView accountabilityRequiredText;
    private TextView masterPinRequiredText;
    private TextView emergencyStatusText;
    private TextView generatedEmergencyCodesText;
    private TextView appLimitsRequiredText;
    private TextView monitoringStateText;
    private TextView currentPinLabel;
    private TextView usageSummaryText;
    private TextView controlsBlockedText;
    private LinearLayout usageList;
    private LinearLayout permissionsDetails;
    private LinearLayout accountabilityEditor;
    private LinearLayout masterPinEditor;
    private LinearLayout emergencyDetails;
    private Switch enabledSwitch;
    private EditText phoneInput;
    private EditText currentPinInput;
    private EditText newPinInput;
    private EditText confirmPinInput;
    private EditText emergencyCodeInput;
    private Button selectAppsButton;
    private Button notificationButton;
    private Button saveMasterPinButton;
    private Button useEmergencyCodeButton;
    private Button generateEmergencyCodesButton;
    private Button shareEmergencyCodesButton;
    private Button hideEmergencyCodesButton;
    private Button monitoringDiagnosticsButton;
    private Button permissionsDetailsButton;
    private Button accountabilityEditorButton;
    private Button masterPinEditorButton;
    private Button emergencyDetailsButton;
    private final List<String> visibleEmergencyCodes = new ArrayList<>();
    private boolean suppressSwitchChange;
    private boolean usageRefreshInFlight;
    private boolean activityResumed;
    private boolean activityDestroyed;
    private boolean diagnosticsExpanded;
    private boolean permissionsExpanded;
    private boolean accountabilityExpanded;
    private boolean masterPinExpanded;
    private boolean emergencyExpanded;
    private ScrollView dashboardContent;
    private ScrollView permissionGateContent;
    private UsageSummaryRenderer usageSummaryRenderer;
    private MasterPinPrompt masterPinPrompt;
    private PermissionSetupScreen permissionSetupScreen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiStyle.applyWindow(this);
        usageSummaryRenderer = new UsageSummaryRenderer(this);
        masterPinPrompt = new MasterPinPrompt(this, this::refresh);
        restoreDisclosureState(savedInstanceState);
        setContentView(buildContent());
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_DIAGNOSTICS_EXPANDED, diagnosticsExpanded);
        outState.putBoolean(STATE_PERMISSIONS_EXPANDED, permissionsExpanded);
        outState.putBoolean(STATE_ACCOUNTABILITY_EXPANDED, accountabilityExpanded);
        outState.putBoolean(STATE_MASTER_PIN_EXPANDED, masterPinExpanded);
        outState.putBoolean(STATE_EMERGENCY_EXPANDED, emergencyExpanded);
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        ensureEnabledServiceRunning();
        refresh();
        requestUsageRefresh();
        statusText.postDelayed(() -> {
            if (activityResumed && !activityDestroyed) {
                refresh();
            }
        }, 1_200L);
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        hideEmergencyCodes();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        activityDestroyed = true;
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            refresh();
        }
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

    private void restoreDisclosureState(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            accountabilityExpanded = !Preferences.hasAccountabilityNumber(this);
            masterPinExpanded = !Preferences.hasMasterPin(this);
            return;
        }
        diagnosticsExpanded = savedInstanceState.getBoolean(
                STATE_DIAGNOSTICS_EXPANDED,
                false
        );
        permissionsExpanded = savedInstanceState.getBoolean(
                STATE_PERMISSIONS_EXPANDED,
                false
        );
        accountabilityExpanded = savedInstanceState.getBoolean(
                STATE_ACCOUNTABILITY_EXPANDED,
                !Preferences.hasAccountabilityNumber(this)
        );
        masterPinExpanded = savedInstanceState.getBoolean(
                STATE_MASTER_PIN_EXPANDED,
                !Preferences.hasMasterPin(this)
        );
        emergencyExpanded = savedInstanceState.getBoolean(
                STATE_EMERGENCY_EXPANDED,
                false
        );
    }

    private View buildContent() {
        FrameLayout host = new FrameLayout(this);
        host.setBackgroundColor(UiStyle.COLOR_BACKGROUND);

        dashboardContent = buildDashboardContent();
        permissionSetupScreen = new PermissionSetupScreen(
                this,
                () -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)),
                () -> startActivity(overlaySettingsIntent()),
                this::requestNotificationPermissionOrOpenSettings
        );
        permissionGateContent = permissionSetupScreen.view();

        FrameLayout.LayoutParams screenParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        host.addView(dashboardContent, screenParams);
        host.addView(permissionGateContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        refresh();
        return host;
    }

    private ScrollView buildDashboardContent() {
        SharedPreferences preferences = Preferences.prefs(this);

        ScrollView scrollView = UiStyle.screenScroll(this);
        scrollView.setId(R.id.main_scroll);
        LinearLayout root = UiStyle.screenRoot(this);
        UiStyle.applyScreenInsetsPadding(scrollView, root, 20, 20, 20, 20);
        UiStyle.attachScreenContent(scrollView, root);

        root.addView(UiStyle.screenTitle(this, getString(R.string.main_title)), UiStyle.fullWidth(this, 2));
        root.addView(UiStyle.bodyText(
                this,
                getString(R.string.main_tagline)
        ), UiStyle.fullWidth(this, 18));
        root.addView(new GooseMascotView(this), UiStyle.gooseBannerParams(this));

        root.addView(buildMonitoringControlsCard(), UiStyle.fullWidth(this, 14));
        root.addView(buildUsageCard(), UiStyle.fullWidth(this, 14));
        root.addView(buildMonitoringStatusCard(), UiStyle.fullWidth(this, 14));
        root.addView(buildAccountabilityCard(preferences), UiStyle.fullWidth(this, 14));
        root.addView(buildMasterPinCard(), UiStyle.fullWidth(this, 14));
        root.addView(buildAppLimitsCard(), UiStyle.fullWidth(this, 14));
        root.addView(buildEmergencyAccessCard(), UiStyle.fullWidth(this, 14));
        root.addView(buildPermissionsCard(), UiStyle.fullWidth(this, 4));

        TextWatcher watcher = autoSaveWatcher();
        phoneInput.addTextChangedListener(watcher);

        return scrollView;
    }

    private LinearLayout buildMonitoringStatusCard() {
        LinearLayout card = sectionCard(
                getString(R.string.section_monitoring_status),
                ""
        );

        statusText = UiStyle.statusText(this);
        statusText.setId(R.id.main_monitoring_status);
        statusText.setTextSize(16);
        card.addView(statusText, UiStyle.fullWidth(this, 10));

        monitoringStateText = UiStyle.bodyText(this, "");
        card.addView(monitoringStateText, UiStyle.fullWidth(this, 10));

        monitoringDiagnosticsText = UiStyle.helperText(this, "");
        card.addView(monitoringDiagnosticsText, UiStyle.fullWidth(this, 8));
        monitoringDiagnosticsButton = UiStyle.quietButton(
                this,
                getString(R.string.show_monitoring_details)
        );
        monitoringDiagnosticsButton.setId(R.id.main_monitoring_details_toggle);
        monitoringDiagnosticsButton.setOnClickListener(v -> {
            diagnosticsExpanded = !diagnosticsExpanded;
            updateDisclosure(
                    monitoringDiagnosticsText,
                    monitoringDiagnosticsButton,
                    diagnosticsExpanded,
                    R.string.show_monitoring_details,
                    R.string.hide_monitoring_details
            );
        });
        card.addView(monitoringDiagnosticsButton, UiStyle.buttonParams(this));
        updateDisclosure(
                monitoringDiagnosticsText,
                monitoringDiagnosticsButton,
                diagnosticsExpanded,
                R.string.show_monitoring_details,
                R.string.hide_monitoring_details
        );
        return card;
    }

    private LinearLayout buildPermissionsCard() {
        LinearLayout card = sectionCard(
                getString(R.string.section_permissions),
                ""
        );

        permissionsSummaryText = UiStyle.statusText(this);
        permissionsSummaryText.setId(R.id.main_permissions_status);
        card.addView(permissionsSummaryText, UiStyle.fullWidth(this, 10));

        permissionsDetails = new LinearLayout(this);
        permissionsDetails.setOrientation(LinearLayout.VERTICAL);

        usageAccessRequiredText = UiStyle.statusText(this);
        permissionsDetails.addView(usageAccessRequiredText, UiStyle.fullWidth(this, 10));

        Button usageAccessButton = UiStyle.primaryButton(this, getString(R.string.open_usage_access));
        usageAccessButton.setId(R.id.main_usage_access_button);
        usageAccessButton.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        permissionsDetails.addView(usageAccessButton, UiStyle.buttonParams(this));

        overlayAccessRequiredText = UiStyle.statusText(this);
        permissionsDetails.addView(overlayAccessRequiredText, UiStyle.fullWidth(this, 10));

        Button overlayButton = UiStyle.secondaryButton(this, getString(R.string.open_overlay_access));
        overlayButton.setId(R.id.main_overlay_access_button);
        overlayButton.setOnClickListener(v -> startActivity(overlaySettingsIntent()));
        permissionsDetails.addView(overlayButton, UiStyle.buttonParams(this));

        notificationAccessText = UiStyle.statusText(this);
        permissionsDetails.addView(notificationAccessText, UiStyle.fullWidth(this, 10));

        notificationButton = UiStyle.secondaryButton(this, getString(R.string.allow_notifications));
        notificationButton.setId(R.id.main_notification_button);
        notificationButton.setOnClickListener(v -> requestNotificationPermissionOrOpenSettings());
        permissionsDetails.addView(notificationButton, UiStyle.buttonParams(this));

        batteryRestrictionText = UiStyle.statusText(this);
        permissionsDetails.addView(batteryRestrictionText, UiStyle.fullWidth(this, 10));

        Button batterySettingsButton = UiStyle.secondaryButton(this, getString(R.string.open_app_settings));
        batterySettingsButton.setId(R.id.main_battery_settings_button);
        batterySettingsButton.setOnClickListener(v -> startActivity(appDetailsSettingsIntent()));
        permissionsDetails.addView(batterySettingsButton, UiStyle.buttonParams(this));
        card.addView(permissionsDetails, UiStyle.fullWidth(this, 4));

        permissionsDetailsButton = UiStyle.quietButton(this, getString(R.string.review_android_access));
        permissionsDetailsButton.setId(R.id.main_permissions_toggle);
        permissionsDetailsButton.setOnClickListener(v -> {
            permissionsExpanded = !permissionsExpanded;
            updateDisclosure(
                    permissionsDetails,
                    permissionsDetailsButton,
                    permissionsExpanded,
                    R.string.review_android_access,
                    R.string.hide_android_access
            );
        });
        card.addView(permissionsDetailsButton, UiStyle.buttonParams(this));

        card.addView(
                UiStyle.helperText(this, getString(R.string.privacy_local_summary)),
                UiStyle.fullWidth(this, 6)
        );
        Button privacyButton = UiStyle.quietButton(this, getString(R.string.open_privacy_policy));
        privacyButton.setId(R.id.main_privacy_button);
        privacyButton.setOnClickListener(view -> AppLinks.openPrivacyPolicy(this));
        card.addView(privacyButton, UiStyle.buttonParams(this));
        return card;
    }

    private LinearLayout buildAccountabilityCard(SharedPreferences preferences) {
        LinearLayout card = sectionCard(
                getString(R.string.section_keyholder),
                getString(R.string.section_keyholder_helper)
        );

        accountabilityRequiredText = UiStyle.statusText(this);
        accountabilityRequiredText.setId(R.id.main_keyholder_status);
        card.addView(accountabilityRequiredText, UiStyle.fullWidth(this, 10));

        accountabilityEditor = new LinearLayout(this);
        accountabilityEditor.setOrientation(LinearLayout.VERTICAL);
        TextView phoneLabel = UiStyle.fieldLabel(this, getString(R.string.keyholder_phone_label));
        phoneInput = UiStyle.inputField(this, getString(R.string.phone_number_hint));
        phoneInput.setId(R.id.main_keyholder_phone);
        phoneLabel.setLabelFor(phoneInput.getId());
        KeyboardHelper.prepareNumericInput(phoneInput);
        phoneInput.setText(preferences.getString(Preferences.KEY_ACCOUNTABILITY_NUMBER, ""));
        configureEditableInput(phoneInput);
        accountabilityEditor.addView(phoneLabel, UiStyle.fullWidth(this, 6));
        accountabilityEditor.addView(phoneInput, UiStyle.fullWidth(this, 4));
        card.addView(accountabilityEditor, UiStyle.fullWidth(this, 4));

        accountabilityEditorButton = UiStyle.quietButton(this, getString(R.string.change_keyholder_number));
        accountabilityEditorButton.setId(R.id.main_keyholder_toggle);
        accountabilityEditorButton.setOnClickListener(v -> {
            accountabilityExpanded = !accountabilityExpanded;
            updateDisclosure(
                    accountabilityEditor,
                    accountabilityEditorButton,
                    accountabilityExpanded,
                    R.string.change_keyholder_number,
                    R.string.done_keyholder_number
            );
            if (accountabilityExpanded) {
                phoneInput.requestFocus();
            }
        });
        card.addView(accountabilityEditorButton, UiStyle.buttonParams(this));
        return card;
    }

    private LinearLayout buildMasterPinCard() {
        LinearLayout card = sectionCard(
                getString(R.string.section_master_pin),
                getString(R.string.section_master_pin_helper)
        );

        masterPinRequiredText = UiStyle.statusText(this);
        masterPinRequiredText.setId(R.id.main_master_pin_status);
        card.addView(masterPinRequiredText, UiStyle.fullWidth(this, 10));

        masterPinEditor = new LinearLayout(this);
        masterPinEditor.setOrientation(LinearLayout.VERTICAL);
        currentPinLabel = UiStyle.fieldLabel(this, getString(R.string.current_master_pin_label));
        currentPinInput = pinInput(getString(R.string.current_pin_hint));
        currentPinInput.setId(R.id.main_master_pin_current);
        currentPinLabel.setLabelFor(currentPinInput.getId());
        masterPinEditor.addView(currentPinLabel, UiStyle.fullWidth(this, 6));
        masterPinEditor.addView(currentPinInput, UiStyle.fullWidth(this, 10));

        TextView newPinLabel = UiStyle.fieldLabel(this, getString(R.string.new_master_pin_label));
        newPinInput = pinInput(getString(R.string.new_pin_hint));
        newPinInput.setId(R.id.main_master_pin_new);
        newPinLabel.setLabelFor(newPinInput.getId());
        masterPinEditor.addView(newPinLabel, UiStyle.fullWidth(this, 6));
        masterPinEditor.addView(newPinInput, UiStyle.fullWidth(this, 10));

        TextView confirmPinLabel = UiStyle.fieldLabel(this, getString(R.string.confirm_master_pin_label));
        confirmPinInput = pinInput(getString(R.string.confirm_pin_hint));
        confirmPinInput.setId(R.id.main_master_pin_confirm);
        confirmPinLabel.setLabelFor(confirmPinInput.getId());
        masterPinEditor.addView(confirmPinLabel, UiStyle.fullWidth(this, 6));
        masterPinEditor.addView(confirmPinInput, UiStyle.fullWidth(this, 10));

        saveMasterPinButton = UiStyle.secondaryButton(this, getString(R.string.set_master_pin));
        saveMasterPinButton.setId(R.id.main_master_pin_save);
        saveMasterPinButton.setOnClickListener(v -> saveMasterPin());
        masterPinEditor.addView(saveMasterPinButton, UiStyle.buttonParams(this));
        card.addView(masterPinEditor, UiStyle.fullWidth(this, 4));

        masterPinEditorButton = UiStyle.quietButton(this, getString(R.string.change_master_pin));
        masterPinEditorButton.setId(R.id.main_master_pin_toggle);
        masterPinEditorButton.setOnClickListener(v -> {
            masterPinExpanded = !masterPinExpanded;
            updateDisclosure(
                    masterPinEditor,
                    masterPinEditorButton,
                    masterPinExpanded,
                    R.string.change_master_pin,
                    R.string.cancel_pin_change
            );
        });
        card.addView(masterPinEditorButton, UiStyle.buttonParams(this));
        return card;
    }

    private LinearLayout buildEmergencyAccessCard() {
        LinearLayout card = sectionCard(
                getString(R.string.section_emergency),
                getString(R.string.section_emergency_helper)
        );

        emergencyStatusText = UiStyle.statusText(this);
        emergencyStatusText.setId(R.id.main_emergency_status);
        card.addView(emergencyStatusText, UiStyle.fullWidth(this, 12));

        emergencyDetails = new LinearLayout(this);
        emergencyDetails.setOrientation(LinearLayout.VERTICAL);
        TextView emergencyCodeLabel = UiStyle.fieldLabel(this, getString(R.string.emergency_use_label));
        emergencyCodeInput = pinInput(getString(R.string.emergency_code_hint));
        emergencyCodeInput.setId(R.id.main_emergency_code);
        emergencyCodeLabel.setLabelFor(emergencyCodeInput.getId());
        emergencyCodeInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
        emergencyDetails.addView(emergencyCodeLabel, UiStyle.fullWidth(this, 6));
        emergencyDetails.addView(emergencyCodeInput, UiStyle.fullWidth(this, 10));

        useEmergencyCodeButton = UiStyle.dangerButton(this, getString(R.string.emergency_use_button));
        useEmergencyCodeButton.setId(R.id.main_emergency_use);
        useEmergencyCodeButton.setOnClickListener(v -> useEmergencyCode());
        emergencyDetails.addView(useEmergencyCodeButton, UiStyle.buttonParams(this));

        emergencyDetails.addView(
                UiStyle.fieldLabel(this, getString(R.string.emergency_keyholder_setup)),
                UiStyle.fullWidth(this, 6)
        );
        TextView setupDetail = UiStyle.helperText(
                this,
                getString(R.string.emergency_setup_detail)
        );
        emergencyDetails.addView(setupDetail, UiStyle.fullWidth(this, 10));

        generateEmergencyCodesButton = UiStyle.secondaryButton(
                this,
                getString(R.string.emergency_generate_three)
        );
        generateEmergencyCodesButton.setId(R.id.main_emergency_generate);
        generateEmergencyCodesButton.setOnClickListener(v -> promptForMasterPin(
                getString(R.string.emergency_generate_prompt),
                this::confirmEmergencyCodeReplacement,
                null
        ));
        emergencyDetails.addView(generateEmergencyCodesButton, UiStyle.buttonParams(this));

        generatedEmergencyCodesText = UiStyle.statusText(this);
        generatedEmergencyCodesText.setVisibility(View.GONE);
        emergencyDetails.addView(generatedEmergencyCodesText, UiStyle.fullWidth(this, 10));

        shareEmergencyCodesButton = UiStyle.primaryButton(this, getString(R.string.emergency_share));
        shareEmergencyCodesButton.setOnClickListener(v -> shareEmergencyCodes());
        shareEmergencyCodesButton.setVisibility(View.GONE);
        emergencyDetails.addView(shareEmergencyCodesButton, UiStyle.buttonParams(this));

        hideEmergencyCodesButton = UiStyle.secondaryButton(this, getString(R.string.emergency_hide_codes));
        hideEmergencyCodesButton.setOnClickListener(v -> hideEmergencyCodes());
        hideEmergencyCodesButton.setVisibility(View.GONE);
        emergencyDetails.addView(hideEmergencyCodesButton, UiStyle.buttonParams(this));
        card.addView(emergencyDetails, UiStyle.fullWidth(this, 4));

        emergencyDetailsButton = UiStyle.quietButton(this, getString(R.string.emergency_open));
        emergencyDetailsButton.setId(R.id.main_emergency_toggle);
        emergencyDetailsButton.setOnClickListener(v -> {
            emergencyExpanded = !emergencyExpanded;
            updateDisclosure(
                    emergencyDetails,
                    emergencyDetailsButton,
                    emergencyExpanded,
                    R.string.emergency_open,
                    R.string.emergency_close
            );
        });
        card.addView(emergencyDetailsButton, UiStyle.buttonParams(this));
        return card;
    }

    private LinearLayout buildAppLimitsCard() {
        LinearLayout card = sectionCard(
                getString(R.string.section_guarded_apps),
                ""
        );

        appLimitsRequiredText = UiStyle.statusText(this);
        appLimitsRequiredText.setId(R.id.main_app_limits_status);
        card.addView(appLimitsRequiredText, UiStyle.fullWidth(this, 10));

        selectAppsButton = UiStyle.primaryButton(this, getString(R.string.set_goose_limits));
        selectAppsButton.setId(R.id.main_app_limits_button);
        selectAppsButton.setOnClickListener(v -> {
            if (!AndroidPermissions.hasUsageAccess(this)) {
                Toast.makeText(this, R.string.app_picker_usage_required_toast, Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                return;
            }
            if (Preferences.isMonitoringRequested(this)) {
                promptForMasterPin(
                        getString(R.string.change_goose_list_prompt),
                        this::openAuthorizedAppSelection,
                        null
                );
                return;
            }
            openAppSelection(null);
        });
        card.addView(selectAppsButton, UiStyle.buttonParams(this));
        return card;
    }

    private LinearLayout buildUsageCard() {
        LinearLayout card = sectionCard(
                getString(R.string.section_usage),
                ""
        );

        usageSummaryText = UiStyle.statusText(this);
        usageSummaryText.setId(R.id.main_usage_status);
        card.addView(usageSummaryText, UiStyle.fullWidth(this, 12));

        usageList = new LinearLayout(this);
        usageList.setId(R.id.main_usage_list);
        usageList.setOrientation(LinearLayout.VERTICAL);
        card.addView(usageList);
        return card;
    }

    private LinearLayout buildMonitoringControlsCard() {
        LinearLayout card = sectionCard(
                getString(R.string.section_controls),
                getString(R.string.section_controls_helper)
        );

        enabledSwitch = new Switch(this);
        enabledSwitch.setId(R.id.main_monitoring_switch);
        enabledSwitch.setText(R.string.goose_duty_toggle);
        enabledSwitch.setTextSize(16);
        enabledSwitch.setTextColor(UiStyle.COLOR_TEXT_PRIMARY);
        enabledSwitch.setMinHeight(UiStyle.dp(this, 48));
        enabledSwitch.setPadding(0, 0, 0, UiStyle.dp(this, 8));
        enabledSwitch.setChecked(Preferences.isMonitoringRequested(this));
        enabledSwitch.setOnCheckedChangeListener(this::onEnabledChanged);
        card.addView(enabledSwitch, UiStyle.fullWidth(this, 4));

        controlsBlockedText = UiStyle.statusText(this);
        controlsBlockedText.setId(R.id.main_monitoring_controls_status);
        card.addView(controlsBlockedText, UiStyle.fullWidth(this, 10));

        return card;
    }

    private LinearLayout sectionCard(String title, String helper) {
        LinearLayout card = UiStyle.card(this);
        card.addView(UiStyle.sectionTitle(this, title), UiStyle.fullWidth(this, 4));
        if (!helper.isEmpty()) {
            card.addView(UiStyle.helperText(this, helper), UiStyle.fullWidth(this, 14));
        }
        return card;
    }

    private void updateDisclosure(
            View content,
            Button button,
            boolean expanded,
            int collapsedLabelResource,
            int expandedLabelResource
    ) {
        String collapsedLabel = getString(collapsedLabelResource);
        String expandedLabel = getString(expandedLabelResource);
        String label = expanded ? expandedLabel : collapsedLabel;
        content.setVisibility(expanded ? View.VISIBLE : View.GONE);
        button.setText(label);
        button.setContentDescription(getString(
                expanded ? R.string.disclosure_expanded : R.string.disclosure_collapsed,
                label
        ));
    }

    private void onEnabledChanged(CompoundButton button, boolean checked) {
        if (suppressSwitchChange) {
            return;
        }
        boolean enabled = Preferences.isMonitoringRequested(this);
        if (checked == enabled) {
            return;
        }
        if (checked) {
            if (!requireMonitoringPrerequisites()) {
                setMonitoringSwitchChecked(false);
                return;
            }
            promptForMasterPin(
                    getString(R.string.start_duty_prompt),
                    this::startMonitoring,
                    () -> setMonitoringSwitchChecked(false)
            );
        } else {
            promptForMasterPin(
                    getString(R.string.stop_duty_prompt),
                    this::stopMonitoring,
                    () -> setMonitoringSwitchChecked(true)
            );
        }
    }

    private EditText pinInput(String hint) {
        EditText input = UiStyle.inputField(this, hint);
        configureEditableInput(input);
        KeyboardHelper.prepareSecureNumericInput(input);
        input.setTransformationMethod(PasswordTransformationMethod.getInstance());
        return input;
    }

    private void configureEditableInput(EditText input) {
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setSelectAllOnFocus(true);
        UiStyle.styleInput(input, false);
        KeyboardHelper.installKeyboardInteraction(
                this,
                input,
                () -> scrollInputIntoView(input)
        );
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
            return;
        }
        Preferences.prefs(this).edit()
                .putString(Preferences.KEY_ACCOUNTABILITY_NUMBER, currentPhoneDigits())
                .apply();
    }

    private void saveValidSettings() {
        SharedPreferences.Editor editor = Preferences.prefs(this).edit();
        if (phoneNumberValid()) {
            editor.putString(Preferences.KEY_ACCOUNTABILITY_NUMBER, currentPhoneDigits());
        } else {
            editor.remove(Preferences.KEY_ACCOUNTABILITY_NUMBER);
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
            currentPinInput.setError(getString(R.string.pin_current_required));
            Toast.makeText(this, R.string.pin_current_required_toast, Toast.LENGTH_LONG).show();
            return;
        }
        if (!isValidPin(newPin)) {
            UiStyle.styleInput(newPinInput, true);
            newPinInput.setError(getString(R.string.pin_minimum_error));
            Toast.makeText(this, R.string.pin_minimum_toast, Toast.LENGTH_LONG).show();
            return;
        }
        if (!newPin.equals(confirmPin)) {
            UiStyle.styleInput(confirmPinInput, true);
            confirmPinInput.setError(getString(R.string.pin_mismatch_error));
            Toast.makeText(this, R.string.pin_mismatch_toast, Toast.LENGTH_LONG).show();
            return;
        }

        Preferences.setMasterPin(this, newPin);
        masterPinExpanded = false;
        currentPinInput.setText("");
        newPinInput.setText("");
        confirmPinInput.setText("");
        clearInputFocus(newPinInput);
        Toast.makeText(
                this,
                hasMasterPin ? R.string.pin_changed_toast : R.string.pin_set_toast,
                Toast.LENGTH_SHORT
        ).show();
        refresh();
    }

    private void confirmEmergencyCodeReplacement() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.emergency_replace_title)
                .setMessage(R.string.emergency_replace_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                        R.string.emergency_replace_action,
                        (dialog, which) -> generateEmergencyCodes()
                )
                .show();
    }

    private void generateEmergencyCodes() {
        visibleEmergencyCodes.clear();
        visibleEmergencyCodes.addAll(Preferences.replaceEmergencyCodes(this));
        if (visibleEmergencyCodes.isEmpty()) {
            Toast.makeText(this, R.string.emergency_save_failed, Toast.LENGTH_LONG).show();
            refresh();
            return;
        }

        StringBuilder display = new StringBuilder(getString(
                R.string.emergency_codes_display_intro
        ));
        for (int index = 0; index < visibleEmergencyCodes.size(); index++) {
            display.append(index + 1)
                    .append(". ")
                    .append(visibleEmergencyCodes.get(index));
            if (index < visibleEmergencyCodes.size() - 1) {
                display.append('\n');
            }
        }
        generatedEmergencyCodesText.setText(display.toString());
        UiStyle.setStatus(generatedEmergencyCodesText, UiStyle.STATUS_WARNING);
        generatedEmergencyCodesText.setVisibility(View.VISIBLE);
        shareEmergencyCodesButton.setVisibility(View.VISIBLE);
        hideEmergencyCodesButton.setVisibility(View.VISIBLE);
        emergencyExpanded = true;
        updateDisclosure(
                emergencyDetails,
                emergencyDetailsButton,
                true,
                R.string.emergency_open,
                R.string.emergency_close
        );
        Toast.makeText(this, R.string.emergency_codes_created, Toast.LENGTH_LONG).show();
        refresh();
    }

    private void shareEmergencyCodes() {
        if (visibleEmergencyCodes.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder(getString(
                R.string.emergency_codes_share_intro
        ));
        for (String code : visibleEmergencyCodes) {
            message.append("\n").append(code);
        }
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, message.toString());
        try {
            startActivity(Intent.createChooser(
                    shareIntent,
                    getString(R.string.emergency_codes_share_title)
            ));
        } catch (RuntimeException ignored) {
            Toast.makeText(this, R.string.emergency_codes_share_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private void hideEmergencyCodes() {
        visibleEmergencyCodes.clear();
        generatedEmergencyCodesText.setText("");
        generatedEmergencyCodesText.setVisibility(View.GONE);
        shareEmergencyCodesButton.setVisibility(View.GONE);
        hideEmergencyCodesButton.setVisibility(View.GONE);
    }

    private void useEmergencyCode() {
        boolean enabled = Preferences.isMonitoringRequested(this);
        if (!enabled) {
            emergencyCodeInput.setError(getString(R.string.emergency_duty_off_error));
            Toast.makeText(this, R.string.emergency_duty_off_toast, Toast.LENGTH_LONG).show();
            return;
        }
        if (Preferences.isEmergencyPauseActive(this)) {
            Toast.makeText(this, R.string.emergency_already_active, Toast.LENGTH_LONG).show();
            refresh();
            return;
        }

        String code = emergencyCodeInput.getText().toString().trim();
        if (code.length() != 8) {
            emergencyCodeInput.setError(getString(R.string.emergency_code_length_error));
            return;
        }
        if (!Preferences.consumeEmergencyCode(this, code)) {
            emergencyCodeInput.setError(getString(R.string.emergency_code_invalid_error));
            Toast.makeText(this, R.string.emergency_code_invalid_toast, Toast.LENGTH_LONG).show();
            return;
        }

        emergencyCodeInput.setText("");
        clearInputFocus(emergencyCodeInput);
        hideEmergencyCodes();
        startMonitoringService();
        Toast.makeText(this, R.string.emergency_active_toast, Toast.LENGTH_LONG).show();
        refresh();
    }

    private String formatEmergencyPauseEnd(long pauseUntilMs) {
        java.util.Date pauseEnd = new java.util.Date(pauseUntilMs);
        return getString(
                R.string.date_at_time,
                android.text.format.DateFormat.getMediumDateFormat(this).format(pauseEnd),
                android.text.format.DateFormat.getTimeFormat(this).format(pauseEnd)
        );
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
        return phoneNumberValid();
    }

    private boolean settingsValuesValid() {
        return phoneNumberValid();
    }

    private boolean phoneNumberValid() {
        return currentPhoneDigits().length() == 10;
    }

    private String currentPhoneDigits() {
        return Preferences.normalizedPhoneNumber(phoneInput.getText().toString());
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
        boolean notifications = AndroidPermissions.hasNotificationAccess(this);
        boolean requiredAccessReady = RequiredAccessPolicy.isComplete(
                usage,
                overlay,
                notifications
        );
        permissionSetupScreen.render(usage, overlay, notifications);
        permissionGateContent.setVisibility(requiredAccessReady ? View.GONE : View.VISIBLE);
        dashboardContent.setVisibility(requiredAccessReady ? View.VISIBLE : View.GONE);
        boolean hasAccountabilityNumber = hasAccountabilityNumber();
        boolean hasMasterPin = Preferences.hasMasterPin(this);
        Set<String> selectedPackages = Preferences.selectedPackages(this);
        boolean hasLimitedApps = !selectedPackages.isEmpty();
        boolean readyToMonitor = usage && overlay && hasAccountabilityNumber && hasMasterPin && hasLimitedApps;
        boolean enabled = Preferences.isMonitoringRequested(this);
        boolean serviceRunning = MonitoringHealth.isServiceRunning();
        boolean backgroundRestricted = AndroidPermissions.isBackgroundRestricted(this);
        String monitoringIssue = MonitoringHealth.currentIssue(this);
        long emergencyPauseUntilMs = Preferences.emergencyPauseUntilMs(this);
        boolean emergencyPauseStored = emergencyPauseUntilMs > System.currentTimeMillis();
        int selectedCount = selectedPackages.size();
        boolean emergencyPaused = enabled && emergencyPauseStored;

        String stateTitle;
        String stateDetail;
        int stateStyle;
        if (emergencyPaused && serviceRunning) {
            stateTitle = getString(R.string.state_emergency_title);
            stateDetail = getString(
                    R.string.state_emergency_detail,
                    formatEmergencyPauseEnd(emergencyPauseUntilMs)
            );
            stateStyle = UiStyle.STATUS_WARNING;
        } else if (enabled && !readyToMonitor) {
            stateTitle = getString(R.string.state_prerequisite_title);
            stateDetail = getString(
                    R.string.state_prerequisite_detail,
                    firstMissingRequirement(
                            usage,
                            overlay,
                            hasAccountabilityNumber,
                            hasMasterPin,
                            hasLimitedApps
                    )
            );
            stateStyle = UiStyle.STATUS_REQUIRED;
        } else if (enabled && backgroundRestricted) {
            stateTitle = getString(R.string.state_battery_title);
            stateDetail = getString(R.string.state_battery_detail);
            stateStyle = UiStyle.STATUS_REQUIRED;
        } else if (enabled && !serviceRunning) {
            stateTitle = monitoringIssue.isEmpty()
                    ? getString(R.string.state_starting_again_title)
                    : getString(R.string.state_not_running_title);
            stateDetail = monitoringIssue.isEmpty()
                    ? getString(R.string.state_starting_detail)
                    : monitoringIssue;
            stateStyle = monitoringIssue.isEmpty()
                    ? UiStyle.STATUS_WARNING
                    : UiStyle.STATUS_REQUIRED;
        } else if (enabled && !monitoringIssue.isEmpty()) {
            stateTitle = monitoringIssue.equals(getString(R.string.monitoring_issue_starting))
                    ? getString(R.string.state_starting_title)
                    : getString(R.string.state_recovering_title);
            stateDetail = monitoringIssue;
            stateStyle = UiStyle.STATUS_WARNING;
        } else if (enabled && !notifications) {
            stateTitle = getString(R.string.state_notifications_title);
            stateDetail = getString(R.string.state_notifications_detail);
            stateStyle = UiStyle.STATUS_WARNING;
        } else if (enabled) {
            stateTitle = getString(R.string.state_on_title);
            stateDetail = getString(R.string.state_on_detail);
            stateStyle = UiStyle.STATUS_READY;
        } else if (readyToMonitor) {
            stateTitle = getString(R.string.state_ready_title);
            stateDetail = getString(R.string.state_ready_detail);
            stateStyle = UiStyle.STATUS_READY;
        } else {
            stateTitle = getString(R.string.state_setup_title);
            stateDetail = getString(R.string.state_setup_detail);
            stateStyle = UiStyle.STATUS_REQUIRED;
        }
        String recentRecovery = MonitoringHealth.recentRecovery(this);
        statusText.setText(stateTitle);
        UiStyle.setStatus(statusText, stateStyle);
        monitoringStateText.setText(stateDetail);
        String recoveryDetail = recentRecovery.isEmpty()
                ? ""
                : getString(R.string.monitoring_last_recovery, recentRecovery);
        monitoringDiagnosticsText.setText(getString(
                R.string.monitoring_diagnostics,
                getString(serviceRunning
                        ? R.string.monitoring_service_running
                        : R.string.monitoring_service_not_running),
                getString(backgroundRestricted
                        ? R.string.monitoring_battery_restricted
                        : R.string.monitoring_battery_not_restricted),
                recoveryDetail
        ));
        updateDisclosure(
                monitoringDiagnosticsText,
                monitoringDiagnosticsButton,
                diagnosticsExpanded,
                R.string.show_monitoring_details,
                R.string.hide_monitoring_details
        );

        setRequirement(
                usageAccessRequiredText,
                usage,
                getString(usage
                        ? R.string.usage_access_ready_title
                        : R.string.usage_access_required_title),
                usage
                        ? getString(R.string.usage_access_ready_detail)
                        : getString(R.string.usage_access_required_main_detail)
        );
        setRequirement(
                overlayAccessRequiredText,
                overlay,
                getString(overlay
                        ? R.string.overlay_access_ready_title
                        : R.string.overlay_access_required_title),
                overlay
                        ? getString(R.string.overlay_access_ready_detail)
                        : getString(R.string.overlay_access_required_detail)
        );
        notificationAccessText.setVisibility(View.VISIBLE);
        notificationButton.setVisibility(View.VISIBLE);
        notificationAccessText.setText(notifications
                ? R.string.notifications_ready_detail
                : R.string.notifications_hidden_detail);
        UiStyle.setStatus(
                notificationAccessText,
                notifications ? UiStyle.STATUS_READY : UiStyle.STATUS_REQUIRED
        );
        boolean runtimeNotificationPermissionMissing =
                !AndroidPermissions.hasNotificationRuntimePermission(this);
        notificationButton.setText(notifications
                ? R.string.open_notification_settings
                : Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && runtimeNotificationPermissionMissing
                ? R.string.allow_notifications
                : R.string.open_notification_settings);
        setRequirement(
                batteryRestrictionText,
                !backgroundRestricted,
                backgroundRestricted
                        ? getString(R.string.battery_restricted_title)
                        : getString(R.string.battery_ready_title),
                backgroundRestricted
                        ? getString(R.string.battery_restricted_detail)
                        : getString(R.string.battery_ready_detail)
        );
        permissionsSummaryText.setText(!requiredAccessReady
                ? R.string.permissions_required_summary
                : backgroundRestricted
                ? R.string.permissions_battery_summary
                : R.string.permissions_ready_summary);
        UiStyle.setStatus(
                permissionsSummaryText,
                !requiredAccessReady
                        ? UiStyle.STATUS_REQUIRED
                        : backgroundRestricted
                        ? UiStyle.STATUS_WARNING
                        : UiStyle.STATUS_READY
        );
        updateDisclosure(
                permissionsDetails,
                permissionsDetailsButton,
                permissionsExpanded,
                R.string.review_android_access,
                R.string.hide_android_access
        );
        permissionsDetailsButton.setVisibility(View.VISIBLE);
        String accountabilityDetail = hasAccountabilityNumber
                ? enabled
                ? getString(R.string.keyholder_locked_detail)
                : getString(R.string.keyholder_ready_detail)
                : getString(R.string.keyholder_required_detail);
        setRequirement(
                accountabilityRequiredText,
                hasAccountabilityNumber,
                getString(hasAccountabilityNumber
                        ? R.string.keyholder_ready_title
                        : R.string.keyholder_required_title),
                accountabilityDetail
        );
        phoneInput.setEnabled(!enabled);
        UiStyle.styleInput(phoneInput, !hasAccountabilityNumber);
        String phoneDigits = currentPhoneDigits();
        phoneInput.setError(hasAccountabilityNumber || phoneDigits.isEmpty()
                ? null
                : getString(R.string.phone_length_error));
        if (!hasAccountabilityNumber) {
            accountabilityExpanded = true;
        } else if (enabled) {
            accountabilityExpanded = false;
        }
        updateDisclosure(
                accountabilityEditor,
                accountabilityEditorButton,
                accountabilityExpanded,
                R.string.change_keyholder_number,
                R.string.done_keyholder_number
        );
        accountabilityEditorButton.setVisibility(
                hasAccountabilityNumber && !enabled ? View.VISIBLE : View.GONE
        );

        currentPinLabel.setVisibility(hasMasterPin ? View.VISIBLE : View.GONE);
        currentPinInput.setVisibility(hasMasterPin ? View.VISIBLE : View.GONE);
        confirmPinInput.setVisibility(View.VISIBLE);
        currentPinInput.setError(null);
        newPinInput.setError(null);
        confirmPinInput.setError(null);
        saveMasterPinButton.setText(hasMasterPin
                ? R.string.change_master_pin_emphasis
                : R.string.set_master_pin_emphasis);
        setRequirement(
                masterPinRequiredText,
                hasMasterPin,
                getString(hasMasterPin
                        ? R.string.master_pin_ready_title
                        : R.string.master_pin_required_title),
                hasMasterPin
                        ? getString(R.string.master_pin_ready_detail)
                        : getString(R.string.master_pin_required_detail)
        );
        if (!hasMasterPin) {
            masterPinExpanded = true;
        }
        updateDisclosure(
                masterPinEditor,
                masterPinEditorButton,
                masterPinExpanded,
                R.string.change_master_pin,
                R.string.cancel_pin_change
        );
        masterPinEditorButton.setVisibility(hasMasterPin ? View.VISIBLE : View.GONE);

        int emergencyCodesRemaining = Preferences.emergencyCodesRemaining(this);
        if (emergencyPaused) {
            emergencyStatusText.setText(getString(
                    R.string.emergency_active_status,
                    formatEmergencyPauseEnd(emergencyPauseUntilMs),
                    emergencyCodesRemaining
            ));
        } else if (emergencyCodesRemaining > 0) {
            emergencyStatusText.setText(getResources().getQuantityString(
                    R.plurals.emergency_codes_remaining,
                    emergencyCodesRemaining,
                    emergencyCodesRemaining
            ));
        } else {
            emergencyStatusText.setText(R.string.emergency_no_codes);
        }
        UiStyle.setStatus(
                emergencyStatusText,
                emergencyPaused
                        ? UiStyle.STATUS_WARNING
                        : emergencyCodesRemaining > 0 ? UiStyle.STATUS_READY : UiStyle.STATUS_NEUTRAL
        );
        emergencyCodeInput.setEnabled(enabled && !emergencyPaused && emergencyCodesRemaining > 0);
        useEmergencyCodeButton.setEnabled(enabled && !emergencyPaused && emergencyCodesRemaining > 0);
        generateEmergencyCodesButton.setEnabled(hasMasterPin);
        updateDisclosure(
                emergencyDetails,
                emergencyDetailsButton,
                emergencyExpanded,
                R.string.emergency_open,
                R.string.emergency_close
        );

        String configuredAppsStatus = getResources().getQuantityString(
                R.plurals.guarded_apps_configured,
                selectedCount,
                selectedCount
        );
        setRequirement(
                appLimitsRequiredText,
                hasLimitedApps,
                hasLimitedApps
                        ? configuredAppsStatus
                        : getString(R.string.guarded_apps_required_title),
                usage
                        ? hasLimitedApps
                        ? getString(R.string.guarded_apps_ready_detail)
                        : getString(R.string.guarded_apps_required_detail)
                        : getString(R.string.guarded_apps_usage_required_detail)
        );
        selectAppsButton.setText(usage
                ? enabled ? R.string.change_goose_list : R.string.set_goose_limits
                : R.string.open_usage_before_limits);
        int usageDutyState;
        if (!enabled) {
            usageDutyState = UsageSummaryRenderer.DUTY_OFF;
        } else if (emergencyPaused) {
            usageDutyState = UsageSummaryRenderer.DUTY_PAUSED;
        } else if (!readyToMonitor || !serviceRunning || !monitoringIssue.isEmpty()) {
            usageDutyState = UsageSummaryRenderer.DUTY_ATTENTION;
        } else {
            usageDutyState = UsageSummaryRenderer.DUTY_ACTIVE;
        }
        usageSummaryRenderer.render(
                usageList,
                usageSummaryText,
                selectedPackages,
                usage,
                usageDutyState
        );

        enabledSwitch.setText(emergencyPaused
                ? R.string.duty_paused
                : enabled && serviceRunning && monitoringIssue.isEmpty()
                ? R.string.duty_on
                : enabled ? R.string.duty_recovering : R.string.duty_off);
        enabledSwitch.setEnabled(readyToMonitor || enabled);
        setMonitoringSwitchChecked(enabled);

        int controlsStyle;
        if (emergencyPaused) {
            controlsBlockedText.setText(R.string.controls_emergency_paused);
            controlsStyle = UiStyle.STATUS_WARNING;
        } else if (enabled && !readyToMonitor) {
            controlsBlockedText.setText(getString(
                    R.string.controls_recovery_required,
                    firstMissingRequirement(
                    usage,
                    overlay,
                    hasAccountabilityNumber,
                    hasMasterPin,
                    hasLimitedApps
                    )
            ));
            controlsStyle = UiStyle.STATUS_REQUIRED;
        } else if (enabled && (!serviceRunning || !monitoringIssue.isEmpty())) {
            controlsBlockedText.setText(getString(
                    R.string.controls_recovering,
                    monitoringIssue.isEmpty()
                            ? getString(R.string.monitoring_restarting)
                            : monitoringIssue
            ));
            controlsStyle = UiStyle.STATUS_WARNING;
        } else if (enabled) {
            controlsBlockedText.setText(R.string.controls_on);
            controlsStyle = UiStyle.STATUS_READY;
        } else if (readyToMonitor) {
            controlsBlockedText.setText(R.string.controls_ready);
            controlsStyle = UiStyle.STATUS_READY;
        } else {
            controlsBlockedText.setText(getString(
                    R.string.controls_locked,
                    firstMissingRequirement(
                    usage,
                    overlay,
                    hasAccountabilityNumber,
                    hasMasterPin,
                    hasLimitedApps
                    )
            ));
            controlsStyle = UiStyle.STATUS_REQUIRED;
        }
        UiStyle.setStatus(controlsBlockedText, controlsStyle);

    }

    private void requestUsageRefresh() {
        if (activityDestroyed
                || usageRefreshInFlight
                || !AndroidPermissions.hasUsageAccess(this)) {
            return;
        }

        Context appContext = getApplicationContext();
        Set<String> selectedPackages = Preferences.selectedPackages(this);
        WeakReference<MainActivity> activityReference = new WeakReference<>(this);
        usageRefreshInFlight = true;
        boolean posted = USAGE_REFRESH_EXECUTOR.tryExecute(() -> {
            try {
                Preferences.pruneOldUsageIfNeeded(appContext);
                Map<String, Long> observedUsage = UsageTracker.queryTodayFromSystemStats(
                        appContext,
                        selectedPackages
                );
                Preferences.reconcileUsageTodayMs(appContext, observedUsage);
            } catch (RuntimeException ignored) {
                // The next resume or service reconciliation will retry.
            }
            MainActivity activity = activityReference.get();
            if (activity == null) {
                return;
            }
            activity.runOnUiThread(() -> {
                activity.usageRefreshInFlight = false;
                if (!activity.activityDestroyed && activity.activityResumed) {
                    activity.refresh();
                }
            });
        });
        if (!posted) {
            usageRefreshInFlight = false;
        }
    }

    private static Thread newUsageRefreshThread(Runnable task) {
        Thread thread = new Thread(() -> {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            } catch (RuntimeException ignored) {
                // Usage refresh remains off the main thread if priority adjustment fails.
            }
            task.run();
        }, "AirLockSettingsUsage");
        thread.setDaemon(true);
        return thread;
    }

    private void setRequirement(TextView textView, boolean met, String title, String detail) {
        textView.setText(getString(R.string.requirement_status, title, detail));
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
            return getString(R.string.missing_usage);
        }
        if (!overlay) {
            return getString(R.string.missing_overlay);
        }
        if (!accountability) {
            return getString(R.string.missing_keyholder);
        }
        if (!masterPin) {
            return getString(R.string.missing_master_pin);
        }
        if (!appLimits) {
            return getString(R.string.missing_app_limit);
        }
        return getString(R.string.missing_unknown);
    }

    private boolean requireMonitoringPrerequisites() {
        if (!AndroidPermissions.hasUsageAccess(this)) {
            Toast.makeText(this, R.string.prerequisite_usage_toast, Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            refresh();
            return false;
        }
        if (!AndroidPermissions.hasOverlayAccess(this)) {
            Toast.makeText(this, R.string.prerequisite_overlay_toast, Toast.LENGTH_LONG).show();
            startActivity(overlaySettingsIntent());
            refresh();
            return false;
        }
        if (!hasAccountabilityNumber()) {
            UiStyle.styleInput(phoneInput, true);
            phoneInput.setError(getString(R.string.prerequisite_keyholder_error));
            Toast.makeText(this, R.string.prerequisite_keyholder_toast, Toast.LENGTH_LONG).show();
            refresh();
            return false;
        }
        if (!Preferences.hasMasterPin(this)) {
            Toast.makeText(this, R.string.prerequisite_pin_toast, Toast.LENGTH_LONG).show();
            refresh();
            return false;
        }
        if (!Preferences.hasLimitedApps(this)) {
            Toast.makeText(this, R.string.prerequisite_limit_toast, Toast.LENGTH_LONG).show();
            refresh();
            return false;
        }
        return true;
    }

    private void promptForMasterPin(String title, Runnable onVerified, Runnable onCanceled) {
        masterPinPrompt.show(title, onVerified, onCanceled);
    }

    private void startMonitoring() {
        saveSettings();
        if (!Preferences.setMonitoringRequested(this, true)) {
            Toast.makeText(this, R.string.duty_save_failed, Toast.LENGTH_LONG).show();
            refresh();
            return;
        }
        setMonitoringSwitchChecked(true);
        ensureNotificationPermission();
        if (!startMonitoringService()) {
            Toast.makeText(
                    this,
                    getString(R.string.duty_start_blocked),
                    Toast.LENGTH_LONG
            ).show();
        }
        refresh();
    }

    private void ensureEnabledServiceRunning() {
        if (Preferences.isMonitoringRequested(this)) {
            startMonitoringService();
        }
    }

    private boolean startMonitoringService() {
        return MonitoringService.requestStart(this);
    }

    private void stopMonitoring() {
        if (!Preferences.setMonitoringRequested(this, false)) {
            Toast.makeText(this, R.string.duty_change_save_failed, Toast.LENGTH_LONG).show();
            refresh();
            return;
        }
        stopService(new Intent(this, MonitoringService.class));
        refresh();
    }

    private void setMonitoringSwitchChecked(boolean checked) {
        suppressSwitchChange = true;
        enabledSwitch.setChecked(checked);
        suppressSwitchChange = false;
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && !AndroidPermissions.hasNotificationRuntimePermission(this)
                && !Preferences.prefs(this).getBoolean(
                        Preferences.KEY_NOTIFICATION_PERMISSION_REQUESTED,
                        false
                )) {
            Preferences.prefs(this).edit()
                    .putBoolean(Preferences.KEY_NOTIFICATION_PERMISSION_REQUESTED, true)
                    .apply();
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST_CODE
            );
        }
    }

    private void requestNotificationPermissionOrOpenSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || AndroidPermissions.hasNotificationRuntimePermission(this)) {
            startActivity(notificationSettingsIntent());
            return;
        }

        boolean previouslyRequested = Preferences.prefs(this).getBoolean(
                Preferences.KEY_NOTIFICATION_PERMISSION_REQUESTED,
                false
        );
        if (previouslyRequested && !shouldShowRequestPermissionRationale(
                Manifest.permission.POST_NOTIFICATIONS
        )) {
            startActivity(notificationSettingsIntent());
            return;
        }

        Preferences.prefs(this).edit()
                .putBoolean(Preferences.KEY_NOTIFICATION_PERMISSION_REQUESTED, true)
                .apply();
        requestPermissions(
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                NOTIFICATION_PERMISSION_REQUEST_CODE
        );
    }

    private Intent overlaySettingsIntent() {
        return new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())
        );
    }

    private Intent appDetailsSettingsIntent() {
        return new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())
        );
    }

    private Intent notificationSettingsIntent() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        boolean canOpenChannel = AndroidPermissions.hasNotificationRuntimePermission(this)
                && AndroidPermissions.areAppNotificationsEnabled(this);
        if (canOpenChannel
                && manager != null
                && manager.getNotificationChannel(MonitoringService.CHANNEL_ID) != null) {
            return new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                    .putExtra(Settings.EXTRA_CHANNEL_ID, MonitoringService.CHANNEL_ID);
        }
        return new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
    }

    private void openAuthorizedAppSelection() {
        openAppSelection(EditAuthorization.issue());
    }

    private void openAppSelection(String authorizationToken) {
        Intent intent = new Intent(this, AppSelectionActivity.class);
        if (authorizationToken != null) {
            intent.putExtra(AppSelectionActivity.EXTRA_EDIT_AUTHORIZATION_TOKEN, authorizationToken);
        }
        startActivity(intent);
    }

    private void showKeyboard(EditText input) {
        KeyboardHelper.show(this, input);
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
        KeyboardHelper.hide(this, view);
    }
}
