package org.lastidea.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.widget.FrameLayout;

final class PageFrameLayout extends FrameLayout {
    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF band = new RectF();
    private int backgroundBottomColor = 0xFF030303;
    private int backgroundMiddleColor = 0xFF101010;
    private int backgroundTopColor = 0xFF060606;
    private int bandGlowColor = 0xFFECECEC;
    private int labelColor = 0xA8FFFFFF;

    PageFrameLayout(Context context) {
        super(context);
        init();
    }

    PageFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setBackgroundColor(0xFF070707);
        setPadding(dp(24), dp(64), dp(24), dp(18));

        background.setColor(0xFF070707);
        background.setStyle(Paint.Style.FILL);

        label.setColor(labelColor);
        label.setTextSize(dp(10));
        label.setTypeface(Typeface.SERIF);

        plate.setStyle(Paint.Style.FILL);
    }

    void setFramePalette(
            int topColor,
            int middleColor,
            int bottomColor,
            int titleColor,
            int glowColor) {
        backgroundTopColor = topColor;
        backgroundMiddleColor = middleColor;
        backgroundBottomColor = bottomColor;
        labelColor = titleColor;
        bandGlowColor = glowColor;
        label.setColor(labelColor);
        setBackgroundColor(bottomColor);
        invalidate();
    }

    void setTitleTypeface(Typeface typeface) {
        label.setTypeface(typeface == null ? Typeface.SERIF : typeface);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        background.setShader(new LinearGradient(
                0,
                0,
                width,
                height,
                new int[]{backgroundTopColor, backgroundMiddleColor, backgroundBottomColor},
                new float[]{0f, 0.48f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, background);
        background.setShader(null);

        band.set(dp(24), dp(18), width - dp(24), dp(42));
        plate.setShader(new LinearGradient(
                band.left,
                band.top,
                band.right,
                band.bottom,
                new int[]{
                        withAlpha(backgroundMiddleColor, 0),
                        withAlpha(bandGlowColor, 42),
                        withAlpha(bandGlowColor, 82),
                        withAlpha(backgroundMiddleColor, 0)
                },
                new float[]{0f, 0.34f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(band, dp(12), dp(12), plate);
        plate.setShader(null);

        canvas.drawText("Last Idea", dp(32), dp(34), label);
    }

    private int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
