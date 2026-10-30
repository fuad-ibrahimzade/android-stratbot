package com.stratbot.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/** lightweight custom equity chart (no chart library needed) */
class EquityCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private var data: List<DoubleArray> = emptyList()
    private var startEquity = 0.0

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#1E2534")
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * resources.displayMetrics.density
        color = Color.parseColor("#8C95AC")
    }

    fun setCurve(points: List<DoubleArray>, base: Double) {
        data = points
        startEquity = base
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        if (data.size < 2) {
            canvas.drawText("not enough data for equity curve", 12f, h / 2, label)
            return
        }

        var min = Double.MAX_VALUE
        var max = -Double.MAX_VALUE
        for (p in data) {
            val v = p[1]
            if (v.isNaN()) continue
            min = kotlin.math.min(min, v)
            max = kotlin.math.max(max, v)
        }
        if (startEquity > 0) {
            min = kotlin.math.min(min, startEquity)
            max = kotlin.math.max(max, startEquity)
        }
        if (max - min < 1e-9) {
            max = min + kotlin.math.abs(max) * 0.01 + 1.0
        }
        val pad = (max - min) * 0.06
        min -= pad
        max += pad

        // grid (4 rows)
        for (i in 0..4) {
            val y = h * i / 4f
            canvas.drawLine(0f, y, w, y, grid)
        }
        val topY = h * 0.02f + 10f
        canvas.drawText(String.format("%.0f", max), 6f, topY, label)
        canvas.drawText(String.format("%.0f", min), 6f, h - 6f, label)

        val n = data.size
        fun xOf(i: Int) = w * i / (n - 1).toFloat()
        fun yOf(v: Double) = (h - h * ((v - min) / (max - min)).coerceIn(0.0, 1.0)).toFloat()

        val path = Path()
        var started = false
        var lastValid = Double.NaN
        for (i in 0 until n) {
            val v = data[i][1]
            if (v.isNaN()) continue
            val x = xOf(i)
            val y = yOf(v)
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else path.lineTo(x, y)
            lastValid = v
        }
        if (!started) return

        val rising = if (startEquity > 0) (lastValid >= startEquity) else true
        line.color = if (rising) Color.parseColor("#22B99A") else Color.parseColor("#F0555B")

        // fill under the line
        val area = Path(path)
        area.lineTo(xOf(n - 1), h)
        area.lineTo(0f, h)
        area.close()
        fill.color = (line.color and 0x00FFFFFF) or 0x2A000000
        canvas.drawPath(area, fill)
        canvas.drawPath(path, line)
    }

    private fun unusedMax(v: Double): Double = max(v, 0.0)
}