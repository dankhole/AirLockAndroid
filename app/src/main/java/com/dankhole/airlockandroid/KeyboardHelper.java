package com.dankhole.airlockandroid;

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

    static boolean showOnTouch(Context context, EditText input, MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            show(context, input);
        }
        return false;
    }

    static void show(Context context, EditText input) {
        input.setFocusable(true);
        input.setFocusableInTouchMode(true);
        input.setCursorVisible(true);
        input.requestFocus();

        input.postDelayed(() -> requestKeyboard(context, input, InputMethodManager.SHOW_IMPLICIT), 40);
        input.postDelayed(() -> {
            if (input.hasFocus()) {
                requestKeyboard(context, input, InputMethodManager.SHOW_FORCED);
            }
        }, 160);
    }

    static void hide(Context context, View view) {
        InputMethodManager inputMethodManager =
                (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputMethodManager != null) {
            inputMethodManager.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private static void requestKeyboard(Context context, EditText input, int flags) {
        InputMethodManager inputMethodManager =
                (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputMethodManager != null) {
            inputMethodManager.showSoftInput(input, flags);
        }
    }
}
