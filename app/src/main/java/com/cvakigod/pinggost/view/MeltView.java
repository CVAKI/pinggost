package com.cvakigod.pinggost.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * Renders text into an offscreen bitmap, then redraws it column-by-column with an
 * increasing downward vertical offset (+ a bit of horizontal jitter on the "corrupted"
 * variant) to fake a melting/dripping effect. Call startMelt() to begin, and set
 * an onMeltFinished listener to know when to move on to MainActivity.
 */
public class MeltView extends View {

    public interface OnMeltFinished {
        void onFinished();
    }

    private Bitmap sourceBitmap;
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();
    private float progress = 0f;       // 0 -> 1
    private boolean melting = false;
    private boolean corrupted = false; // set true after an anomaly session for an extra-creepy melt
    private OnMeltFinished listener;
    private final Random rng = new Random();
    private long startTime = 0L;
    private static final long DURATION_MS = 3200L;

    public MeltView(Context context, AttributeSet attrs) {
        super(context, attrs);
        bgPaint.setColor(Color.BLACK);
        textPaint.setColor(Color.parseColor("#00FF41"));
        textPaint.setTextSize(90f);
        textPaint.setFakeBoldText(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setCorrupted(boolean corrupted) {
        this.corrupted = corrupted;
        textPaint.setColor(corrupted ? Color.parseColor("#FF1744") : Color.parseColor("#00FF41"));
    }

    public void setOnMeltFinished(OnMeltFinished l) {
        this.listener = l;
    }

    public void startMelt(String title) {
        post(() -> {
            buildSourceBitmap(title);
            melting = true;
            startTime = System.currentTimeMillis();
            invalidate();
        });
    }

    private void buildSourceBitmap(String title) {
        int w = Math.max(getWidth(), 600);
        int h = Math.max(getHeight(), 400);
        sourceBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(sourceBitmap);
        Rect bounds = new Rect();
        textPaint.getTextBounds(title, 0, title.length(), bounds);
        c.drawText(title, w / 2f, h / 2f + bounds.height() / 2f, textPaint);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), getHeight(), bgPaint);

        if (!melting || sourceBitmap == null) return;

        long elapsed = System.currentTimeMillis() - startTime;
        progress = Math.min(1f, elapsed / (float) (corrupted ? DURATION_MS * 1.6f : DURATION_MS));

        int colWidth = 3;
        int w = sourceBitmap.getWidth();
        int h = sourceBitmap.getHeight();

        for (int x = 0; x < w; x += colWidth) {
            // each column has its own "drip speed" so the melt looks organic, not uniform
            float seed = (x * 12.9898f) % 1f;
            float dripSpeed = 0.4f + seed * 1.6f;
            float dropOffset = progress * h * 1.3f * dripSpeed;

            float jitterX = corrupted ? (rng.nextFloat() - 0.5f) * 14f * progress : 0f;

            Rect src = new Rect(x, 0, Math.min(x + colWidth, w), h);
            Rect dst = new Rect(
                    (int) (x + jitterX), (int) dropOffset,
                    (int) (x + colWidth + jitterX), (int) (dropOffset + h));
            canvas.drawBitmap(sourceBitmap, src, dst, null);
        }

        if (progress >= 1f) {
            melting = false;
            if (listener != null) listener.onFinished();
            return;
        }
        invalidate();
    }
}
