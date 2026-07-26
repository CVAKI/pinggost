package com.cvakigod.pinggost.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * Classic radar-sweep visual. The sweep angle just rotates continuously for effect,
 * but the NUMBER and BRIGHTNESS of "blips" on the radar are driven by the live
 * alpha/beta/gamma readings passed in from MainActivity, so it's not purely decorative.
 */
public class RadarView extends View {

    private float sweepAngle = 0f;
    private float alpha = 0f, beta = 0f, gamma = 0f; // 0-100 each
    private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rng = new Random();

    public RadarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        circlePaint.setStyle(Paint.Style.STROKE);
        circlePaint.setColor(Color.parseColor("#1FFF3B30"));
        circlePaint.setColor(Color.parseColor("#2200FF41"));
        circlePaint.setStrokeWidth(2f);

        sweepPaint.setStyle(Paint.Style.STROKE);
        sweepPaint.setStrokeWidth(4f);
        sweepPaint.setColor(Color.parseColor("#8800FF41"));

        blipPaint.setStyle(Paint.Style.FILL);
    }

    /** feed the latest readings (0-100 each) in every update tick */
    public void updateReadings(float alpha, float beta, float gamma) {
        this.alpha = alpha;
        this.beta = beta;
        this.gamma = gamma;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float maxR = Math.min(w, h) / 2f - 8f;

        // concentric rings
        for (int i = 1; i <= 4; i++) {
            canvas.drawCircle(cx, cy, maxR * i / 4f, circlePaint);
        }
        // crosshairs
        canvas.drawLine(cx - maxR, cy, cx + maxR, cy, circlePaint);
        canvas.drawLine(cx, cy - maxR, cx, cy + maxR, circlePaint);

        // sweeping line
        sweepAngle = (sweepAngle + 3f) % 360f;
        double rad = Math.toRadians(sweepAngle);
        float ex = cx + (float) Math.cos(rad) * maxR;
        float ey = cy + (float) Math.sin(rad) * maxR;
        canvas.drawLine(cx, cy, ex, ey, sweepPaint);

        // blips: alpha controls how many, beta controls how far out, gamma controls flicker/glow
        int blipCount = 1 + (int) (alpha / 25f); // 1..5 blips
        for (int i = 0; i < blipCount; i++) {
            double angle = Math.toRadians(rng.nextInt(360));
            float dist = maxR * (0.3f + (beta / 100f) * 0.65f) * (0.7f + rng.nextFloat() * 0.3f);
            float bx = cx + (float) Math.cos(angle) * dist;
            float by = cy + (float) Math.sin(angle) * dist;

            int flickerAlpha = (int) (120 + (gamma / 100f) * 135 * rng.nextFloat());
            blipPaint.setColor(Color.argb(flickerAlpha, 255, 23, 68)); // red-ish blip
            canvas.drawCircle(bx, by, 6f + gamma / 20f, blipPaint);
        }

        invalidate(); // keep sweeping
    }
}
