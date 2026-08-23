package com.dankhole.airlockandroid;

import android.content.Context;
import android.graphics.Rect;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

final class BlockerOverlayController {
    private static final String MINUTES_INPUT_TAG = "minutes_input";
    private static final String CODE_INPUT_TAG = "code_input";
    private static final String EMERGENCY_INPUT_TAG = "emergency_input";

    private static final int SCREEN_HOME = 0;
    private static final int SCREEN_REQUEST = 1;
    private static final int SCREEN_APPROVAL = 2;
    private static final int SCREEN_EMERGENCY = 3;

    private final Context context;
    private final Listener listener;
    private final Map<String, FormState> formStates = new HashMap<>();

    BlockerOverlayController(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    View build(String packageName, String appLabel, long usedMinutes, int limitMinutes) {
        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(true);
        scrollView.setBackgroundColor(UiStyle.COLOR_OVERLAY_BACKGROUND);

        LinearLayout root = new LinearLayout(context);
        root.setId(R.id.blocker_root);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(UiStyle.COLOR_OVERLAY_BACKGROUND);
        UiStyle.applyScreenInsetsPadding(scrollView, root, 20, 24, 20, 24);
        UiStyle.attachOverlayContent(scrollView, root);

        LinearLayout card = UiStyle.overlayCard(context);
        root.addView(card, UiStyle.fullWidth(context, 0));
        FormState formState = formState(packageName);
        formState.appLabel = appLabel;
        formState.usedMinutes = usedMinutes;
        formState.limitMinutes = limitMinutes;
        renderCurrentScreen(scrollView, card, packageName, formState);

        return UiStyle.overlayWindowRoot(context, scrollView);
    }

    void onAttached(View overlayView) {
        installBackHandler(overlayView);
        overlayView.setFocusableInTouchMode(true);
        overlayView.requestFocus();
        KeyboardHelper.hide(context, overlayView);
    }

    void clearFormState(String packageName) {
        formStates.remove(packageName);
    }

    void clearAllFormStates() {
        formStates.clear();
    }

    private void renderCurrentScreen(
            ScrollView scrollView,
            LinearLayout card,
            String packageName,
            FormState formState
    ) {
        card.removeAllViews();
        card.setGravity(Gravity.NO_GRAVITY);
        if (formState.screen == SCREEN_REQUEST) {
            renderRequestScreen(scrollView, card, packageName, formState);
        } else if (formState.screen == SCREEN_APPROVAL) {
            renderApprovalScreen(scrollView, card, packageName, formState);
        } else if (formState.screen == SCREEN_EMERGENCY) {
            renderEmergencyScreen(scrollView, card, packageName, formState);
        } else {
            formState.screen = SCREEN_HOME;
            renderHomeScreen(scrollView, card, packageName, formState);
        }
        installBackHandler(card);
        scrollView.post(() -> scrollView.scrollTo(0, 0));
    }

    private void renderHomeScreen(
            ScrollView scrollView,
            LinearLayout card,
            String packageName,
            FormState formState
    ) {
        TextView badge = UiStyle.badge(
                context,
                context.getString(R.string.blocker_home_badge),
                UiStyle.STATUS_REQUIRED
        );
        card.addView(badge, UiStyle.fullWidth(context, 10));

        TextView title = UiStyle.overlayTitle(
                context,
                context.getString(R.string.blocker_home_title, formState.appLabel)
        );
        title.setId(R.id.blocker_title);
        card.addView(title, UiStyle.fullWidth(context, 8));

        TextView detail = UiStyle.overlayBody(
                context,
                context.getResources().getQuantityString(
                        R.plurals.blocker_usage_detail,
                        formState.limitMinutes,
                        formState.usedMinutes,
                        formState.limitMinutes
                )
        );
        detail.setId(R.id.blocker_summary);
        detail.setGravity(Gravity.CENTER);
        card.addView(detail, UiStyle.fullWidth(context, 16));

        Preferences.PendingApprovalSummary pending =
                Preferences.pendingApprovalSummary(context, packageName);
        if (pending.hasRequests()) {
            card.addView(pendingStatus(pending, false), UiStyle.fullWidth(context, 16));
        }

        TextView question = UiStyle.overlayStepLabel(
                context,
                context.getString(R.string.blocker_home_question)
        );
        card.addView(question, UiStyle.fullWidth(context, 6));

        if (pending.hasRequests()) {
            Button approvalButton = UiStyle.overlayActionButton(
                    context,
                    context.getString(R.string.blocker_enter_approval_action),
                    approvalActionBody(),
                    true
            );
            approvalButton.setId(R.id.blocker_enter_approval);
            approvalButton.setOnClickListener(v -> navigateTo(
                    SCREEN_APPROVAL,
                    scrollView,
                    card,
                    packageName,
                    formState
            ));
            card.addView(approvalButton, UiStyle.buttonParams(context));

            Button requestButton = UiStyle.overlayActionButton(
                    context,
                    context.getString(R.string.blocker_new_request_action),
                    newRequestActionBody(pending),
                    false
            );
            requestButton.setId(R.id.blocker_new_request);
            requestButton.setOnClickListener(v -> navigateTo(
                    SCREEN_REQUEST,
                    scrollView,
                    card,
                    packageName,
                    formState
            ));
            card.addView(requestButton, UiStyle.buttonParams(context));
        } else {
            Button requestButton = UiStyle.overlayActionButton(
                    context,
                    context.getString(R.string.blocker_request_action),
                    context.getString(R.string.blocker_request_action_body),
                    true
            );
            requestButton.setId(R.id.blocker_new_request);
            requestButton.setOnClickListener(v -> navigateTo(
                    SCREEN_REQUEST,
                    scrollView,
                    card,
                    packageName,
                    formState
            ));
            card.addView(requestButton, UiStyle.buttonParams(context));
        }

        Button emergencyButton = UiStyle.overlayQuietActionButton(
                context,
                context.getString(R.string.blocker_emergency_action),
                context.getString(R.string.blocker_emergency_action_body)
        );
        emergencyButton.setId(R.id.blocker_emergency_option);
        emergencyButton.setOnClickListener(v -> navigateTo(
                SCREEN_EMERGENCY,
                scrollView,
                card,
                packageName,
                formState
        ));
        card.addView(emergencyButton, UiStyle.buttonParams(context));

        Button leaveButton = UiStyle.overlaySecondaryButton(
                context,
                context.getString(R.string.blocker_leave)
        );
        leaveButton.setId(R.id.blocker_leave);
        leaveButton.setOnClickListener(v -> listener.onLeaveApp());
        card.addView(leaveButton, UiStyle.buttonParams(context));
    }

    private void renderRequestScreen(
            ScrollView scrollView,
            LinearLayout card,
            String packageName,
            FormState formState
    ) {
        addFlowHeader(
                scrollView,
                card,
                packageName,
                formState,
                R.string.blocker_request_title,
                R.string.blocker_request_body
        );

        Preferences.PendingApprovalSummary pending =
                Preferences.pendingApprovalSummary(context, packageName);
        if (pending.hasRequests()) {
            card.addView(pendingStatus(pending, true), UiStyle.fullWidth(context, 14));
        }

        TextView errorText = errorText();
        TextView minutesLabel = UiStyle.overlayStepLabel(
                context,
                context.getString(R.string.blocker_minutes_label)
        );
        EditText minutesInput = overlayNumberInput(
                context.getString(R.string.blocker_minutes_hint),
                MINUTES_INPUT_TAG,
                EditorInfo.IME_ACTION_DONE,
                5
        );
        minutesInput.setId(R.id.blocker_minutes);
        minutesLabel.setLabelFor(minutesInput.getId());
        minutesInput.setText(formState.requestedMinutesText);
        minutesInput.setSelectAllOnFocus(true);
        UiStyle.styleOverlayInput(minutesInput, formState.minutesInputError);
        installInputBehavior(scrollView, minutesInput, () -> {
            formState.requestedMinutesText = minutesInput.getText().toString();
            formState.minutesInputError = false;
            formState.requestErrorMessage = "";
            UiStyle.styleOverlayInput(minutesInput, false);
            hideError(errorText);
        });
        card.addView(minutesLabel, UiStyle.fullWidth(context, 6));
        card.addView(minutesInput, UiStyle.fullWidth(context, 8));

        restoreError(errorText, formState.requestErrorMessage);
        card.addView(errorText, UiStyle.fullWidth(context, 8));

        Button submitButton = UiStyle.primaryButton(
                context,
                context.getString(R.string.blocker_request_submit)
        );
        submitButton.setId(R.id.blocker_text_keyholder);
        submitButton.setOnClickListener(v -> {
            listener.onFormInteraction();
            int requestedMinutes = parsePositiveInt(minutesInput);
            if (requestedMinutes <= 0) {
                formState.minutesInputError = true;
                UiStyle.styleOverlayInput(minutesInput, true);
                formState.requestErrorMessage = required(
                        context.getString(R.string.blocker_minutes_error)
                );
                showError(errorText, formState.requestErrorMessage);
                minutesInput.requestFocus();
                return;
            }
            if (listener.requestApproval(packageName, requestedMinutes)) {
                formState.requestedMinutesText = String.valueOf(requestedMinutes);
                formState.minutesInputError = false;
                formState.requestErrorMessage = "";
                formState.screen = SCREEN_HOME;
                KeyboardHelper.hide(context, card);
                renderCurrentScreen(scrollView, card, packageName, formState);
            }
        });
        card.addView(submitButton, UiStyle.buttonParams(context));
        minutesInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitButton.performClick();
                return true;
            }
            return false;
        });
    }

    private void renderApprovalScreen(
            ScrollView scrollView,
            LinearLayout card,
            String packageName,
            FormState formState
    ) {
        addFlowHeader(
                scrollView,
                card,
                packageName,
                formState,
                R.string.blocker_approval_title,
                R.string.blocker_approval_body
        );

        Preferences.PendingApprovalSummary pending =
                Preferences.pendingApprovalSummary(context, packageName);
        View status;
        if (pending.hasRequests()) {
            status = pendingStatus(pending, false);
        } else {
            status = statusCard(
                    context.getString(R.string.blocker_no_pending_title),
                    UiStyle.STATUS_WARNING,
                    context.getString(R.string.blocker_no_pending_value),
                    context.getString(R.string.blocker_no_pending_detail)
            );
        }
        card.addView(status, UiStyle.fullWidth(context, 14));

        TextView errorText = errorText();
        TextView codeLabel = UiStyle.overlayStepLabel(
                context,
                context.getString(R.string.blocker_code_label)
        );
        EditText codeInput = overlayNumberInput(
                context.getString(R.string.blocker_code_hint),
                CODE_INPUT_TAG,
                EditorInfo.IME_ACTION_DONE,
                4
        );
        codeInput.setId(R.id.blocker_approval_code);
        codeLabel.setLabelFor(codeInput.getId());
        codeInput.setText(formState.approvalCodeText);
        UiStyle.styleOverlayInput(codeInput, formState.codeInputError);
        installInputBehavior(scrollView, codeInput, () -> {
            formState.approvalCodeText = codeInput.getText().toString();
            formState.codeInputError = false;
            formState.approvalErrorMessage = "";
            UiStyle.styleOverlayInput(codeInput, false);
            hideError(errorText);
        });
        card.addView(codeLabel, UiStyle.fullWidth(context, 6));
        card.addView(codeInput, UiStyle.fullWidth(context, 8));

        restoreError(errorText, formState.approvalErrorMessage);
        card.addView(errorText, UiStyle.fullWidth(context, 8));

        Button unlockButton = UiStyle.primaryButton(
                context,
                context.getString(R.string.blocker_unlock)
        );
        unlockButton.setId(R.id.blocker_unlock);
        unlockButton.setOnClickListener(v -> redeemApproval(
                card,
                packageName,
                formState,
                codeInput,
                errorText
        ));
        card.addView(unlockButton, UiStyle.buttonParams(context));
        codeInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                unlockButton.performClick();
                return true;
            }
            return false;
        });

        Button anotherRequest = UiStyle.overlayQuietActionButton(
                context,
                pending.hasRequests()
                        ? context.getString(R.string.blocker_new_request_action)
                        : context.getString(R.string.blocker_request_action),
                newRequestActionBody(pending)
        );
        anotherRequest.setId(R.id.blocker_new_request);
        anotherRequest.setOnClickListener(v -> navigateTo(
                SCREEN_REQUEST,
                scrollView,
                card,
                packageName,
                formState
        ));
        card.addView(anotherRequest, UiStyle.buttonParams(context));
    }

    private void redeemApproval(
            LinearLayout card,
            String packageName,
            FormState formState,
            EditText codeInput,
            TextView errorText
    ) {
        listener.onFormInteraction();
        String entered = codeInput.getText().toString().trim();
        if (entered.isEmpty()) {
            formState.codeInputError = true;
            UiStyle.styleOverlayInput(codeInput, true);
            formState.approvalErrorMessage = required(
                    context.getString(R.string.blocker_code_required)
            );
            showError(errorText, formState.approvalErrorMessage);
            codeInput.requestFocus();
            return;
        }

        int approvedMinutes = listener.redeemApprovalCode(packageName, entered);
        if (approvedMinutes > 0) {
            formState.approvalCodeText = "";
            formState.approvalErrorMessage = "";
            showUnlockCelebration(card, approvedMinutes);
        } else if (approvedMinutes == Preferences.APPROVAL_REDEMPTION_SAVE_FAILED) {
            formState.codeInputError = true;
            UiStyle.styleOverlayInput(codeInput, true);
            formState.approvalErrorMessage = required(
                    context.getString(R.string.blocker_code_save_failed)
            );
            showError(errorText, formState.approvalErrorMessage);
        } else {
            formState.codeInputError = true;
            UiStyle.styleOverlayInput(codeInput, true);
            formState.approvalErrorMessage = required(
                    context.getString(R.string.blocker_code_invalid)
            );
            showError(errorText, formState.approvalErrorMessage);
        }
    }

    private void renderEmergencyScreen(
            ScrollView scrollView,
            LinearLayout card,
            String packageName,
            FormState formState
    ) {
        addFlowHeader(
                scrollView,
                card,
                packageName,
                formState,
                R.string.blocker_emergency_form_title,
                R.string.blocker_emergency_hint
        );

        View emergencyHint = statusCard(
                R.id.blocker_emergency_hint,
                context.getString(R.string.blocker_emergency_status_title),
                UiStyle.STATUS_WARNING,
                context.getString(R.string.blocker_emergency_status_value),
                context.getString(R.string.blocker_emergency_status_resume),
                context.getString(R.string.blocker_emergency_status_not_reply)
        );
        card.addView(emergencyHint, UiStyle.fullWidth(context, 14));

        TextView errorText = errorText();
        TextView codeLabel = UiStyle.overlayStepLabel(
                context,
                context.getString(R.string.blocker_emergency_code_label)
        );
        EditText codeInput = overlayNumberInput(
                context.getString(R.string.blocker_emergency_code_hint),
                EMERGENCY_INPUT_TAG,
                EditorInfo.IME_ACTION_DONE,
                8
        );
        codeInput.setId(R.id.blocker_emergency_code);
        codeLabel.setLabelFor(codeInput.getId());
        codeInput.setText(formState.emergencyCodeText);
        UiStyle.styleOverlayInput(codeInput, formState.emergencyCodeInputError);
        installInputBehavior(scrollView, codeInput, () -> {
            formState.emergencyCodeText = codeInput.getText().toString();
            formState.emergencyCodeInputError = false;
            formState.emergencyErrorMessage = "";
            UiStyle.styleOverlayInput(codeInput, false);
            hideError(errorText);
        });
        card.addView(codeLabel, UiStyle.fullWidth(context, 6));
        card.addView(codeInput, UiStyle.fullWidth(context, 8));

        restoreError(errorText, formState.emergencyErrorMessage);
        card.addView(errorText, UiStyle.fullWidth(context, 8));

        Button emergencyButton = UiStyle.dangerButton(
                context,
                context.getString(R.string.blocker_emergency_submit)
        );
        emergencyButton.setId(R.id.blocker_emergency_submit);
        emergencyButton.setOnClickListener(v -> useEmergencyCode(
                card,
                formState,
                codeInput,
                errorText
        ));
        card.addView(emergencyButton, UiStyle.buttonParams(context));
        codeInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                emergencyButton.performClick();
                return true;
            }
            return false;
        });
    }

    private void useEmergencyCode(
            LinearLayout card,
            FormState formState,
            EditText codeInput,
            TextView errorText
    ) {
        listener.onFormInteraction();
        String entered = codeInput.getText().toString().trim();
        if (entered.isEmpty()) {
            formState.emergencyCodeInputError = true;
            UiStyle.styleOverlayInput(codeInput, true);
            formState.emergencyErrorMessage = required(
                    context.getString(R.string.blocker_emergency_code_required)
            );
            showError(errorText, formState.emergencyErrorMessage);
            codeInput.requestFocus();
            return;
        }
        if (listener.consumeEmergencyCode(entered)) {
            formState.emergencyCodeText = "";
            formState.emergencyErrorMessage = "";
            showEmergencyPauseCelebration(card);
        } else {
            formState.emergencyCodeInputError = true;
            UiStyle.styleOverlayInput(codeInput, true);
            formState.emergencyErrorMessage = required(
                    context.getString(R.string.blocker_emergency_code_invalid)
            );
            showError(errorText, formState.emergencyErrorMessage);
        }
    }

    private void addFlowHeader(
            ScrollView scrollView,
            LinearLayout card,
            String packageName,
            FormState formState,
            int titleResource,
            int bodyResource
    ) {
        Button backButton = UiStyle.quietButton(
                context,
                context.getString(R.string.blocker_back_to_options)
        );
        backButton.setId(R.id.blocker_flow_back);
        backButton.setOnClickListener(v -> navigateTo(
                SCREEN_HOME,
                scrollView,
                card,
                packageName,
                formState
        ));
        card.addView(backButton, UiStyle.fullWidth(context, 14));

        TextView title = UiStyle.overlayTitle(context, context.getString(titleResource));
        title.setId(R.id.blocker_title);
        card.addView(title, UiStyle.fullWidth(context, 8));

        TextView body = UiStyle.overlayBody(context, context.getString(bodyResource));
        body.setId(R.id.blocker_summary);
        body.setGravity(Gravity.CENTER);
        card.addView(body, UiStyle.fullWidth(context, 16));
    }

    private void navigateTo(
            int screen,
            ScrollView scrollView,
            LinearLayout card,
            String packageName,
            FormState formState
    ) {
        listener.onFormInteraction();
        KeyboardHelper.hide(context, card);
        formState.screen = screen;
        renderCurrentScreen(scrollView, card, packageName, formState);
    }

    private View pendingStatus(
            Preferences.PendingApprovalSummary pending,
            boolean explainOverride
    ) {
        if (pending.count == 1) {
            if (explainOverride) {
                return statusCard(
                        context.getString(R.string.blocker_request_override_title),
                        UiStyle.STATUS_WARNING,
                        context.getString(
                                R.string.blocker_request_override_single_value,
                                pending.singleMinutes
                        ),
                        context.getString(R.string.blocker_request_override_single_detail),
                        context.getString(R.string.blocker_request_override_single_result)
                );
            }
            return statusCard(
                    context.getString(R.string.blocker_pending_title),
                    UiStyle.STATUS_READY,
                    context.getString(
                            R.string.blocker_pending_single_value,
                            pending.singleMinutes
                    ),
                    context.getString(
                            R.string.blocker_pending_single_grant,
                            pending.singleMinutes
                    ),
                    context.getString(R.string.blocker_pending_expiry)
            );
        }
        if (explainOverride) {
            return statusCard(
                    context.getResources().getQuantityString(
                            R.plurals.blocker_request_override_multiple_title,
                            pending.count,
                            pending.count
                    ),
                    UiStyle.STATUS_WARNING,
                    context.getResources().getQuantityString(
                            R.plurals.blocker_request_override_multiple_value,
                            pending.count,
                            pending.count
                    ),
                    context.getString(R.string.blocker_request_override_multiple_detail),
                    context.getString(R.string.blocker_request_override_multiple_result)
            );
        }
        return statusCard(
                context.getResources().getQuantityString(
                        R.plurals.blocker_pending_multiple_title,
                        pending.count,
                        pending.count
                ),
                UiStyle.STATUS_READY,
                context.getResources().getQuantityString(
                        R.plurals.blocker_pending_multiple_value,
                        pending.count,
                        pending.count
                ),
                context.getString(R.string.blocker_pending_multiple_grant),
                context.getString(R.string.blocker_pending_expiry)
        );
    }

    private String approvalActionBody() {
        return context.getString(R.string.blocker_enter_approval_action_body);
    }

    private String newRequestActionBody(Preferences.PendingApprovalSummary pending) {
        if (pending.count == 1) {
            return context.getString(
                    R.string.blocker_new_request_single_body,
                    pending.singleMinutes
            );
        }
        if (pending.count > 1) {
            return context.getString(R.string.blocker_new_request_multiple_body);
        }
        return context.getString(R.string.blocker_new_request_no_pending_body);
    }

    private View statusCard(String title, int status, String value, String... details) {
        return statusCard(R.id.blocker_request_status, title, status, value, details);
    }

    private View statusCard(
            int titleId,
            String title,
            int status,
            String value,
            String... details
    ) {
        LinearLayout statusCard = UiStyle.overlayStatusCard(context, status);
        TextView titleView = UiStyle.overlayStatusTitle(context, title, status);
        titleView.setId(titleId);
        titleView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusCard.addView(titleView, UiStyle.fullWidth(context, 4));
        statusCard.addView(
                UiStyle.overlayStatusValue(context, value),
                UiStyle.fullWidth(context, details.length == 0 ? 0 : 3)
        );
        for (int index = 0; index < details.length; index++) {
            int bottomMargin = index == details.length - 1 ? 0 : 2;
            statusCard.addView(
                    UiStyle.overlayStatusDetail(context, details[index]),
                    UiStyle.fullWidth(context, bottomMargin)
            );
        }
        return statusCard;
    }

    private EditText overlayNumberInput(
            String hint,
            String tag,
            int imeAction,
            int maxLength
    ) {
        EditText input = new EditText(context);
        input.setTag(tag);
        input.setHint(hint);
        KeyboardHelper.prepareNumericInput(input);
        input.setSingleLine(true);
        input.setImeOptions(imeAction);
        input.setGravity(Gravity.CENTER);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        return input;
    }

    private void installInputBehavior(
            ScrollView scrollView,
            EditText input,
            Runnable afterTextChanged
    ) {
        input.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                afterTextChanged.run();
            }
        });
        KeyboardHelper.installKeyboardInteraction(
                context,
                input,
                () -> scrollToInput(scrollView, input)
        );
        input.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                KeyboardHelper.show(context, input);
                scrollToInput(scrollView, input);
            }
        });
    }

    private void installBackHandler(View view) {
        view.setOnKeyListener((focusedView, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                listener.onLeaveApp();
            }
            return true;
        });
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            installBackHandler(group.getChildAt(index));
        }
    }

    private void showUnlockCelebration(LinearLayout card, int approvedMinutes) {
        listener.onUnlockCelebrationStarted();
        KeyboardHelper.hide(context, card);
        card.removeAllViews();
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        GooseCelebrationView gooseView = new GooseCelebrationView(context);
        gooseView.setId(R.id.blocker_celebration);
        card.addView(gooseView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiStyle.dp(context, 190)
        ));
        TextView title = UiStyle.overlayTitle(
                context,
                context.getString(R.string.blocker_unlock_title)
        );
        title.setId(R.id.blocker_title);
        card.addView(title, UiStyle.fullWidth(context, 8));
        TextView body = UiStyle.overlayBody(
                context,
                context.getResources().getQuantityString(
                        R.plurals.blocker_unlock_body,
                        approvedMinutes,
                        approvedMinutes
                )
        );
        body.setId(R.id.blocker_summary);
        body.setGravity(Gravity.CENTER);
        card.addView(body, UiStyle.fullWidth(context, 0));
        card.post(() -> card.announceForAccessibility(
                context.getResources().getQuantityString(
                        R.plurals.blocker_unlock_announcement,
                        approvedMinutes,
                        approvedMinutes
                )
        ));
        gooseView.start(() -> listener.onUnlockCelebrationFinished(approvedMinutes));
    }

    private void showEmergencyPauseCelebration(LinearLayout card) {
        listener.onEmergencyCelebrationStarted();
        KeyboardHelper.hide(context, card);
        card.removeAllViews();
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        GooseCelebrationView gooseView = new GooseCelebrationView(context);
        gooseView.setId(R.id.blocker_celebration);
        card.addView(gooseView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiStyle.dp(context, 190)
        ));
        TextView title = UiStyle.overlayTitle(
                context,
                context.getString(R.string.blocker_emergency_title)
        );
        title.setId(R.id.blocker_title);
        card.addView(title, UiStyle.fullWidth(context, 8));
        TextView body = UiStyle.overlayBody(
                context,
                context.getString(R.string.blocker_emergency_body)
        );
        body.setId(R.id.blocker_summary);
        body.setGravity(Gravity.CENTER);
        card.addView(body, UiStyle.fullWidth(context, 0));
        card.post(() -> card.announceForAccessibility(
                context.getString(R.string.blocker_emergency_announcement)
        ));
        gooseView.start(listener::onEmergencyCelebrationFinished);
    }

    private TextView errorText() {
        TextView errorText = UiStyle.statusText(context);
        errorText.setId(R.id.blocker_error);
        errorText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        return errorText;
    }

    private void restoreError(TextView errorText, String message) {
        if (message.isEmpty()) {
            errorText.setVisibility(View.GONE);
        } else {
            showError(errorText, message);
        }
    }

    private void showError(TextView errorText, String message) {
        errorText.setText(message);
        UiStyle.setStatus(errorText, UiStyle.STATUS_REQUIRED);
        errorText.setVisibility(View.VISIBLE);
    }

    private void hideError(TextView errorText) {
        errorText.setText("");
        errorText.setVisibility(View.GONE);
    }

    private String required(String message) {
        return context.getString(R.string.required_message, message);
    }

    private void scrollToInput(ScrollView scrollView, View input) {
        input.postDelayed(() -> {
            if (!input.isAttachedToWindow()) {
                return;
            }
            Rect bounds = new Rect();
            input.getDrawingRect(bounds);
            scrollView.offsetDescendantRectToMyCoords(input, bounds);
            int targetY = Math.max(0, bounds.top - UiStyle.dp(context, 80));
            scrollView.smoothScrollTo(0, targetY);
        }, 250);
    }

    private int parsePositiveInt(EditText input) {
        try {
            int parsed = Integer.parseInt(input.getText().toString().trim());
            return parsed > 0 ? parsed : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private FormState formState(String packageName) {
        FormState state = formStates.get(packageName);
        if (state == null) {
            state = new FormState();
            formStates.put(packageName, state);
        }
        return state;
    }

    interface Listener {
        void onFormInteraction();

        boolean requestApproval(String packageName, int requestedMinutes);

        int redeemApprovalCode(String packageName, String enteredCode);

        boolean consumeEmergencyCode(String enteredCode);

        void onLeaveApp();

        void onUnlockCelebrationStarted();

        void onUnlockCelebrationFinished(int approvedMinutes);

        void onEmergencyCelebrationStarted();

        void onEmergencyCelebrationFinished();
    }

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence text, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence text, int start, int before, int count) {
        }
    }

    private static final class FormState {
        int screen = SCREEN_HOME;
        String appLabel = "";
        long usedMinutes;
        int limitMinutes = 1;
        String requestedMinutesText = "5";
        String approvalCodeText = "";
        String emergencyCodeText = "";
        String requestErrorMessage = "";
        String approvalErrorMessage = "";
        String emergencyErrorMessage = "";
        boolean minutesInputError;
        boolean codeInputError;
        boolean emergencyCodeInputError;
    }
}
