package com.dankhole.airlockandroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.method.PasswordTransformationMethod;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Toast;

final class MasterPinPrompt {
    private final Activity activity;
    private final Runnable onMissingPin;

    MasterPinPrompt(Activity activity, Runnable onMissingPin) {
        this.activity = activity;
        this.onMissingPin = onMissingPin;
    }

    void show(String title, Runnable onVerified, Runnable onCanceled) {
        if (!Preferences.hasMasterPin(activity)) {
            Toast.makeText(
                    activity,
                    R.string.master_pin_missing_toast,
                    Toast.LENGTH_LONG
            ).show();
            runIfPresent(onCanceled);
            onMissingPin.run();
            return;
        }

        EditText input = pinInput();
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(R.string.master_pin_prompt_message)
                .setView(input)
                .setNegativeButton(R.string.cancel, (ignored, which) -> runIfPresent(onCanceled))
                .setPositiveButton(R.string.continue_action, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String pin = input.getText().toString().trim();
                if (!Preferences.verifyMasterPin(activity, pin)) {
                    input.setError(activity.getString(R.string.master_pin_invalid));
                    return;
                }
                dialog.dismiss();
                onVerified.run();
            });
            input.setOnEditorActionListener((view, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                    return true;
                }
                return false;
            });
            input.requestFocus();
            KeyboardHelper.show(activity, input);
        });
        dialog.setOnCancelListener(ignored -> runIfPresent(onCanceled));
        dialog.show();
    }

    private EditText pinInput() {
        EditText input = new EditText(activity);
        input.setId(R.id.master_pin_dialog_input);
        input.setHint(R.string.master_pin_hint);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        KeyboardHelper.prepareSecureNumericInput(input);
        input.setTransformationMethod(PasswordTransformationMethod.getInstance());
        input.setTextColor(UiStyle.COLOR_TEXT_PRIMARY);
        input.setHintTextColor(UiStyle.COLOR_TEXT_MUTED);
        KeyboardHelper.installKeyboardInteraction(activity, input, null);
        return input;
    }

    private void runIfPresent(Runnable callback) {
        if (callback != null) {
            callback.run();
        }
    }
}
