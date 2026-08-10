package com.dankhole.airlockandroid;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class UiStyle {
    private static final int SCREEN_CONTENT_MAX_WIDTH_DP = 720;
    private static final int OVERLAY_CONTENT_MAX_WIDTH_DP = 600;
    static final int COLOR_BACKGROUND = Color.rgb(18, 22, 27);
    static final int COLOR_SURFACE = Color.rgb(28, 34, 40);
    static final int COLOR_SURFACE_ALT = Color.rgb(35, 43, 49);
    static final int COLOR_OVERLAY_BACKGROUND = Color.rgb(10, 13, 16);
    static final int COLOR_OVERLAY_SURFACE = Color.rgb(28, 34, 40);
    static final int COLOR_SYSTEM_BAR_CONTRAST = Color.rgb(117, 117, 117);
    static final int COLOR_TEXT_PRIMARY = Color.rgb(239, 244, 242);
    static final int COLOR_TEXT_SECONDARY = Color.rgb(201, 211, 207);
    static final int COLOR_TEXT_MUTED = Color.rgb(155, 168, 164);
    static final int COLOR_TEXT_INVERSE = Color.WHITE;
    static final int COLOR_TEXT_DISABLED = Color.rgb(112, 123, 120);
    static final int COLOR_PRIMARY = Color.rgb(26, 128, 91);
    static final int COLOR_PRIMARY_DEEP = Color.rgb(20, 105, 74);
    static final int COLOR_PRIMARY_PRESSED = Color.rgb(14, 79, 55);
    static final int COLOR_PRIMARY_SOFT = Color.rgb(29, 58, 48);
    static final int COLOR_PRIMARY_BRIGHT = Color.rgb(91, 213, 166);
    static final int COLOR_DANGER = Color.rgb(255, 132, 120);
    static final int COLOR_DANGER_ACTION = Color.rgb(190, 61, 53);
    static final int COLOR_DANGER_DARK = Color.rgb(154, 46, 40);
    static final int COLOR_DANGER_SOFT = Color.rgb(66, 36, 35);
    static final int COLOR_WARNING = Color.rgb(245, 190, 90);
    static final int COLOR_WARNING_SOFT = Color.rgb(64, 52, 30);
    static final int COLOR_READY = COLOR_PRIMARY_BRIGHT;
    static final int COLOR_READY_SOFT = COLOR_PRIMARY_SOFT;
    static final int COLOR_OUTLINE = Color.rgb(65, 78, 75);
    static final int COLOR_OUTLINE_STRONG = Color.rgb(99, 120, 114);
    static final int COLOR_DISABLED_SURFACE = Color.rgb(49, 57, 55);

    static final int STATUS_READY = 1;
    static final int STATUS_REQUIRED = 2;
    static final int STATUS_WARNING = 3;
    static final int STATUS_NEUTRAL = 4;

    private UiStyle() {
    }

    static void applyWindow(Activity activity) {
        activity.getWindow().setStatusBarColor(COLOR_BACKGROUND);
        activity.getWindow().setNavigationBarColor(COLOR_BACKGROUND);
        applyDarkSystemBarAppearance(activity.getWindow().getDecorView());
    }

    static void applyDarkSystemBarAppearance(View view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = view.getWindowInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(
                        0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                );
            }
            return;
        }
        int flags = view.getSystemUiVisibility();
        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        view.setSystemUiVisibility(flags);
    }

    static ScrollView screenScroll(Context context) {
        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(true);
        scrollView.setBackgroundColor(COLOR_BACKGROUND);
        return scrollView;
    }

    static LinearLayout screenRoot(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BACKGROUND);
        return root;
    }

    static void attachScreenContent(ScrollView scrollView, LinearLayout content) {
        attachConstrainedContent(
                scrollView,
                content,
                SCREEN_CONTENT_MAX_WIDTH_DP,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    static void attachOverlayContent(ScrollView scrollView, LinearLayout content) {
        attachConstrainedContent(
                scrollView,
                content,
                OVERLAY_CONTENT_MAX_WIDTH_DP,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
    }

    static View constrainedScreen(Context context, View content) {
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(COLOR_BACKGROUND);
        ConstrainedContentHost host = new ConstrainedContentHost(
                context,
                SCREEN_CONTENT_MAX_WIDTH_DP
        );
        host.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        host.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        root.addView(host, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        applySystemInsetsPadding(root, 0, 0, 0, 0);
        return root;
    }

    static void applyScreenInsetsPadding(
            ScrollView scrollView,
            View content,
            int leftDp,
            int topDp,
            int rightDp,
            int bottomDp
    ) {
        content.setPadding(
                dp(content.getContext(), leftDp),
                dp(content.getContext(), topDp),
                dp(content.getContext(), rightDp),
                dp(content.getContext(), bottomDp)
        );
        applySystemInsetsPadding(scrollView, 0, 0, 0, 0);
    }

    static View overlayWindowRoot(Context context, ScrollView content) {
        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(COLOR_OVERLAY_BACKGROUND);
        frame.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        View topScrim = systemBarScrim(context);
        View bottomScrim = systemBarScrim(context);
        View leftScrim = systemBarScrim(context);
        View rightScrim = systemBarScrim(context);
        FrameLayout.LayoutParams topParams = edgeScrimParams(Gravity.TOP);
        FrameLayout.LayoutParams bottomParams = edgeScrimParams(Gravity.BOTTOM);
        FrameLayout.LayoutParams leftParams = sideScrimParams(Gravity.START);
        FrameLayout.LayoutParams rightParams = sideScrimParams(Gravity.END);
        frame.addView(topScrim, topParams);
        frame.addView(bottomScrim, bottomParams);
        frame.addView(leftScrim, leftParams);
        frame.addView(rightScrim, rightParams);

        frame.setOnApplyWindowInsetsListener((target, insets) -> {
            int left = insets.getSystemWindowInsetLeft();
            int top = insets.getSystemWindowInsetTop();
            int right = insets.getSystemWindowInsetRight();
            int bottom = insets.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) {
                    left = Math.max(left, cutout.getSafeInsetLeft());
                    top = Math.max(top, cutout.getSafeInsetTop());
                    right = Math.max(right, cutout.getSafeInsetRight());
                    bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                }
            }
            topParams.height = top;
            bottomParams.height = bottom;
            leftParams.width = left;
            rightParams.width = right;
            topScrim.setLayoutParams(topParams);
            bottomScrim.setLayoutParams(bottomParams);
            leftScrim.setLayoutParams(leftParams);
            rightScrim.setLayoutParams(rightParams);
            return insets;
        });
        requestInsetsWhenReady(frame);
        return frame;
    }

    static void applySystemInsetsPadding(View view, int leftDp, int topDp, int rightDp, int bottomDp) {
        final int baseLeft = dp(view.getContext(), leftDp);
        final int baseTop = dp(view.getContext(), topDp);
        final int baseRight = dp(view.getContext(), rightDp);
        final int baseBottom = dp(view.getContext(), bottomDp);
        view.setPadding(baseLeft, baseTop, baseRight, baseBottom);
        view.setOnApplyWindowInsetsListener((target, insets) -> {
            int left = insets.getSystemWindowInsetLeft();
            int top = insets.getSystemWindowInsetTop();
            int right = insets.getSystemWindowInsetRight();
            int bottom = insets.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) {
                    left = Math.max(left, cutout.getSafeInsetLeft());
                    top = Math.max(top, cutout.getSafeInsetTop());
                    right = Math.max(right, cutout.getSafeInsetRight());
                    bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                }
            }
            target.setPadding(
                    baseLeft + left,
                    baseTop + top,
                    baseRight + right,
                    baseBottom + bottom
            );
            return insets;
        });
        requestInsetsWhenReady(view);
    }

    static TextView screenTitle(Context context, String text) {
        TextView textView = new TextView(context);
        textView.setText(text);
        textView.setTextSize(30);
        textView.setTextColor(COLOR_TEXT_PRIMARY);
        textView.setTypeface(Typeface.DEFAULT_BOLD);
        textView.setIncludeFontPadding(true);
        return textView;
    }

    static TextView sectionTitle(Context context, String text) {
        TextView textView = new TextView(context);
        textView.setText(text);
        textView.setTextSize(17);
        textView.setTextColor(COLOR_TEXT_PRIMARY);
        textView.setTypeface(Typeface.DEFAULT_BOLD);
        return textView;
    }

    static TextView bodyText(Context context, String text) {
        TextView textView = new TextView(context);
        textView.setText(text);
        textView.setTextSize(15);
        textView.setTextColor(COLOR_TEXT_SECONDARY);
        textView.setLineSpacing(dp(context, 2), 1f);
        return textView;
    }

    static TextView helperText(Context context, String text) {
        TextView textView = bodyText(context, text);
        textView.setTextSize(14);
        textView.setTextColor(COLOR_TEXT_MUTED);
        return textView;
    }

    static TextView fieldLabel(Context context, String text) {
        TextView textView = new TextView(context);
        textView.setText(text);
        textView.setTextSize(14);
        textView.setTextColor(COLOR_TEXT_PRIMARY);
        textView.setTypeface(Typeface.DEFAULT_BOLD);
        return textView;
    }

    static TextView statusText(Context context) {
        TextView textView = new TextView(context);
        textView.setTextSize(14);
        textView.setLineSpacing(dp(context, 2), 1f);
        textView.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        return textView;
    }

    static void setStatus(TextView textView, int status) {
        int textColor = COLOR_TEXT_SECONDARY;
        int background = COLOR_SURFACE_ALT;
        int outline = COLOR_OUTLINE;
        if (status == STATUS_READY) {
            textColor = COLOR_READY;
            background = COLOR_READY_SOFT;
            outline = COLOR_READY;
        } else if (status == STATUS_REQUIRED) {
            textColor = COLOR_DANGER;
            background = COLOR_DANGER_SOFT;
            outline = COLOR_DANGER;
        } else if (status == STATUS_WARNING) {
            textColor = COLOR_WARNING;
            background = COLOR_WARNING_SOFT;
            outline = COLOR_WARNING;
        }
        textView.setTextColor(textColor);
        textView.setBackground(rounded(textView.getContext(), background, 8, outline, 1));
    }

    static TextView badge(Context context, String text, int status) {
        TextView textView = new TextView(context);
        textView.setText(text);
        textView.setTextSize(12);
        textView.setTypeface(Typeface.DEFAULT_BOLD);
        textView.setGravity(Gravity.CENTER);
        textView.setPadding(dp(context, 10), dp(context, 5), dp(context, 10), dp(context, 5));
        setStatus(textView, status);
        return textView;
    }

    static LinearLayout card(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16));
        card.setBackground(rounded(context, COLOR_SURFACE, 8, COLOR_OUTLINE, 1));
        card.setElevation(dp(context, 1));
        return card;
    }

    static LinearLayout overlayCard(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 18), dp(context, 18), dp(context, 18), dp(context, 18));
        card.setBackground(rounded(context, COLOR_OVERLAY_SURFACE, 8, COLOR_OUTLINE, 1));
        return card;
    }

    static TextView overlayTitle(Context context, String text) {
        TextView textView = new TextView(context);
        textView.setText(text);
        textView.setTextSize(28);
        textView.setTextColor(COLOR_TEXT_INVERSE);
        textView.setTypeface(Typeface.DEFAULT_BOLD);
        textView.setGravity(Gravity.CENTER);
        return textView;
    }

    static TextView overlayBody(Context context, String text) {
        TextView textView = new TextView(context);
        textView.setText(text);
        textView.setTextSize(16);
        textView.setTextColor(COLOR_TEXT_SECONDARY);
        textView.setLineSpacing(dp(context, 2), 1f);
        return textView;
    }

    static TextView overlayStepLabel(Context context, String text) {
        TextView textView = new TextView(context);
        textView.setText(text);
        textView.setTextSize(14);
        textView.setTextColor(COLOR_TEXT_INVERSE);
        textView.setTypeface(Typeface.DEFAULT_BOLD);
        return textView;
    }

    static Button primaryButton(Context context, String text) {
        Button button = baseButton(context, text);
        button.setTextColor(buttonTextColors(COLOR_TEXT_INVERSE, COLOR_TEXT_DISABLED));
        button.setBackground(buttonBackground(
                context,
                COLOR_PRIMARY,
                COLOR_PRIMARY_DEEP,
                COLOR_DISABLED_SURFACE,
                0
        ));
        return button;
    }

    static Button secondaryButton(Context context, String text) {
        Button button = baseButton(context, text);
        button.setTextColor(buttonTextColors(COLOR_TEXT_INVERSE, COLOR_TEXT_DISABLED));
        button.setBackground(buttonBackground(
                context,
                COLOR_PRIMARY_DEEP,
                COLOR_PRIMARY_PRESSED,
                COLOR_DISABLED_SURFACE,
                0
        ));
        return button;
    }

    static Button dangerButton(Context context, String text) {
        Button button = baseButton(context, text);
        button.setTextColor(buttonTextColors(COLOR_TEXT_INVERSE, COLOR_TEXT_DISABLED));
        button.setBackground(buttonBackground(
                context,
                COLOR_DANGER_ACTION,
                COLOR_DANGER_DARK,
                COLOR_DISABLED_SURFACE,
                0
        ));
        return button;
    }

    static Button quietButton(Context context, String text) {
        Button button = baseButton(context, text);
        button.setTextColor(buttonTextColors(COLOR_TEXT_SECONDARY, COLOR_TEXT_DISABLED));
        button.setBackground(buttonBackground(context, COLOR_SURFACE_ALT, COLOR_DISABLED_SURFACE, COLOR_DISABLED_SURFACE, COLOR_OUTLINE));
        return button;
    }

    static Button overlaySecondaryButton(Context context, String text) {
        Button button = baseButton(context, text);
        button.setTextColor(buttonTextColors(COLOR_TEXT_INVERSE, COLOR_TEXT_DISABLED));
        button.setBackground(buttonBackground(
                context,
                COLOR_PRIMARY_DEEP,
                COLOR_PRIMARY_PRESSED,
                COLOR_DISABLED_SURFACE,
                0
        ));
        return button;
    }

    static EditText inputField(Context context, String hint) {
        EditText input = new EditText(context);
        input.setHint(hint);
        styleInput(input, false);
        return input;
    }

    static void styleInput(EditText input, boolean error) {
        Context context = input.getContext();
        input.setTextSize(16);
        input.setTextColor(COLOR_TEXT_PRIMARY);
        input.setHintTextColor(COLOR_TEXT_MUTED);
        input.setMinHeight(dp(context, 52));
        input.setPadding(dp(context, 12), 0, dp(context, 12), 0);
        input.setBackground(rounded(
                context,
                COLOR_SURFACE,
                8,
                error ? COLOR_DANGER : COLOR_OUTLINE_STRONG,
                error ? 2 : 1
        ));
    }

    static void styleOverlayInput(EditText input, boolean error) {
        Context context = input.getContext();
        input.setTextSize(18);
        input.setTextColor(COLOR_TEXT_INVERSE);
        input.setHintTextColor(Color.rgb(183, 194, 199));
        input.setMinHeight(dp(context, 54));
        input.setPadding(dp(context, 12), 0, dp(context, 12), 0);
        input.setBackground(rounded(
                context,
                COLOR_SURFACE_ALT,
                8,
                error ? COLOR_DANGER : COLOR_OUTLINE_STRONG,
                error ? 2 : 1
        ));
    }

    static void styleSelectableRow(LinearLayout row, boolean selected) {
        Context context = row.getContext();
        row.setBackground(rounded(
                context,
                selected ? COLOR_PRIMARY_SOFT : COLOR_SURFACE,
                8,
                selected ? COLOR_READY : COLOR_OUTLINE,
                selected ? 2 : 1
        ));
    }

    static LinearLayout usageRow(Context context, boolean overLimit) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
        row.setBackgroundColor(overLimit ? COLOR_DANGER_SOFT : COLOR_SURFACE_ALT);
        return row;
    }

    static LinearLayout.LayoutParams fullWidth(Context context) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(context, 12));
        return params;
    }

    static LinearLayout.LayoutParams fullWidth(Context context, int bottomMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(context, bottomMarginDp));
        return params;
    }

    static LinearLayout.LayoutParams buttonParams(Context context) {
        LinearLayout.LayoutParams params = fullWidth(context, 10);
        params.setMargins(0, dp(context, 6), 0, dp(context, 10));
        return params;
    }

    static LinearLayout.LayoutParams gooseBannerParams(Context context) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(context, 4), 0, dp(context, 18));
        return params;
    }

    static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static Button baseButton(Context context, String text) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setMinHeight(dp(context, 52));
        button.setMinWidth(dp(context, 48));
        button.setPadding(dp(context, 16), 0, dp(context, 16), 0);
        button.setGravity(Gravity.CENTER);
        button.setStateListAnimator(null);
        button.setElevation(0);
        return button;
    }

    private static View systemBarScrim(Context context) {
        View view = new View(context);
        view.setBackgroundColor(COLOR_SYSTEM_BAR_CONTRAST);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private static FrameLayout.LayoutParams edgeScrimParams(int gravity) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0
        );
        params.gravity = gravity;
        return params;
    }

    private static FrameLayout.LayoutParams sideScrimParams(int gravity) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        params.gravity = gravity;
        return params;
    }

    private static ColorStateList buttonTextColors(int enabledColor, int disabledColor) {
        return new ColorStateList(
                new int[][]{
                        new int[]{-android.R.attr.state_enabled},
                        new int[]{}
                },
                new int[]{disabledColor, enabledColor}
        );
    }

    private static StateListDrawable buttonBackground(
            Context context,
            int enabledColor,
            int pressedColor,
            int disabledColor,
            int outlineColor
    ) {
        StateListDrawable states = new StateListDrawable();
        states.addState(
                new int[]{-android.R.attr.state_enabled},
                rounded(context, disabledColor, 8, outlineColor == 0 ? disabledColor : COLOR_OUTLINE, 1)
        );
        states.addState(
                new int[]{android.R.attr.state_pressed},
                rounded(context, pressedColor, 8, outlineColor == 0 ? pressedColor : outlineColor, 1)
        );
        states.addState(
                new int[]{},
                rounded(context, enabledColor, 8, outlineColor == 0 ? enabledColor : outlineColor, 1)
        );
        return states;
    }

    private static GradientDrawable rounded(
            Context context,
            int color,
            int radiusDp,
            int strokeColor,
            int strokeDp
    ) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        if (strokeDp > 0) {
            drawable.setStroke(dp(context, strokeDp), strokeColor);
        }
        return drawable;
    }

    private static void requestInsetsWhenReady(View view) {
        if (view.isAttachedToWindow()) {
            view.requestApplyInsets();
            return;
        }
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View attachedView) {
                attachedView.removeOnAttachStateChangeListener(this);
                attachedView.requestApplyInsets();
            }

            @Override
            public void onViewDetachedFromWindow(View detachedView) {
            }
        });
    }

    private static void attachConstrainedContent(
            ScrollView scrollView,
            LinearLayout content,
            int maxWidthDp,
            int height
    ) {
        ConstrainedContentHost host = new ConstrainedContentHost(
                scrollView.getContext(),
                maxWidthDp
        );
        host.setBackgroundColor(content.getSolidColor());
        host.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        host.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL
        ));
        scrollView.addView(host, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height
        ));
    }

    private static final class ConstrainedContentHost extends FrameLayout {
        private final int maxContentWidthPx;

        ConstrainedContentHost(Context context, int maxContentWidthDp) {
            super(context);
            maxContentWidthPx = dp(context, maxContentWidthDp);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int availableWidth = MeasureSpec.getSize(widthMeasureSpec);
            int horizontalInset = Math.max(0, availableWidth - maxContentWidthPx);
            int left = horizontalInset / 2;
            int right = horizontalInset - left;
            if (getPaddingLeft() != left || getPaddingRight() != right) {
                setPadding(left, 0, right, 0);
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }
}
