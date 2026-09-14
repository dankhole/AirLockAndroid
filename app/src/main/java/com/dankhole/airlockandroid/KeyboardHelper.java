package com.dankhole.airlockandroid;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

final class KeyboardHelper {
    private KeyboardHelper() {
    }

    static void prepareNumericInput(EditText input) {
        input.setFocusable(true);
        input.setFocusableInTouchMode(true);
        input.setCursorVisible(true);
        input.setInputType(InputType.TYPE_CLASS_PHONE);
        input.setRawInputType(InputType.TYPE_CLASS_PHONE);
    }

    static void prepareSecureNumericInput(EditText input) {
        input.setFocusable(true);
        input.setFocusableInTouchMode(true);
        input.setCursorVisible(true);
        int inputType = InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        input.setInputType(inputType);
    }

    @SuppressLint("ClickableViewAccessibility")
    static void installKeyboardInteraction(
            Context context,
            EditText input,
            Runnable afterInteraction
    ) {
        // Keep native EditText touch/cursor handling, and mirror the touch work
        // in OnClick so accessibility performClick actions follow the same path.
        input.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                show(context, input);
                runAfterInteraction(afterInteraction);
            }
            return false;
        });
        input.setOnClickListener(view -> {
            show(context, input);
            runAfterInteraction(afterInteraction);
        });
    }

    static void show(Context context, EditText input) {
        input.setFocusable(true);
        input.setFocusableInTouchMode(true);
        input.setCursorVisible(true);
        input.requestFocus();

        input.postDelayed(() -> requestKeyboard(context, input), 40);
        input.postDelayed(() -> requestKeyboard(context, input), 160);
    }

    static void hide(Context context, View view) {
        InputMethodManager inputMethodManager =
                (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputMethodManager != null) {
            inputMethodManager.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private static void requestKeyboard(Context context, EditText input) {
        if (!input.isAttachedToWindow() || !input.isShown()
                || !input.hasWindowFocus() || !input.hasFocus()) {
            return;
        }
        InputMethodManager inputMethodManager =
                (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputMethodManager != null) {
            // SHOW_FORCED can leave the keyboard visible across apps on older
            // Android versions. These requests follow an explicit input tap.
            inputMethodManager.showSoftInput(input, 0);
        }
    }

    private static void runAfterInteraction(Runnable afterInteraction) {
        if (afterInteraction != null) {
            afterInteraction.run();
        }
    }
}
