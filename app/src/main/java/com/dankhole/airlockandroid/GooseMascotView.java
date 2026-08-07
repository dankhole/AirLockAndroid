package com.dankhole.airlockandroid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

final class GooseMascotView extends View {
    private static final int GOOSE_TOP_EXTENT_DP = 63;
    private static final int GOOSE_BOTTOM_EXTENT_DP = 34;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    GooseMascotView(Context context) {
        super(context);
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
        drawGoose(canvas, width * 0.24f, gooseCenterY);
        drawLabel(canvas, width, height);
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
        paint.setColor(Color.argb(90, 91, 213, 145));
        canvas.drawOval(new RectF(width * 0.08f, height * 0.68f, width * 0.48f, height * 0.83f), paint);
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

    private void drawLabel(Canvas canvas, float width, float height) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(dp(20));
        paint.setColor(UiStyle.COLOR_TEXT_PRIMARY);
        canvas.drawText("Goose mode!", width * 0.46f, height * 0.45f, paint);

        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setTextSize(dp(13));
        paint.setColor(UiStyle.COLOR_TEXT_SECONDARY);
        canvas.drawText("Silly, stern, and watching the clock!", width * 0.46f, height * 0.68f, paint);
    }

    private float dp(int value) {
        return UiStyle.dp(getContext(), value);
    }
}
