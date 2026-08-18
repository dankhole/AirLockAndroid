package com.dankhole.airlockandroid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class AppSelectionActivity extends Activity {
    static final String EXTRA_EDIT_AUTHORIZATION_TOKEN =
            "com.dankhole.airlock.EDIT_AUTHORIZATION_TOKEN";

    private static final String STATE_AUTHORIZATION_SESSION = "authorization_session";
    private static final String STATE_BATCH_PACKAGES = "batch_packages";
    private static final String STATE_LIMIT_MINUTES = "limit_minutes";
    private static final String STATE_SEARCH_QUERY = "search_query";
    private static final String STATE_SHOWING_LIMIT = "showing_limit";
    private static final AtomicInteger LOADER_THREAD_SEQUENCE = new AtomicInteger();
    private static final BoundedTaskExecutor APP_CATALOG_EXECUTOR = new BoundedTaskExecutor(
            2,
            30_000L,
            AppSelectionActivity::newAppCatalogThread
    );

    private final Set<String> batchPackages = new HashSet<>();

    private List<AppCatalogLoader.Entry> apps;
    private AppPickerAdapter appListAdapter;
    private TextView selectionSummary;
    private Button selectionNextButton;
    private Button selectionRemoveButton;
    private EditText limitInput;
    private String authorizationSessionId = "";
    private String limitMinutesText = "15";
    private String searchQuery = "";
    private boolean showingLimitStep;
    private boolean appLoadInFlight;
    private boolean reloadAfterCurrentLoad;
    private boolean refreshCatalogOnResume;
    private boolean activityDestroyed;
    private int appLoadGeneration;
    private OnBackInvokedCallback backInvokedCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiStyle.applyWindow(this);
        registerBackHandler();
        restoreWizardState(savedInstanceState);
        restoreOrBeginAuthorization(savedInstanceState);

        if (isLockedByActiveMonitoring()) {
            showMonitoringLockedScreen();
        } else if (!AndroidPermissions.hasUsageAccess(this)) {
            showAccessRequiredScreen();
        } else {
            requestAppCatalog(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!authorizationSessionId.isEmpty()
                && !EditAuthorization.resumeSession(authorizationSessionId)) {
            authorizationSessionId = "";
        }
        if (isLockedByActiveMonitoring()) {
            showMonitoringLockedScreen();
            return;
        }
        if (!AndroidPermissions.hasUsageAccess(this)) {
            showAccessRequiredScreen();
            return;
        }
        if (refreshCatalogOnResume) {
            refreshCatalogOnResume = false;
            requestAppCatalog(apps == null);
        } else if (apps == null && !appLoadInFlight) {
            requestAppCatalog(true);
        }
    }

    @Override
    protected void onStop() {
        if (!isChangingConfigurations()) {
            EditAuthorization.markSessionBackgrounded(authorizationSessionId);
            refreshCatalogOnResume = true;
        }
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (limitInput != null) {
            limitMinutesText = limitInput.getText().toString();
        }
        outState.putString(STATE_AUTHORIZATION_SESSION, authorizationSessionId);
        outState.putStringArrayList(STATE_BATCH_PACKAGES, new ArrayList<>(batchPackages));
        outState.putString(STATE_LIMIT_MINUTES, limitMinutesText);
        outState.putString(STATE_SEARCH_QUERY, searchQuery);
        outState.putBoolean(STATE_SHOWING_LIMIT, showingLimitStep);
    }

    @Override
    public void onBackPressed() {
        handleBackNavigation();
    }

    @Override
    protected void onDestroy() {
        activityDestroyed = true;
        appLoadGeneration++;
        if (!isChangingConfigurations()) {
            EditAuthorization.revokeSession(authorizationSessionId);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && backInvokedCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backInvokedCallback);
        }
        super.onDestroy();
    }

    private void restoreWizardState(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            return;
        }
        ArrayList<String> restoredPackages = savedInstanceState.getStringArrayList(
                STATE_BATCH_PACKAGES
        );
        if (restoredPackages != null) {
            batchPackages.addAll(restoredPackages);
        }
        limitMinutesText = savedInstanceState.getString(STATE_LIMIT_MINUTES, "15");
        searchQuery = savedInstanceState.getString(STATE_SEARCH_QUERY, "");
        showingLimitStep = savedInstanceState.getBoolean(STATE_SHOWING_LIMIT, false);
    }

    private void restoreOrBeginAuthorization(Bundle savedInstanceState) {
        String restoredSession = savedInstanceState == null
                ? ""
                : savedInstanceState.getString(STATE_AUTHORIZATION_SESSION, "");
        if (EditAuthorization.resumeSession(restoredSession)) {
            authorizationSessionId = restoredSession;
        } else {
            authorizationSessionId = EditAuthorization.consumeAndBeginSession(
                    getIntent().getStringExtra(EXTRA_EDIT_AUTHORIZATION_TOKEN)
            );
        }
        getIntent().removeExtra(EXTRA_EDIT_AUTHORIZATION_TOKEN);
    }

    private void registerBackHandler() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        backInvokedCallback = this::handleBackNavigation;
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                backInvokedCallback
        );
    }

    private void handleBackNavigation() {
        if (showingLimitStep && apps != null) {
            showingLimitStep = false;
            setContentView(selectionContent());
            return;
        }
        finishAfterTransition();
    }

    private boolean isLockedByActiveMonitoring() {
        return Preferences.isMonitoringRequested(this)
                && !EditAuthorization.restoreSession(authorizationSessionId);
    }

    private void requestAppCatalog(boolean showLoading) {
        if (appLoadInFlight) {
            reloadAfterCurrentLoad = true;
            return;
        }
        if (showLoading || apps == null) {
            showCatalogLoadingScreen();
        }

        Context appContext = getApplicationContext();
        WeakReference<AppSelectionActivity> activityReference = new WeakReference<>(this);
        int generation = ++appLoadGeneration;
        appLoadInFlight = true;
        boolean posted = APP_CATALOG_EXECUTOR.tryExecute(() -> {
            List<AppCatalogLoader.Entry> loadedApps = null;
            RuntimeException failure = null;
            try {
                loadedApps = AppCatalogLoader.load(appContext);
            } catch (RuntimeException exception) {
                failure = exception;
            }

            AppSelectionActivity activity = activityReference.get();
            if (activity == null) {
                return;
            }
            List<AppCatalogLoader.Entry> result = loadedApps;
            RuntimeException error = failure;
            activity.runOnUiThread(() -> activity.completeAppCatalogLoad(
                    generation,
                    result,
                    error
            ));
        });
        if (!posted) {
            appLoadInFlight = false;
            showCatalogErrorScreen();
        }
    }

    private void completeAppCatalogLoad(
            int generation,
            List<AppCatalogLoader.Entry> loadedApps,
            RuntimeException failure
    ) {
        if (activityDestroyed || generation != appLoadGeneration) {
            return;
        }
        appLoadInFlight = false;
        if (failure != null || loadedApps == null) {
            showCatalogErrorScreen();
            return;
        }

        apps = loadedApps;
        Set<String> visiblePackages = new HashSet<>();
        for (AppCatalogLoader.Entry app : apps) {
            visiblePackages.add(app.packageName);
        }
        boolean selectionChanged = batchPackages.retainAll(visiblePackages);
        if (selectionChanged && batchPackages.isEmpty()) {
            showingLimitStep = false;
        }

        if (apps.isEmpty()) {
            showCatalogEmptyScreen();
        } else if (showingLimitStep && !batchPackages.isEmpty()) {
            showLimitStep();
        } else {
            showingLimitStep = false;
            setContentView(selectionContent());
        }

        if (reloadAfterCurrentLoad) {
            reloadAfterCurrentLoad = false;
            requestAppCatalog(false);
        }
    }

    private void showCatalogLoadingScreen() {
        Screen screen = newScreen(getString(R.string.app_limits_title));
        screen.root.addView(wizardHeader(
                getString(R.string.wizard_step_one),
                getString(R.string.app_catalog_loading_title)
        ), UiStyle.fullWidth(this, 14));

        LinearLayout card = UiStyle.card(this);
        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setContentDescription(getString(R.string.app_catalog_loading_description));
        card.addView(progress, centeredProgressParams());
        TextView status = UiStyle.statusText(this);
        status.setId(R.id.app_catalog_loading);
        status.setText(R.string.app_catalog_loading);
        UiStyle.setStatus(status, UiStyle.STATUS_NEUTRAL);
        card.addView(status, UiStyle.fullWidth(this, 0));
        screen.root.addView(card, UiStyle.fullWidth(this));
        setContentView(screen.scrollView);
    }

    private void showCatalogErrorScreen() {
        Screen screen = newScreen(getString(R.string.app_limits_title));
        LinearLayout card = UiStyle.card(this);
        TextView error = UiStyle.statusText(this);
        error.setId(R.id.app_catalog_error);
        error.setText(R.string.app_catalog_load_error);
        UiStyle.setStatus(error, UiStyle.STATUS_REQUIRED);
        card.addView(error, UiStyle.fullWidth(this, 12));

        Button retry = UiStyle.primaryButton(this, getString(R.string.app_catalog_retry));
        retry.setId(R.id.app_catalog_retry);
        retry.setOnClickListener(v -> requestAppCatalog(true));
        card.addView(retry, UiStyle.buttonParams(this));
        Button back = UiStyle.secondaryButton(this, getString(R.string.back_to_goose_settings));
        back.setId(R.id.app_catalog_back);
        back.setOnClickListener(v -> finish());
        card.addView(back, UiStyle.buttonParams(this));
        screen.root.addView(card, UiStyle.fullWidth(this));
        setContentView(screen.scrollView);
    }

    private void showCatalogEmptyScreen() {
        showingLimitStep = false;
        Screen screen = newScreen(getString(R.string.app_limits_title));
        LinearLayout card = UiStyle.card(this);
        TextView empty = UiStyle.statusText(this);
        empty.setText(R.string.app_catalog_empty);
        UiStyle.setStatus(empty, UiStyle.STATUS_WARNING);
        card.addView(empty, UiStyle.fullWidth(this, 12));
        Button retry = UiStyle.secondaryButton(this, getString(R.string.app_catalog_check_again));
        retry.setId(R.id.app_catalog_retry);
        retry.setOnClickListener(v -> requestAppCatalog(true));
        card.addView(retry, UiStyle.buttonParams(this));
        screen.root.addView(card, UiStyle.fullWidth(this));
        setContentView(screen.scrollView);
    }

    private void showAccessRequiredScreen() {
        showingLimitStep = false;
        apps = null;
        Screen screen = newScreen(getString(R.string.app_limits_title));
        screen.root.addView(UiStyle.bodyText(
                this,
                getString(R.string.app_picker_usage_access_intro)
        ), UiStyle.fullWidth(this, 18));

        LinearLayout card = UiStyle.card(this);
        TextView required = UiStyle.statusText(this);
        required.setText(R.string.usage_access_required_detail);
        UiStyle.setStatus(required, UiStyle.STATUS_REQUIRED);
        card.addView(required, UiStyle.fullWidth(this, 12));

        Button settingsButton = UiStyle.primaryButton(
                this,
                getString(R.string.open_usage_access_settings)
        );
        settingsButton.setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        ));
        card.addView(settingsButton, UiStyle.buttonParams(this));
        Button backButton = UiStyle.secondaryButton(
                this,
                getString(R.string.back_to_goose_settings)
        );
        backButton.setOnClickListener(v -> finish());
        card.addView(backButton, UiStyle.buttonParams(this));
        screen.root.addView(card, UiStyle.fullWidth(this));
        setContentView(screen.scrollView);
    }

    private void showMonitoringLockedScreen() {
        showingLimitStep = false;
        apps = null;
        Screen screen = newScreen(getString(R.string.app_limits_title));
        screen.root.addView(UiStyle.bodyText(
                this,
                getString(R.string.app_picker_monitoring_locked_intro)
        ), UiStyle.fullWidth(this, 18));

        LinearLayout card = UiStyle.card(this);
        TextView required = UiStyle.statusText(this);
        required.setText(R.string.app_editor_monitoring_locked);
        UiStyle.setStatus(required, UiStyle.STATUS_REQUIRED);
        card.addView(required, UiStyle.fullWidth(this, 12));
        Button backButton = UiStyle.primaryButton(this, getString(R.string.back_to_airlock));
        backButton.setOnClickListener(v -> finish());
        card.addView(backButton, UiStyle.buttonParams(this));
        screen.root.addView(card, UiStyle.fullWidth(this));
        setContentView(screen.scrollView);
    }

    private View selectionContent() {
        ListView list = new ListView(this);
        list.setId(R.id.app_picker_list);
        list.setBackgroundColor(UiStyle.COLOR_BACKGROUND);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setCacheColorHint(UiStyle.COLOR_BACKGROUND);
        list.setClipToPadding(true);

        LinearLayout header = UiStyle.screenRoot(this);
        header.setPadding(
                UiStyle.dp(this, 20),
                UiStyle.dp(this, 20),
                UiStyle.dp(this, 20),
                0
        );
        header.addView(UiStyle.screenTitle(this, getString(R.string.app_limits_title)),
                UiStyle.fullWidth(this, 4));
        header.addView(new GooseMascotView(this), UiStyle.gooseBannerParams(this));
        header.addView(wizardHeader(
                getString(R.string.wizard_step_one),
                getString(R.string.app_picker_title)
        ), UiStyle.fullWidth(this, 12));
        header.addView(UiStyle.bodyText(this, getString(R.string.app_picker_intro)),
                UiStyle.fullWidth(this, 10));

        selectionSummary = UiStyle.statusText(this);
        selectionSummary.setId(R.id.app_picker_selection_status);
        selectionSummary.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        header.addView(selectionSummary, UiStyle.fullWidth(this, 12));

        TextView searchLabel = UiStyle.fieldLabel(this, getString(R.string.app_picker_search_label));
        EditText searchInput = UiStyle.inputField(this, getString(R.string.app_picker_search_hint));
        searchInput.setId(R.id.app_picker_search);
        searchLabel.setLabelFor(searchInput.getId());
        searchInput.setSingleLine(true);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        UiStyle.styleInput(searchInput, false);
        searchInput.setText(searchQuery);
        searchInput.setSelection(searchInput.length());
        searchInput.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                searchQuery = editable.toString();
                if (appListAdapter != null) {
                    appListAdapter.setQuery(searchQuery);
                }
            }
        });
        header.addView(searchLabel, UiStyle.fullWidth(this, 6));
        header.addView(searchInput, UiStyle.fullWidth(this, 12));
        list.addHeaderView(header, null, false);

        LinearLayout footer = UiStyle.screenRoot(this);
        footer.setPadding(
                UiStyle.dp(this, 20),
                UiStyle.dp(this, 4),
                UiStyle.dp(this, 20),
                UiStyle.dp(this, 20)
        );
        selectionNextButton = UiStyle.primaryButton(
                this,
                getString(R.string.app_picker_continue)
        );
        selectionNextButton.setId(R.id.app_picker_continue);
        selectionNextButton.setOnClickListener(v -> {
            if (batchPackages.isEmpty()) {
                Toast.makeText(this, R.string.app_picker_required_toast, Toast.LENGTH_SHORT).show();
                refreshSelectionSummary();
                return;
            }
            showLimitStep();
        });
        footer.addView(selectionNextButton, UiStyle.buttonParams(this));

        selectionRemoveButton = UiStyle.secondaryButton(
                this,
                getString(R.string.app_picker_remove)
        );
        selectionRemoveButton.setId(R.id.app_picker_remove);
        selectionRemoveButton.setOnClickListener(v -> {
            if (batchPackages.isEmpty()) {
                Toast.makeText(this, R.string.app_picker_required_short, Toast.LENGTH_SHORT).show();
                refreshSelectionSummary();
                return;
            }
            Preferences.removeLimitsForPackages(this, batchPackages);
            batchPackages.clear();
            Toast.makeText(this, R.string.app_picker_removed_toast, Toast.LENGTH_SHORT).show();
            requestAppCatalog(true);
        });
        footer.addView(selectionRemoveButton, UiStyle.buttonParams(this));
        list.addFooterView(footer, null, false);

        appListAdapter = new AppPickerAdapter(
                this,
                apps,
                batchPackages,
                this::refreshSelectionSummary
        );
        list.setAdapter(appListAdapter);
        appListAdapter.setQuery(searchQuery);
        refreshSelectionSummary();
        return UiStyle.constrainedScreen(this, list);
    }

    private void showLimitStep() {
        showingLimitStep = true;
        Screen screen = newScreen(getString(R.string.app_timer_title));
        screen.root.addView(wizardHeader(
                getString(R.string.wizard_step_two),
                getString(R.string.app_timer_step_title)
        ), UiStyle.fullWidth(this, 12));
        screen.root.addView(UiStyle.bodyText(this, selectedAppsSummary()), UiStyle.fullWidth(this, 16));

        LinearLayout panel = UiStyle.card(this);
        TextView limitLabel = UiStyle.fieldLabel(this, getString(R.string.app_limit_label));
        limitInput = UiStyle.inputField(this, getString(R.string.minutes_hint));
        limitInput.setId(R.id.app_limit_minutes);
        limitLabel.setLabelFor(limitInput.getId());
        KeyboardHelper.prepareNumericInput(limitInput);
        limitInput.setSingleLine(true);
        limitInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        UiStyle.styleInput(limitInput, false);
        limitInput.setText(limitMinutesText);
        limitInput.setSelectAllOnFocus(true);
        limitInput.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                limitMinutesText = editable.toString();
                UiStyle.styleInput(limitInput, false);
            }
        });
        KeyboardHelper.installKeyboardInteraction(
                this,
                limitInput,
                () -> scrollInputIntoView(screen.scrollView, limitInput)
        );
        limitInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                showKeyboard(limitInput);
                scrollInputIntoView(screen.scrollView, limitInput);
            }
        });
        panel.addView(limitLabel, UiStyle.fullWidth(this, 6));
        panel.addView(limitInput, UiStyle.fullWidth(this, 8));

        TextView validationText = UiStyle.statusText(this);
        validationText.setId(R.id.app_limit_validation);
        validationText.setText(R.string.limit_validation);
        validationText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        UiStyle.setStatus(validationText, UiStyle.STATUS_NEUTRAL);
        panel.addView(validationText);
        screen.root.addView(panel, UiStyle.fullWidth(this, 14));

        Button saveButton = UiStyle.primaryButton(this, getString(R.string.app_limit_save));
        saveButton.setId(R.id.app_limit_save);
        saveButton.setOnClickListener(v -> {
            int minutes = parsePositiveInt(limitInput);
            if (minutes <= 0) {
                UiStyle.styleInput(limitInput, true);
                validationText.setText(R.string.limit_validation_required);
                UiStyle.setStatus(validationText, UiStyle.STATUS_REQUIRED);
                limitInput.setError(getString(R.string.app_limit_input_error));
                limitInput.requestFocus();
                return;
            }
            Preferences.saveLimitForPackages(this, batchPackages, minutes);
            Toast.makeText(this, R.string.app_limit_saved_toast, Toast.LENGTH_SHORT).show();
            finish();
        });
        screen.root.addView(saveButton, UiStyle.buttonParams(this));
        Button backButton = UiStyle.secondaryButton(this, getString(R.string.app_limit_back));
        backButton.setId(R.id.app_limit_back);
        backButton.setOnClickListener(v -> {
            showingLimitStep = false;
            setContentView(selectionContent());
        });
        screen.root.addView(backButton, UiStyle.buttonParams(this));
        setContentView(screen.scrollView);
    }

    private Screen newScreen(String title) {
        ScrollView scrollView = UiStyle.screenScroll(this);
        LinearLayout root = UiStyle.screenRoot(this);
        UiStyle.applyScreenInsetsPadding(scrollView, root, 20, 20, 20, 20);
        UiStyle.attachScreenContent(scrollView, root);
        root.addView(UiStyle.screenTitle(this, title), UiStyle.fullWidth(this, 4));
        root.addView(new GooseMascotView(this), UiStyle.gooseBannerParams(this));
        return new Screen(scrollView, root);
    }

    private void showKeyboard(EditText input) {
        KeyboardHelper.show(this, input);
    }

    private void scrollInputIntoView(ScrollView scrollView, View input) {
        input.postDelayed(() -> {
            if (!input.isAttachedToWindow()) {
                return;
            }
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
        int count = batchPackages.size();
        return getResources().getQuantityString(
                R.plurals.app_limit_selected_summary,
                count,
                count
        );
    }

    private void refreshSelectionSummary() {
        if (selectionSummary == null) {
            return;
        }
        boolean hasSelection = !batchPackages.isEmpty();
        selectionSummary.setText(hasSelection
                ? getResources().getQuantityString(
                        R.plurals.app_picker_ready,
                        batchPackages.size(),
                        batchPackages.size()
                )
                : getString(R.string.app_picker_required));
        UiStyle.setStatus(
                selectionSummary,
                hasSelection ? UiStyle.STATUS_READY : UiStyle.STATUS_REQUIRED
        );
        if (selectionNextButton != null) {
            selectionNextButton.setEnabled(hasSelection);
        }
        if (selectionRemoveButton != null) {
            selectionRemoveButton.setEnabled(hasSelection);
        }
    }

    private LinearLayout.LayoutParams centeredProgressParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.setMargins(0, 0, 0, UiStyle.dp(this, 12));
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

    private static Thread newAppCatalogThread(Runnable task) {
        Thread thread = new Thread(() -> {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            } catch (RuntimeException ignored) {
                // App discovery still remains off the main thread.
            }
            task.run();
        }, "AirLockAppCatalog-" + LOADER_THREAD_SEQUENCE.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence text, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence text, int start, int before, int count) {
        }
    }

    private static final class Screen {
        final ScrollView scrollView;
        final LinearLayout root;

        Screen(ScrollView scrollView, LinearLayout root) {
            this.scrollView = scrollView;
            this.root = root;
        }
    }
}
