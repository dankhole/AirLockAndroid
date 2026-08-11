package com.dankhole.airlockandroid;

import android.content.Context;
import android.graphics.Rect;
import android.text.Editable;
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

        TextView title = UiStyle.overlayTitle(context, context.getString(R.string.blocker_title));
        title.setId(R.id.blocker_title);
        card.addView(title, UiStyle.fullWidth(context, 8));
        TextView detail = UiStyle.overlayBody(
                context,
                context.getResources().getQuantityString(
                        R.plurals.blocker_usage_summary,
                        limitMinutes,
                        appLabel,
                        usedMinutes,
                        limitMinutes
                )
        );
        detail.setId(R.id.blocker_summary);
        detail.setGravity(Gravity.CENTER);
        card.addView(detail, UiStyle.fullWidth(context, 16));

        TextView requestStatus = UiStyle.statusText(context);
        requestStatus.setId(R.id.blocker_request_status);
        requestStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        updateRequestStatus(requestStatus, formState.lastRequestedMinutes);
        card.addView(requestStatus, UiStyle.fullWidth(context, 14));

        TextView errorText = UiStyle.statusText(context);
        errorText.setId(R.id.blocker_error);
        errorText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);

        TextView minutesLabel = UiStyle.overlayStepLabel(
                context,
                context.getString(R.string.blocker_minutes_label)
        );
        EditText minutesInput = overlayNumberInput(
                context.getString(R.string.blocker_minutes_hint),
                MINUTES_INPUT_TAG,
                EditorInfo.IME_ACTION_NEXT
        );
        minutesInput.setId(R.id.blocker_minutes);
        minutesLabel.setLabelFor(minutesInput.getId());
        minutesInput.setText(formState.requestedMinutesText);
        minutesInput.setSelectAllOnFocus(true);
        UiStyle.styleOverlayInput(minutesInput, formState.minutesInputError);
        installInputBehavior(scrollView, minutesInput, () -> {
            formState.requestedMinutesText = minutesInput.getText().toString();
            formState.minutesInputError = false;
            UiStyle.styleOverlayInput(minutesInput, false);
            hideError(errorText, formState);
        });
        card.addView(minutesLabel, UiStyle.fullWidth(context, 6));
        card.addView(minutesInput, UiStyle.fullWidth(context, 8));

        Button textCodeButton = UiStyle.primaryButton(
                context,
                context.getString(R.string.blocker_text_keyholder)
        );
        textCodeButton.setId(R.id.blocker_text_keyholder);
        card.addView(textCodeButton, UiStyle.buttonParams(context));

        TextView codeLabel = UiStyle.overlayStepLabel(
                context,
                context.getString(R.string.blocker_code_label)
        );
        EditText codeInput = overlayNumberInput(
                context.getString(R.string.blocker_code_hint),
                CODE_INPUT_TAG,
                EditorInfo.IME_ACTION_DONE
        );
        codeInput.setId(R.id.blocker_approval_code);
        codeLabel.setLabelFor(codeInput.getId());
        codeInput.setText(formState.approvalCodeText);
        UiStyle.styleOverlayInput(codeInput, formState.codeInputError);
        installInputBehavior(scrollView, codeInput, () -> {
            formState.approvalCodeText = codeInput.getText().toString();
            formState.codeInputError = false;
            UiStyle.styleOverlayInput(codeInput, false);
            hideError(errorText, formState);
        });
        card.addView(codeLabel, UiStyle.fullWidth(context, 6));
        card.addView(codeInput, UiStyle.fullWidth(context, 8));

        restoreError(errorText, formState);
        card.addView(errorText, UiStyle.fullWidth(context, 10));

        Button unlockButton = UiStyle.primaryButton(
                context,
                context.getString(R.string.blocker_unlock)
        );
        unlockButton.setId(R.id.blocker_unlock);
        card.addView(unlockButton, UiStyle.buttonParams(context));

        TextView emergencyHint = UiStyle.statusText(context);
        emergencyHint.setId(R.id.blocker_emergency_hint);
        emergencyHint.setText(R.string.blocker_emergency_hint);
        UiStyle.setStatus(emergencyHint, UiStyle.STATUS_WARNING);
        emergencyHint.setVisibility(formState.emergencyHelpVisible ? View.VISIBLE : View.GONE);
        card.addView(emergencyHint, UiStyle.fullWidth(context, 8));

        Button emergencyHelpButton = UiStyle.quietButton(
                context,
                context.getString(formState.emergencyHelpVisible
                        ? R.string.blocker_hide_emergency
                        : R.string.blocker_use_emergency)
        );
        emergencyHelpButton.setId(R.id.blocker_emergency_toggle);
        emergencyHelpButton.setOnClickListener(v -> {
            formState.emergencyHelpVisible = !formState.emergencyHelpVisible;
            emergencyHint.setVisibility(formState.emergencyHelpVisible ? View.VISIBLE : View.GONE);
            emergencyHelpButton.setText(
                    formState.emergencyHelpVisible
                            ? R.string.blocker_hide_emergency
                            : R.string.blocker_use_emergency
            );
            if (formState.emergencyHelpVisible) {
                emergencyHint.announceForAccessibility(emergencyHint.getText());
            }
        });
        card.addView(emergencyHelpButton, UiStyle.buttonParams(context));

        Button leaveButton = UiStyle.overlaySecondaryButton(
                context,
                context.getString(R.string.blocker_leave)
        );
        leaveButton.setId(R.id.blocker_leave);
        card.addView(leaveButton, UiStyle.buttonParams(context));

        textCodeButton.setOnClickListener(v -> {
            listener.onFormInteraction();
            int requestedMinutes = parsePositiveInt(minutesInput);
            if (requestedMinutes <= 0) {
                formState.minutesInputError = true;
                UiStyle.styleOverlayInput(minutesInput, true);
                showError(errorText, formState, context.getString(R.string.blocker_minutes_error));
                minutesInput.requestFocus();
                return;
            }
            if (listener.requestApproval(packageName, requestedMinutes)) {
                formState.requestedMinutesText = String.valueOf(requestedMinutes);
                formState.lastRequestedMinutes = requestedMinutes;
                formState.minutesInputError = false;
                minutesInput.setText(formState.requestedMinutesText);
                updateRequestStatus(requestStatus, requestedMinutes);
                hideError(errorText, formState);
                codeInput.requestFocus();
                KeyboardHelper.show(context, codeInput);
            }
        });

        unlockButton.setOnClickListener(v -> {
            listener.onFormInteraction();
            String entered = codeInput.getText().toString().trim();
            if (entered.isEmpty()) {
                formState.codeInputError = true;
                UiStyle.styleOverlayInput(codeInput, true);
                showError(
                        errorText,
                        formState,
                        context.getString(R.string.blocker_code_required)
                );
                codeInput.requestFocus();
                return;
            }
            if (listener.consumeEmergencyCode(entered)) {
                formState.approvalCodeText = "";
                formState.errorMessage = "";
                showEmergencyPauseCelebration(card);
                return;
            }

            int approvedMinutes = listener.redeemApprovalCode(packageName, entered);
            if (approvedMinutes > 0) {
                showUnlockCelebration(card, approvedMinutes);
            } else if (approvedMinutes == Preferences.APPROVAL_REDEMPTION_SAVE_FAILED) {
                formState.codeInputError = true;
                UiStyle.styleOverlayInput(codeInput, true);
                showError(
                        errorText,
                        formState,
                        context.getString(R.string.blocker_code_save_failed)
                );
            } else {
                formState.codeInputError = true;
                UiStyle.styleOverlayInput(codeInput, true);
                showError(
                        errorText,
                        formState,
                        context.getString(R.string.blocker_code_invalid)
                );
            }
        });
        codeInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                unlockButton.performClick();
                return true;
            }
            return false;
        });
        leaveButton.setOnClickListener(v -> listener.onLeaveApp());

        return UiStyle.overlayWindowRoot(context, scrollView);
    }

    void onAttached(View overlayView) {
        installBackHandler(overlayView);
        overlayView.setFocusableInTouchMode(true);
        overlayView.requestFocus();
        KeyboardHelper.hide(context, overlayView);
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

    void clearFormState(String packageName) {
        formStates.remove(packageName);
    }

    void clearAllFormStates() {
        formStates.clear();
    }

    private EditText overlayNumberInput(String hint, String tag, int imeAction) {
        EditText input = new EditText(context);
        input.setTag(tag);
        input.setHint(hint);
        KeyboardHelper.prepareNumericInput(input);
        input.setSingleLine(true);
        input.setImeOptions(imeAction);
        input.setGravity(Gravity.CENTER);
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

    private void showUnlockCelebration(LinearLayout card, int approvedMinutes) {
        listener.onUnlockCelebrationStarted();
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

    private void updateRequestStatus(TextView status, int requestedMinutes) {
        if (requestedMinutes > 0) {
            status.setText(context.getResources().getQuantityString(
                    R.plurals.blocker_request_sent,
                    requestedMinutes,
                    requestedMinutes
            ));
            UiStyle.setStatus(status, UiStyle.STATUS_READY);
        } else {
            status.setText(R.string.blocker_request_intro);
            UiStyle.setStatus(status, UiStyle.STATUS_WARNING);
        }
    }

    private void restoreError(TextView errorText, FormState formState) {
        if (formState.errorMessage.isEmpty()) {
            errorText.setVisibility(View.GONE);
        } else {
            errorText.setText(formState.errorMessage);
            UiStyle.setStatus(errorText, UiStyle.STATUS_REQUIRED);
            errorText.setVisibility(View.VISIBLE);
        }
    }

    private void showError(TextView errorText, FormState formState, String message) {
        formState.errorMessage = context.getString(R.string.required_message, message);
        errorText.setText(formState.errorMessage);
        UiStyle.setStatus(errorText, UiStyle.STATUS_REQUIRED);
        errorText.setVisibility(View.VISIBLE);
    }

    private void hideError(TextView errorText, FormState formState) {
        formState.errorMessage = "";
        errorText.setText("");
        errorText.setVisibility(View.GONE);
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
        String requestedMinutesText = "5";
        String approvalCodeText = "";
        String errorMessage = "";
        boolean minutesInputError;
        boolean codeInputError;
        boolean emergencyHelpVisible;
        int lastRequestedMinutes = -1;
    }
}
