package com.cvakigod.pinggost.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * A single vertical bar with a label, used to represent Alpha / Beta / Gamma readings.
 * Value range is always 0-100. Color shifts green -> yellow -> red as value rises,
 * matching the "danger" feel of the ghost-hunter EMF-meter aesthetic.
 */
public class BarGraphView extends View {

    private float value = 0f;          // current 0-100 value (animated toward target)
    private float targetValue = 0f;
    private String label = "A";
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();

    public BarGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        bgPaint.setColor(Color.parseColor("#1A1A1A"));
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(36f);
        textPaint.setFakeBoldText(true);
    }

    public void setLabel(String l) {
        this.label = l;
        invalidate();
    }

    /** Call this repeatedly (e.g. every 300-500ms) with the latest sensor-derived reading. */
    public void setValue(float v) {
        targetValue = Math.max(0f, Math.min(100f, v));
    }

    private int colorForValue(float v) {
        if (v < 50f) return Color.parseColor("#00E676");      // green - calm
        if (v < 75f) return Color.parseColor("#FFEA00");      // yellow - active
        return Color.parseColor("#FF1744");                   // red - anomaly range
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // ease toward target so the bar feels alive instead of jumpy
        value += (targetValue - value) * 0.15f;

        int w = getWidth();
        int h = getHeight();
        float barWidth = w * 0.5f;
        float left = (w - barWidth) / 2f;

        // track background
        barRect.set(left, 0, left + barWidth, h - 60f);
        canvas.drawRoundRect(barRect, 16f, 16f, bgPaint);

        // filled portion
        float fillHeight = (h - 60f) * (value / 100f);
        RectF fillRect = new RectF(left, (h - 60f) - fillHeight, left + barWidth, h - 60f);
        barPaint.setColor(colorForValue(value));
        canvas.drawRoundRect(fillRect, 16f, 16f, barPaint);

        // label + numeric readout
        canvas.drawText(label, w / 2f, h - 20f, textPaint);
        canvas.drawText(String.valueOf((int) value), w / 2f, (h - 60f) - fillHeight - 12f, textPaint);

        invalidate(); // keep the easing animation running
    }
}
