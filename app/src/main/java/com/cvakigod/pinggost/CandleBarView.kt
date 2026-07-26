package com.cvakigod.pinggost.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

/**
 * A compact candlestick-style strip chart for a single signal (Alpha / Beta / Gamma).
 * Each candle is built from consecutive readings: previous value = open, new value = close.
 * Feed real data with addReading(0f..1f). Until real data arrives it self-animates with
 * demo readings so the screen looks alive immediately.
 */
class CandleBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private data class Candle(val open: Float, val close: Float, val high: Float, val low: Float)

    private val candles = mutableListOf<Candle>()
    private val maxCandles = 12
    private var lastValue = 0.5f

    var label: String = "SIGNAL"
        set(value) { field = value; invalidate() }

    private var autoDemo = true
    private val handler = Handler(Looper.getMainLooper())
    private val demoRunnable = object : Runnable {
        override fun run() {
            if (autoDemo) {
                ingest(lastValue + (Random.nextFloat() - 0.5f) * 0.45f)
                handler.postDelayed(this, 650)
            }
        }
    }

    private val bgPaint = Paint().apply { color = Color.parseColor("#0D0D0D") }
    private val gridPaint = Paint().apply { color = Color.parseColor("#182818"); strokeWidth = 1f }
    private val borderPaint = Paint().apply {
        color = Color.parseColor("#224422"); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val upPaint = Paint().apply { color = Color.parseColor("#00FF41") }
    private val downPaint = Paint().apply { color = Color.parseColor("#FF1744") }
    private val wickPaint = Paint().apply { strokeWidth = 2.5f }
    private val labelPaint = Paint().apply {
        color = Color.parseColor("#00FF41"); textSize = 24f; isFakeBoldText = true
        typeface = Typeface.MONOSPACE; textAlign = Paint.Align.LEFT
    }
    private val valuePaint = Paint().apply {
        color = Color.parseColor("#88CC88"); textSize = 20f
        typeface = Typeface.MONOSPACE; textAlign = Paint.Align.RIGHT
    }

    private fun ingest(raw: Float) {
        val value = raw.coerceIn(0f, 1f)
        val open = lastValue
        val close = value
        val jitter = 0.03f
        val high = (maxOf(open, close) + jitter).coerceAtMost(1f)
        val low = (minOf(open, close) - jitter).coerceAtLeast(0f)
        candles.add(Candle(open, close, high, low))
        if (candles.size > maxCandles) candles.removeAt(0)
        lastValue = value
        invalidate()
    }

    /**
     * Feed a normalized reading (0f..1f). Call this from your sensor/RadarView listener.
     * Automatically stops demo self-animation the first time it's called.
     */
    fun addReading(raw: Float) {
        if (autoDemo) { autoDemo = false; handler.removeCallbacks(demoRunnable) }
        ingest(raw)
    }

    /**
     * Drop-in replacement for BarGraphView.setValue(float) — takes a 0..100 percent value.
     * Automatically stops demo self-animation the first time it's called.
     */
    fun setValue(percent: Float) {
        addReading(percent / 100f)
    }

    /** Turn off self-animation once you start feeding real readings via addReading()/setValue(). */
    fun setDemoMode(enabled: Boolean) {
        autoDemo = enabled
        handler.removeCallbacks(demoRunnable)
        if (enabled) handler.post(demoRunnable)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (candles.isEmpty() && autoDemo) repeat(5) { ingest(0.4f + Random.nextFloat() * 0.3f) }
        if (autoDemo) handler.post(demoRunnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(demoRunnable)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val chartTop = 38f
        val chartBottom = h - 8f
        val chartHeight = (chartBottom - chartTop).coerceAtLeast(1f)

        for (i in 0..3) {
            val y = chartTop + chartHeight * i / 3f
            canvas.drawLine(4f, y, w - 4f, y, gridPaint)
        }

        canvas.drawText(label, 6f, 24f, labelPaint)
        if (candles.isNotEmpty()) {
            val pct = (candles.last().close * 100).toInt()
            canvas.drawText("$pct%", w - 6f, 24f, valuePaint)
        }

        if (candles.isEmpty()) {
            canvas.drawRect(1f, 1f, w - 1f, h - 1f, borderPaint)
            return
        }

        val slot = (w - 8f) / maxCandles
        val bodyWidth = slot * 0.5f

        candles.forEachIndexed { i, c ->
            val cx = 4f + slot * i + slot / 2f
            val yOpen = chartBottom - c.open * chartHeight
            val yClose = chartBottom - c.close * chartHeight
            val yHigh = chartBottom - c.high * chartHeight
            val yLow = chartBottom - c.low * chartHeight
            val up = c.close >= c.open
            val paint = if (up) upPaint else downPaint
            wickPaint.color = paint.color
            canvas.drawLine(cx, yHigh, cx, yLow, wickPaint)
            val top = minOf(yOpen, yClose)
            var bottom = maxOf(yOpen, yClose)
            if (bottom - top < 3f) bottom = top + 3f
            canvas.drawRect(cx - bodyWidth / 2f, top, cx + bodyWidth / 2f, bottom, paint)
        }

        canvas.drawRect(1f, 1f, w - 1f, h - 1f, borderPaint)
    }
}