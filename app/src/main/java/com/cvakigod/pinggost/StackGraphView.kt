package com.cvakigod.pinggost.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

/**
 * Scrolling stacked-area chart showing Alpha + Beta + Gamma combined over time.
 * Feed real data with updateValues(alpha, beta, gamma), each 0f..1f.
 * Self-animates with demo data until real values are pushed.
 */
class StackGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private data class Sample(val a: Float, val b: Float, val g: Float)

    private val samples = mutableListOf<Sample>()
    private val maxSamples = 46

    private var autoDemo = true
    private val handler = Handler(Looper.getMainLooper())
    private val demoRunnable = object : Runnable {
        override fun run() {
            if (autoDemo) {
                ingest(
                    0.25f + Random.nextFloat() * 0.4f,
                    0.15f + Random.nextFloat() * 0.35f,
                    0.10f + Random.nextFloat() * 0.5f
                )
                handler.postDelayed(this, 500)
            }
        }
    }

    private val colorAlpha = Color.parseColor("#00E5FF")
    private val colorBeta = Color.parseColor("#B388FF")
    private val colorGamma = Color.parseColor("#FFD54F")

    private val bgPaint = Paint().apply { color = Color.parseColor("#0D0D0D") }
    private val gridPaint = Paint().apply { color = Color.parseColor("#182818"); strokeWidth = 1f }
    private val borderPaint = Paint().apply {
        color = Color.parseColor("#224422"); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val titlePaint = Paint().apply {
        color = Color.parseColor("#00FF41"); textSize = 22f; isFakeBoldText = true
        typeface = Typeface.MONOSPACE
    }
    private val axisPaint = Paint().apply {
        color = Color.parseColor("#557755"); textSize = 18f
        typeface = Typeface.MONOSPACE; textAlign = Paint.Align.RIGHT
    }

    private fun fillPaint(color: Int) = Paint().apply {
        this.color = color; alpha = 140; style = Paint.Style.FILL
    }
    private fun linePaint(color: Int) = Paint().apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = 2f
    }

    private val fillGamma = fillPaint(colorGamma)
    private val fillBeta = fillPaint(colorBeta)
    private val fillAlpha = fillPaint(colorAlpha)
    private val lineGamma = linePaint(colorGamma)
    private val lineBeta = linePaint(colorBeta)
    private val lineAlpha = linePaint(colorAlpha)

    private fun ingest(alpha: Float, beta: Float, gamma: Float) {
        samples.add(Sample(alpha.coerceIn(0f, 1f), beta.coerceIn(0f, 1f), gamma.coerceIn(0f, 1f)))
        if (samples.size > maxSamples) samples.removeAt(0)
        invalidate()
    }

    /** Feed live normalized readings (0f..1f each). Stops demo animation automatically. */
    fun updateValues(alpha: Float, beta: Float, gamma: Float) {
        if (autoDemo) { autoDemo = false; handler.removeCallbacks(demoRunnable) }
        ingest(alpha, beta, gamma)
    }

    /** Same as updateValues but takes 0..100 percent values, matching your sensor pipeline's scale. */
    fun setValues100(alphaPct: Float, betaPct: Float, gammaPct: Float) {
        updateValues(alphaPct / 100f, betaPct / 100f, gammaPct / 100f)
    }

    fun setDemoMode(enabled: Boolean) {
        autoDemo = enabled
        handler.removeCallbacks(demoRunnable)
        if (enabled) handler.post(demoRunnable)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (samples.isEmpty() && autoDemo) repeat(10) {
            ingest(0.3f + Random.nextFloat() * 0.2f, 0.2f + Random.nextFloat() * 0.2f, 0.15f + Random.nextFloat() * 0.25f)
        }
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

        val chartTop = 30f
        val chartBottom = h - 6f
        val chartHeight = (chartBottom - chartTop).coerceAtLeast(1f)
        val chartLeft = 4f
        val chartRight = w - 40f

        canvas.drawText("SIGNAL HISTORY // STACKED", 6f, 20f, titlePaint)

        for (i in 0..2) {
            val y = chartTop + chartHeight * i / 2f
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint)
            val pct = 100 - (i * 50)
            canvas.drawText("$pct", chartRight + 36f, y + 6f, axisPaint)
        }

        if (samples.size < 2) {
            canvas.drawRect(1f, 1f, w - 1f, h - 1f, borderPaint)
            return
        }

        val maxTotal = (samples.maxOf { it.a + it.b + it.g }).coerceAtLeast(0.3f) * 1.15f
        val stepX = (chartRight - chartLeft) / (maxSamples - 1)
        val startIndex = maxSamples - samples.size

        fun buildLayer(valueAt: (Sample) -> Float, baseAt: (Sample) -> Float): Path {
            val path = Path()
            samples.forEachIndexed { i, s ->
                val x = chartLeft + stepX * (startIndex + i)
                val yTop = chartBottom - ((baseAt(s) + valueAt(s)) / maxTotal) * chartHeight
                if (i == 0) path.moveTo(x, yTop) else path.lineTo(x, yTop)
            }
            for (i in samples.indices.reversed()) {
                val s = samples[i]
                val x = chartLeft + stepX * (startIndex + i)
                val yBase = chartBottom - (baseAt(s) / maxTotal) * chartHeight
                path.lineTo(x, yBase)
            }
            path.close()
            return path
        }

        // stack order bottom -> top: gamma, beta, alpha
        val gammaPath = buildLayer({ it.g }, { 0f })
        canvas.drawPath(gammaPath, fillGamma)
        canvas.drawPath(gammaPath, lineGamma)

        val betaPath = buildLayer({ it.b }, { it.g })
        canvas.drawPath(betaPath, fillBeta)
        canvas.drawPath(betaPath, lineBeta)

        val alphaPath = buildLayer({ it.a }, { it.g + it.b })
        canvas.drawPath(alphaPath, fillAlpha)
        canvas.drawPath(alphaPath, lineAlpha)

        canvas.drawRect(1f, 1f, w - 1f, h - 1f, borderPaint)
    }
}