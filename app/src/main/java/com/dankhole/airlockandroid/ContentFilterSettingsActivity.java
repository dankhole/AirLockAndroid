package com.dankhole.airlockandroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Optional, local-only filters. The Android accessibility setting is never edit authorization. */
public final class ContentFilterSettingsActivity extends Activity {
    static final String EXTRA_AUTHORIZATION_TOKEN =
            "com.dankhole.airlock.CONTENT_FILTER_AUTHORIZATION_TOKEN";
    private static final String STATE_SESSION = "content_filter_session";
    private static final long STATUS_REFRESH_MS = 2_000L;
    // Hash identity stays in this process only; saved state contains just an opaque session ID.
    private static final SessionPinIdentities SESSION_PIN_IDENTITIES = new SessionPinIdentities();

    private final Object authorizationLock = new Object();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final long DISCOVERY_TIMEOUT_MS = 5_000L;
    // A Binder call cannot be forcibly canceled. Recreation must not create another worker.
    private static final BoundedTaskExecutor EXECUTOR = new BoundedTaskExecutor(1, 10_000L, task -> {
        Thread thread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            task.run();
        }, "airlock-content-settings");
        thread.setDaemon(true);
        return thread;
    });
    private final Runnable accessListener = () -> handler.post(this::requestSnapshot);
    private final SharedPreferences.OnSharedPreferenceChangeListener preferencesListener =
            (preferences, key) -> handler.post(() -> {
                if (this.resumed && !isAuthorized()) {
                    dismissPrompts();
                    showLockedScreen();
                } else {
                    requestSnapshot();
                }
            });
    private final Runnable statusRefresh = new Runnable() {
        @Override
        public void run() {
            requestSnapshot();
            if (resumed) {
                handler.postDelayed(this, STATUS_REFRESH_MS);
            }
        }
    };

    private String sessionId = "";
    private volatile boolean resumed;
    private volatile boolean destroyed;
    private boolean inFlight;
    private boolean discoveryUnavailable;
    private int workSequence;
    private Runnable discoveryTimeout;
    private boolean refreshCriticalOnLoad = true;
    private boolean rendering;
    private int generation;
    private AlertDialog pinDialog;
    private AlertDialog disclosureDialog;
    private TextView statusView;
    private TextView warningView;
    private TextView androidAccessView;
    private LinearLayout choices;
    private Button consentButton;
    private Button settingsButton;
    private Button offButton;
    private Snapshot snapshot;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        UiStyle.applyWindow(this);
        String restored = state == null ? "" : state.getString(STATE_SESSION, "");
        if (SESSION_PIN_IDENTITIES.contains(restored)
                && EditAuthorization.resumeSession(restored)) {
            sessionId = restored;
        } else {
            SESSION_PIN_IDENTITIES.remove(restored);
            if (state == null) {
                sessionId = EditAuthorization.consumeAndBeginSession(
                        getIntent().getStringExtra(EXTRA_AUTHORIZATION_TOKEN));
                rememberPinIdentity();
            }
        }
        // Never retry an incoming token after recreation or a canceled prompt.
        getIntent().removeExtra(EXTRA_AUTHORIZATION_TOKEN);
        showLockedScreen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        synchronized (authorizationLock) {
            resumed = true;
            if (!EditAuthorization.resumeSession(sessionId)) {
                SESSION_PIN_IDENTITIES.remove(sessionId);
                sessionId = "";
            }
        }
        Preferences.prefs(this).registerOnSharedPreferenceChangeListener(preferencesListener);
        ContentFilterAccess.addListener(accessListener);
        refreshCriticalOnLoad = true;
        if (isAuthorized()) {
            showSettingsScreen();
        } else {
            showLockedScreen();
        }
        handler.post(statusRefresh);
    }

    @Override
    protected void onPause() {
        synchronized (authorizationLock) {
            resumed = false;
            EditAuthorization.markSessionBackgrounded(sessionId);
        }
        generation++;
        workSequence++;
        inFlight = false;
        handler.removeCallbacksAndMessages(null);
        Preferences.prefs(this).unregisterOnSharedPreferenceChangeListener(preferencesListener);
        ContentFilterAccess.removeListener(accessListener);
        dismissPrompts();
        // Do not leave editable controls visible in the background or Recents.
        showLockedScreen();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString(STATE_SESSION, sessionId);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        generation++;
        handler.removeCallbacksAndMessages(null);
        dismissPrompts();
        if (!isChangingConfigurations()) {
            EditAuthorization.revokeSession(sessionId);
            SESSION_PIN_IDENTITIES.remove(sessionId);
        }
        super.onDestroy();
    }

    private boolean isAuthorized() {
        synchronized (authorizationLock) {
            if (!resumed || destroyed) {
                return false;
            }
            if (!SESSION_PIN_IDENTITIES.matches(sessionId, pinIdentity())
                    || !EditAuthorization.resumeSession(sessionId)) {
                EditAuthorization.revokeSession(sessionId);
                SESSION_PIN_IDENTITIES.remove(sessionId);
                sessionId = "";
                return false;
            }
            return true;
        }
    }

    private String pinIdentity() {
        try {
            SharedPreferences preferences = Preferences.prefs(this);
            String salt = preferences.getString(Preferences.KEY_MASTER_PIN_SALT, "");
            String hash = preferences.getString(Preferences.KEY_MASTER_PIN_HASH, "");
            return salt.isEmpty() || hash.isEmpty() ? "" : salt + ":" + hash;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private void rememberPinIdentity() {
        if (!sessionId.isEmpty()) {
            SESSION_PIN_IDENTITIES.remember(sessionId, pinIdentity());
        }
    }

    private LinearLayout screen() {
        ScrollView scroll = UiStyle.screenScroll(this);
        scroll.setId(R.id.content_filter_scroll);
        LinearLayout root = UiStyle.screenRoot(this);
        UiStyle.applyScreenInsetsPadding(scroll, root, 20, 20, 20, 20);
        UiStyle.attachScreenContent(scroll, root);
        root.addView(UiStyle.screenTitle(this, getString(R.string.content_filter_title)),
                UiStyle.fullWidth(this, 14));
        setContentView(scroll);
        return root;
    }

    private void addBack(LinearLayout root) {
        Button back = UiStyle.quietButton(this, getString(R.string.back_to_airlock));
        back.setId(R.id.content_filter_back);
        back.setOnClickListener(view -> finish());
        root.addView(back, UiStyle.buttonParams(this));
    }

    private void showLockedScreen() {
        statusView = null;
        choices = null;
        LinearLayout root = screen();
        TextView locked = UiStyle.statusText(this);
        locked.setId(R.id.content_filter_locked);
        locked.setText(R.string.content_filter_locked);
        UiStyle.setStatus(locked, UiStyle.STATUS_REQUIRED);
        root.addView(locked, UiStyle.fullWidth(this, 14));
        Button unlock = UiStyle.primaryButton(this, getString(R.string.content_filter_unlock));
        unlock.setId(R.id.content_filter_unlock);
        unlock.setOnClickListener(view -> showPinPrompt());
        root.addView(unlock, UiStyle.buttonParams(this));
        addBack(root);
    }

    private void showPinPrompt() {
        if (!resumed || destroyed || pinDialog != null) {
            return;
        }
        pinDialog = showAuthorizationPrompt(this, () -> {
            if (!resumed || destroyed) {
                return;
            }
            synchronized (authorizationLock) {
                EditAuthorization.revokeSession(sessionId);
                SESSION_PIN_IDENTITIES.remove(sessionId);
                sessionId = EditAuthorization.consumeAndBeginSession(EditAuthorization.issue());
                rememberPinIdentity();
            }
            showSettingsScreen();
            requestSnapshot();
        }, () -> pinDialog = null);
    }

    // Shared with the dashboard so this optional entry prompt can be dismissed on pause.
    static AlertDialog showAuthorizationPrompt(Activity activity, Runnable onVerified, Runnable onDismiss) {
        if (!Preferences.hasMasterPin(activity)) {
            android.widget.Toast.makeText(activity, R.string.master_pin_missing_toast,
                    android.widget.Toast.LENGTH_LONG).show();
            return null;
        }
        EditText input = UiStyle.inputField(activity, activity.getString(R.string.master_pin_hint));
        input.setId(R.id.master_pin_dialog_input);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        KeyboardHelper.prepareSecureNumericInput(input);
        input.setTransformationMethod(PasswordTransformationMethod.getInstance());
        KeyboardHelper.installKeyboardInteraction(activity, input, null);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.content_filter_pin_title)
                .setMessage(R.string.master_pin_prompt_message)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.continue_action, null)
                .create();
        dialog.setOnDismissListener(ignored -> onDismiss.run());
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                if (!dialog.isShowing() || activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                boolean verified;
                try {
                    verified = Preferences.verifyMasterPin(activity, input.getText().toString().trim());
                } catch (RuntimeException verificationFailure) {
                    verified = false;
                }
                if (!verified) {
                    input.setError(activity.getString(R.string.master_pin_invalid));
                    return;
                }
                dialog.dismiss();
                onVerified.run();
            });
            input.setOnEditorActionListener((view, action, event) -> {
                if (action == EditorInfo.IME_ACTION_DONE) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                    return true;
                }
                return false;
            });
            input.requestFocus();
            KeyboardHelper.show(activity, input);
        });
        dialog.show();
        return dialog;
    }

    private void dismissPrompts() {
        if (pinDialog != null) {
            pinDialog.dismiss();
            pinDialog = null;
        }
        if (disclosureDialog != null) {
            disclosureDialog.dismiss();
            disclosureDialog = null;
        }
    }

    private void showSettingsScreen() {
        if (!isAuthorized()) {
            showLockedScreen();
            return;
        }
        LinearLayout root = screen();
        root.addView(UiStyle.bodyText(this, getString(R.string.content_filter_intro)),
                UiStyle.fullWidth(this, 14));
        statusView = UiStyle.statusText(this);
        statusView.setId(R.id.content_filter_status);
        statusView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusView.setText(R.string.content_filter_checking);
        UiStyle.setStatus(statusView, UiStyle.STATUS_WARNING);
        root.addView(statusView, UiStyle.fullWidth(this, 10));
        androidAccessView = UiStyle.helperText(this, getString(R.string.content_filter_android_access_checking));
        androidAccessView.setId(R.id.content_filter_android_access);
        root.addView(androidAccessView, UiStyle.fullWidth(this, 10));
        warningView = UiStyle.helperText(this, "");
        warningView.setId(R.id.content_filter_warning);
        root.addView(warningView, UiStyle.fullWidth(this, 14));
        choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.VERTICAL);
        root.addView(choices, UiStyle.fullWidth(this, 10));
        consentButton = UiStyle.secondaryButton(this, getString(R.string.content_filter_review_disclosure));
        consentButton.setId(R.id.content_filter_consent);
        consentButton.setOnClickListener(view -> {
            if (isAuthorized()) {
                if (ContentFilterSettings.hasConsent(this)) {
                    submitMutation(() -> ContentFilterSettings.setConsent(getApplicationContext(), false), false);
                } else {
                    showDisclosure();
                }
            } else if (!isAuthorized()) {
                showLockedScreen();
            }
        });
        consentButton.setText(ContentFilterSettings.hasConsent(this)
                ? R.string.content_filter_withdraw_consent : R.string.content_filter_review_disclosure);
        root.addView(consentButton, UiStyle.buttonParams(this));
        settingsButton = UiStyle.secondaryButton(this, getString(R.string.content_filter_open_settings));
        settingsButton.setId(R.id.content_filter_open_settings);
        settingsButton.setOnClickListener(view -> {
            if (!isAuthorized()) {
                showLockedScreen();
            } else if (snapshot == null || !snapshot.consent) {
                showDisclosure();
            } else {
                openAndroidSettings();
            }
        });
        root.addView(settingsButton, UiStyle.buttonParams(this));
        offButton = UiStyle.dangerButton(this, getString(R.string.content_filter_turn_off));
        offButton.setId(R.id.content_filter_turn_off);
        offButton.setOnClickListener(view -> submitMutation(() -> {
            for (ContentFilter filter : ContentFilter.values()) {
                if (!isAuthorized()) {
                    return;
                }
                ContentFilterSettings.setEnabled(getApplicationContext(), filter, false);
            }
        }, false));
        root.addView(offButton, UiStyle.buttonParams(this));
        root.addView(UiStyle.helperText(this, getString(R.string.content_filter_limitations)),
                UiStyle.fullWidth(this, 14));
        addBack(root);
        renderLocalSnapshot();
        requestSnapshot();
    }

    private void showDisclosure() {
        if (!isAuthorized() || disclosureDialog != null) {
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.content_filter_disclosure_title)
                .setMessage(R.string.content_filter_disclosure_body)
                .setNegativeButton(R.string.content_filter_decline, (ignored, which) -> {
                    if (isAuthorized()) {
                        submitMutation(() -> ContentFilterSettings.setConsent(getApplicationContext(), false), false);
                    }
                })
                .setPositiveButton(R.string.content_filter_accept, (ignored, which) -> {
                    if (isAuthorized()) {
                        submitMutation(() -> ContentFilterSettings.setConsent(getApplicationContext(), true), true);
                    }
                })
                .create();
        disclosureDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (disclosureDialog == dialog) {
                disclosureDialog = null;
            }
        });
        dialog.show();
    }

    private void openAndroidSettings() {
        if (!isAuthorized()) {
            showLockedScreen();
            return;
        }
        // Consent is checked again rather than trusting a previously rendered snapshot.
        if (!ContentFilterSettings.hasConsent(this)) {
            showDisclosure();
            return;
        }
        if (!ContentFilterAccess.openSettings(this)) {
            android.widget.Toast.makeText(this, R.string.content_filter_settings_unavailable,
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void setControlsEnabled(boolean enabled) {
        if (choices == null) {
            return;
        }
        for (int index = 0; index < choices.getChildCount(); index++) {
            View child = choices.getChildAt(index);
            if (child instanceof Switch) {
                ContentFilter filter = (ContentFilter) child.getTag();
                child.setEnabled(enabled && snapshot != null && !snapshot.critical.contains(filter));
            }
        }
        // Local reductions and Android recovery must never wait for Binder discovery.
        boolean authorized = isAuthorized();
        consentButton.setEnabled(authorized);
        settingsButton.setEnabled(authorized);
        offButton.setEnabled(authorized);
    }

    private void submitMutation(Runnable mutation, boolean openSettingsAfter) {
        synchronized (authorizationLock) {
            if (!isAuthorized()) {
                showLockedScreen();
                return;
            }
            try {
                // Settings mutations use local preferences and no-Binder safety snapshots only.
                // In particular, consent withdrawal and disabling never need discovery.
                mutation.run();
                generation++;
            } catch (RuntimeException ignored) {
                showDiscoveryUnavailable();
                return;
            }
        }
        renderLocalSnapshot();
        if (openSettingsAfter) {
            openAndroidSettings();
        }
        requestSnapshot();
    }

    private void renderLocalSnapshot() {
        if (!isAuthorized() || statusView == null) {
            return;
        }
        try {
            renderSnapshot(Snapshot.load(getApplicationContext()));
        } catch (RuntimeException ignored) {
            showDiscoveryUnavailable();
        }
    }

    private void requestSnapshot() {
        if (!isAuthorized() || statusView == null) {
            return;
        }
        renderLocalSnapshot();
        if (inFlight) {
            return;
        }
        boolean refreshCritical = refreshCriticalOnLoad || !CriticalApps.hasReliableSnapshot();
        if (!refreshCritical) {
            return;
        }
        refreshCriticalOnLoad = false;
        inFlight = true;
        int sequence = ++workSequence;
        int workGeneration = generation;
        Context context = getApplicationContext();
        WeakReference<ContentFilterSettingsActivity> reference = new WeakReference<>(this);
        boolean submitted = EXECUTOR.tryExecute(() -> {
            Snapshot result = null;
            try {
                if (refreshCritical) {
                    CriticalApps.refresh(context);
                }
                result = Snapshot.load(context);
            } catch (RuntimeException ignored) {
                // No screen content or package details are logged.
            }
            ContentFilterSettingsActivity activity = reference.get();
            if (activity != null) {
                Snapshot loaded = result;
                activity.runOnUiThread(() -> activity.completeWork(sequence, workGeneration, loaded));
            }
        });
        if (!submitted) {
            inFlight = false;
            refreshCriticalOnLoad |= refreshCritical;
            showDiscoveryUnavailable();
            return;
        }
        discoveryTimeout = () -> {
            if (sequence == workSequence && inFlight && isAuthorized()) {
                generation++;
                showDiscoveryUnavailable();
            }
        };
        handler.postDelayed(discoveryTimeout, DISCOVERY_TIMEOUT_MS);
    }

    private void showDiscoveryUnavailable() {
        discoveryUnavailable = true;
        refreshCriticalOnLoad = true;
        if (statusView != null && isAuthorized()) {
            String message = getString(R.string.content_filter_load_error);
            if (!message.contentEquals(statusView.getText())) {
                statusView.setText(message);
            }
            UiStyle.setStatus(statusView, UiStyle.STATUS_WARNING);
            androidAccessView.setText(R.string.content_filter_android_access_checking);
            setControlsEnabled(false);
        }
    }

    private void completeWork(int sequence, int workGeneration, Snapshot loaded) {
        if (sequence != workSequence) {
            return;
        }
        inFlight = false;
        if (discoveryTimeout != null) {
            handler.removeCallbacks(discoveryTimeout);
        }
        if (destroyed || !isAuthorized()) {
            return;
        }
        if (workGeneration != generation) {
            requestSnapshot();
            return;
        }
        if (loaded == null) {
            showDiscoveryUnavailable();
            return;
        }
        discoveryUnavailable = false;
        renderSnapshot(loaded);
    }

    private void renderSnapshot(Snapshot loaded) {
        boolean choicesChanged = snapshot == null
                || !snapshot.configured.equals(loaded.configured)
                || !snapshot.critical.equals(loaded.critical)
                || snapshot.reliable != loaded.reliable
                || choices.getChildCount() == 0;
        snapshot = loaded;
        String displayedStatus = discoveryUnavailable
                ? getString(R.string.content_filter_load_error) : loaded.status;
        if (!displayedStatus.contentEquals(statusView.getText())) {
            statusView.setText(displayedStatus);
        }
        UiStyle.setStatus(statusView, !discoveryUnavailable
                && loaded.state == ContentFilterAccess.State.READY
                ? UiStyle.STATUS_READY : UiStyle.STATUS_WARNING);
        androidAccessView.setText(loaded.androidAccessState == ContentFilterAccess.AndroidAccessState.ENABLED
                ? R.string.content_filter_android_access_enabled
                : loaded.androidAccessState == ContentFilterAccess.AndroidAccessState.DISABLED
                        ? R.string.content_filter_android_access_not_enabled
                        : R.string.content_filter_android_access_checking);
        warningView.setText(loaded.warning);
        warningView.setVisibility(loaded.warning.isEmpty() ? View.GONE : View.VISIBLE);
        consentButton.setText(loaded.consent ? R.string.content_filter_withdraw_consent
                : R.string.content_filter_review_disclosure);
        if (choicesChanged) {
            renderChoices(loaded);
        }
        setControlsEnabled(!discoveryUnavailable);
    }

    private void renderChoices(Snapshot loaded) {
        rendering = true;
        choices.removeAllViews();
        for (ContentFilter filter : ContentFilter.values()) {
            Switch choice = UiStyle.settingsSwitch(this, getString(label(filter)));
            choice.setId(viewId(filter));
            choice.setTag(filter);
            boolean critical = loaded.critical.contains(filter);
            choice.setChecked(!critical && loaded.configured.contains(filter));
            choice.setEnabled(!critical);
            choice.setOnCheckedChangeListener((button, checked) -> {
                if (rendering) {
                    return;
                }
                if (!isAuthorized()) {
                    showLockedScreen();
                    return;
                }
                submitMutation(() -> {
                    if (!checked || CriticalApps.canFilterFromSnapshot(filter.packageName())) {
                        ContentFilterSettings.setEnabled(getApplicationContext(), filter, checked);
                    }
                }, false);
            });
            choices.addView(choice, UiStyle.fullWidth(this, 4));
            if (critical) {
                TextView unavailable = UiStyle.helperText(this, getString(loaded.reliable
                        ? R.string.content_filter_critical_unavailable
                        : R.string.content_filter_safety_unavailable));
                choices.addView(unavailable, UiStyle.fullWidth(this, 12));
            }
        }
        rendering = false;
    }

    private static int label(ContentFilter filter) {
        switch (filter) {
            case YOUTUBE_SHORTS: return R.string.content_filter_youtube_shorts;
            case INSTAGRAM_REELS: return R.string.content_filter_instagram_reels;
            case FACEBOOK_REELS: return R.string.content_filter_facebook_reels;
            case SNAPCHAT_SPOTLIGHT: return R.string.content_filter_snapchat_spotlight;
            case INSTAGRAM_STORIES: return R.string.content_filter_instagram_stories;
            default: throw new IllegalArgumentException("Unknown filter");
        }
    }

    private static int viewId(ContentFilter filter) {
        switch (filter) {
            case YOUTUBE_SHORTS: return R.id.content_filter_youtube_shorts;
            case INSTAGRAM_REELS: return R.id.content_filter_instagram_reels;
            case FACEBOOK_REELS: return R.id.content_filter_facebook_reels;
            case SNAPCHAT_SPOTLIGHT: return R.id.content_filter_snapchat_spotlight;
            case INSTAGRAM_STORIES: return R.id.content_filter_instagram_stories;
            default: throw new IllegalArgumentException("Unknown filter");
        }
    }

    /** Pure process-local identity policy, separated for focused JVM tests. */
    static final class SessionPinIdentities {
        private final Map<String, String> identities = new ConcurrentHashMap<>();

        void remember(String session, String identity) {
            if (session != null && !session.isEmpty() && identity != null && !identity.isEmpty()) {
                identities.put(session, identity);
            }
        }

        boolean contains(String session) {
            return session != null && identities.containsKey(session);
        }

        boolean matches(String session, String currentIdentity) {
            String expected = session == null ? null : identities.get(session);
            if (expected == null || !expected.equals(currentIdentity)) {
                remove(session);
                return false;
            }
            return true;
        }

        void remove(String session) {
            if (session != null) {
                identities.remove(session);
            }
        }
    }

    private static final class Snapshot {
        final Set<ContentFilter> configured;
        final Set<ContentFilter> critical;
        final boolean consent;
        final ContentFilterAccess.AndroidAccessState androidAccessState;
        final boolean reliable;
        final ContentFilterAccess.State state;
        final String status;
        final String warning;

        private Snapshot(Context context) {
            configured = ContentFilterSettings.configuredFilters(context);
            critical = EnumSet.noneOf(ContentFilter.class);
            for (ContentFilter filter : ContentFilter.values()) {
                if (!CriticalApps.canFilterFromSnapshot(filter.packageName())) {
                    critical.add(filter);
                }
            }
            reliable = CriticalApps.hasReliableSnapshot();
            if (!reliable) {
                critical.addAll(EnumSet.allOf(ContentFilter.class));
            }
            consent = ContentFilterSettings.hasConsent(context);
            // Cached Android setting hint only; independent of Duty, consent and live health.
            androidAccessState = ContentFilterAccess.androidAccessState(context);
            ContentFilterAccess.State accessState = ContentFilterAccess.state(context);
            String accessStatus = ContentFilterAccess.statusText(context);
            String accessWarning = ContentFilterAccess.warning(context);
            boolean unsafeSelection = false;
            for (ContentFilter filter : configured) {
                unsafeSelection |= critical.contains(filter);
            }
            state = unsafeSelection ? ContentFilterAccess.State.UNAVAILABLE : accessState;
            status = unsafeSelection ? context.getString(R.string.content_filter_unsafe_selection) : accessStatus;
            warning = unsafeSelection ? context.getString(reliable
                    ? R.string.content_filter_critical_unavailable
                    : R.string.content_filter_safety_unavailable) : accessWarning;
        }

        static Snapshot load(Context context) {
            return new Snapshot(context);
        }
    }
}
