package com.dankhole.airlockandroid;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

final class GooseCelebrationView extends View {
    private static final long DURATION_MS = 1800L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String honkText;
    private float progress;
    private ValueAnimator animator;
    private boolean animationCancelled;

    GooseCelebrationView(Context context) {
        super(context);
        honkText = context.getString(R.string.celebration_honk);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void start(Runnable onFinished) {
        if (animator != null) {
            animator.cancel();
        }
        animationCancelled = false;
        if (!ValueAnimator.areAnimatorsEnabled()) {
            progress = 1f;
            invalidate();
            if (onFinished != null) {
                post(onFinished);
            }
            return;
        }
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(DURATION_MS);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            progress = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                animationCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                animator = null;
                if (!animationCancelled && onFinished != null) {
                    postDelayed(onFinished, 250L);
                }
            }
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        float bounce = (float) Math.sin(progress * Math.PI * 4f) * dp(8);
        float waddle = (progress - 0.5f) * dp(34);
        float centerX = width / 2f + waddle;
        float centerY = height * 0.58f - bounce;
        float flap = (float) Math.sin(progress * Math.PI * 6f);

        drawConfetti(canvas, width, height);
        drawHonk(canvas, centerX, centerY, flap);
        drawGoose(canvas, centerX, centerY, flap);
    }

    private void drawGoose(Canvas canvas, float centerX, float centerY, float flap) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(75, 0, 0, 0));
        canvas.drawOval(new RectF(
                centerX - dp(52),
                centerY + dp(30),
                centerX + dp(54),
                centerY + dp(44)
        ), paint);

        paint.setColor(Color.rgb(248, 249, 241));
        canvas.drawOval(new RectF(
                centerX - dp(48),
                centerY - dp(22),
                centerX + dp(44),
                centerY + dp(32)
        ), paint);

        paint.setColor(Color.rgb(235, 238, 229));
        Path wing = new Path();
        wing.moveTo(centerX - dp(8), centerY - dp(8));
        wing.quadTo(centerX + dp(18), centerY + dp(4) + flap * dp(12), centerX + dp(8), centerY + dp(24));
        wing.quadTo(centerX - dp(16), centerY + dp(14), centerX - dp(8), centerY - dp(8));
        canvas.drawPath(wing, paint);

        paint.setColor(Color.rgb(248, 249, 241));
        canvas.drawRoundRect(new RectF(
                centerX + dp(18),
                centerY - dp(56),
                centerX + dp(38),
                centerY - dp(10)
        ), dp(10), dp(10), paint);
        canvas.drawCircle(centerX + dp(42), centerY - dp(58), dp(18), paint);

        paint.setColor(Color.rgb(244, 160, 54));
        Path beak = new Path();
        beak.moveTo(centerX + dp(58), centerY - dp(60));
        beak.lineTo(centerX + dp(82), centerY - dp(52));
        beak.lineTo(centerX + dp(58), centerY - dp(44));
        beak.close();
        canvas.drawPath(beak, paint);

        paint.setColor(Color.rgb(12, 16, 20));
        canvas.drawCircle(centerX + dp(47), centerY - dp(64), dp(3), paint);

        paint.setColor(Color.rgb(244, 160, 54));
        paint.setStrokeWidth(dp(3));
        canvas.drawLine(centerX - dp(14), centerY + dp(30), centerX - dp(20), centerY + dp(44), paint);
        canvas.drawLine(centerX + dp(16), centerY + dp(30), centerX + dp(24), centerY + dp(44), paint);
        canvas.drawLine(centerX - dp(26), centerY + dp(44), centerX - dp(11), centerY + dp(44), paint);
        canvas.drawLine(centerX + dp(18), centerY + dp(44), centerX + dp(34), centerY + dp(44), paint);
    }

    private void drawHonk(Canvas canvas, float centerX, float centerY, float flap) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dp(24));
        int alpha = 150 + (int) (Math.abs(flap) * 105);
        paint.setColor(Color.argb(alpha, 245, 190, 90));
        canvas.drawText(honkText, centerX, centerY - dp(84), paint);
    }

    private void drawConfetti(Canvas canvas, float width, float height) {
        int[] colors = {
                UiStyle.COLOR_PRIMARY,
                UiStyle.COLOR_WARNING,
                UiStyle.COLOR_READY,
                Color.rgb(112, 199, 231)
        };
        for (int i = 0; i < 12; i++) {
            float phase = (progress + i * 0.083f) % 1f;
            float x = ((i * 47) % 100) / 100f * width;
            float y = height * 0.18f + phase * height * 0.62f;
            paint.setColor(colors[i % colors.length]);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(x, y, dp(i % 2 == 0 ? 3 : 2), paint);
        }
    }

    private float dp(int value) {
        return UiStyle.dp(getContext(), value);
    }
}
