package com.dankhole.airlockandroid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

final class GooseMascotView extends View {
    private static final int GOOSE_TOP_EXTENT_DP = 63;
    private static final int GOOSE_BOTTOM_EXTENT_DP = 34;
    private static final int NARROW_WIDTH_DP = 360;
    private static final int WIDE_HEIGHT_DP = 112;
    private static final int NARROW_HEIGHT_DP = 128;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subtitlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final String title;
    private final String subtitle;

    GooseMascotView(Context context) {
        super(context);
        title = context.getString(R.string.mascot_title);
        subtitle = context.getString(R.string.mascot_subtitle);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int measuredWidth = resolveSize(UiStyle.dp(getContext(), 320), widthMeasureSpec);
        int desiredHeight = measuredWidth < dp(NARROW_WIDTH_DP)
                ? UiStyle.dp(getContext(), NARROW_HEIGHT_DP)
                : UiStyle.dp(getContext(), WIDE_HEIGHT_DP);
        setMeasuredDimension(measuredWidth, resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        drawPond(canvas, width, height);
        float gooseCenterY = Math.max(
                height * 0.65f,
                dp(GOOSE_TOP_EXTENT_DP) + dp(4)
        );
        gooseCenterY = Math.min(gooseCenterY, height - dp(GOOSE_BOTTOM_EXTENT_DP) - dp(4));
        boolean narrow = width < dp(NARROW_WIDTH_DP);
        float gooseCenterX = width * (narrow ? 0.22f : 0.24f);
        drawGoose(canvas, gooseCenterX, gooseCenterY);
        drawLabel(canvas, width, height, gooseCenterX, narrow);
    }

    private void drawPond(Canvas canvas, float width, float height) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(UiStyle.COLOR_PRIMARY_SOFT);
        canvas.drawRoundRect(
                new RectF(0, height * 0.12f, width, height * 0.94f),
                dp(18),
                dp(18),
                paint
        );

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(UiStyle.COLOR_OUTLINE);
        canvas.drawRoundRect(
                new RectF(dp(0), height * 0.12f, width - dp(1), height * 0.94f),
                dp(18),
                dp(18),
                paint
        );

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(UiStyle.COLOR_PRIMARY_BRIGHT);
        paint.setAlpha(90);
        canvas.drawOval(new RectF(width * 0.08f, height * 0.68f, width * 0.48f, height * 0.83f), paint);
        paint.setAlpha(255);
    }

    private void drawGoose(Canvas canvas, float centerX, float centerY) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(248, 249, 241));
        canvas.drawOval(new RectF(centerX - dp(34), centerY - dp(14), centerX + dp(35), centerY + dp(24)), paint);
        canvas.drawRoundRect(new RectF(centerX + dp(12), centerY - dp(46), centerX + dp(28), centerY - dp(6)),
                dp(9), dp(9), paint);
        canvas.drawCircle(centerX + dp(32), centerY - dp(48), dp(15), paint);

        paint.setColor(Color.rgb(232, 235, 226));
        Path wing = new Path();
        wing.moveTo(centerX - dp(8), centerY - dp(4));
        wing.quadTo(centerX + dp(10), centerY + dp(6), centerX, centerY + dp(19));
        wing.quadTo(centerX - dp(18), centerY + dp(12), centerX - dp(8), centerY - dp(4));
        canvas.drawPath(wing, paint);

        paint.setColor(Color.rgb(244, 160, 54));
        Path beak = new Path();
        beak.moveTo(centerX + dp(45), centerY - dp(50));
        beak.lineTo(centerX + dp(65), centerY - dp(43));
        beak.lineTo(centerX + dp(45), centerY - dp(36));
        beak.close();
        canvas.drawPath(beak, paint);

        paint.setColor(Color.rgb(12, 16, 20));
        canvas.drawCircle(centerX + dp(36), centerY - dp(53), dp(3), paint);

        paint.setColor(Color.rgb(244, 160, 54));
        paint.setStrokeWidth(dp(3));
        canvas.drawLine(centerX - dp(12), centerY + dp(22), centerX - dp(18), centerY + dp(34), paint);
        canvas.drawLine(centerX + dp(12), centerY + dp(22), centerX + dp(18), centerY + dp(34), paint);
    }

    private void drawLabel(
            Canvas canvas,
            float width,
            float height,
            float gooseCenterX,
            boolean narrow
    ) {
        float labelX = narrow
                ? Math.max(width * 0.45f, gooseCenterX + dp(70))
                : width * 0.46f;
        int labelWidth = Math.max(1, (int) (width - labelX - dp(12)));

        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(sp(narrow ? 18 : 20));
        paint.setColor(UiStyle.COLOR_TEXT_PRIMARY);
        canvas.drawText(title, labelX, height * (narrow ? 0.40f : 0.45f), paint);

        subtitlePaint.setTypeface(android.graphics.Typeface.DEFAULT);
        subtitlePaint.setTextSize(sp(narrow ? 12 : 13));
        subtitlePaint.setColor(UiStyle.COLOR_TEXT_SECONDARY);
        StaticLayout subtitleLayout = StaticLayout.Builder.obtain(
                        subtitle,
                        0,
                        subtitle.length(),
                        subtitlePaint,
                        labelWidth
                )
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0, 1.05f)
                .setMaxLines(narrow ? 2 : 1)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setEllipsizedWidth(labelWidth)
                .build();
        canvas.save();
        canvas.translate(labelX, height * (narrow ? 0.54f : 0.56f));
        subtitleLayout.draw(canvas);
        canvas.restore();
    }

    private float dp(int value) {
        return UiStyle.dp(getContext(), value);
    }

    private float sp(int value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}
