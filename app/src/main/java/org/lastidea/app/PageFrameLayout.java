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

        label.setColor(0xA8FFFFFF);
        label.setTextSize(dp(10));
        label.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));

        plate.setStyle(Paint.Style.FILL);
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
                new int[]{0xFF060606, 0xFF101010, 0xFF030303},
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
                new int[]{0x00171717, 0x33545454, 0x66ECECEC, 0x00242424},
                new float[]{0f, 0.34f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(band, dp(12), dp(12), plate);
        plate.setShader(null);

        canvas.drawText("Last Idea", dp(32), dp(34), label);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
