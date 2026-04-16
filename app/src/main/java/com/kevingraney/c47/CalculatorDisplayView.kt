package com.kevingraney.c47

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class CalculatorDisplayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#D2C89A")
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A1A1A")
        textSize = 9f * dp
        typeface = Typeface.MONOSPACE
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A1A1A")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A0A0A")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A1A1A")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val dividerPaint = Paint().apply {
        color = Color.parseColor("#666666")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val softkeyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A1A1A")
        textSize = 8f * dp
        typeface = Typeface.DEFAULT_BOLD
    }

    private val dashedBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A3A3A")
        style = Paint.Style.STROKE
        strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(4f, 2f), 0f)
    }

    private val arrowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A1A1A")
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val leftPanelRight = w * 0.40f
        val softkeyTop = h * 0.58f

        // Vertical divider between left info panel and graph
        canvas.drawLine(leftPanelRight, 0f, leftPanelRight, h, dividerPaint)

        // Horizontal divider separating text info from softkey boxes (left panel only)
        canvas.drawLine(0f, softkeyTop, leftPanelRight, softkeyTop, dividerPaint)

        drawTextInfo(canvas, 4f * dp, 4f * dp, leftPanelRight, softkeyTop)
        drawSoftkeyBoxes(canvas, 2f * dp, softkeyTop + 2f * dp, leftPanelRight - 2f * dp, h - 2f * dp)
        drawGraph(canvas, leftPanelRight + 1f, 0f, w, h)
    }

    private fun drawTextInfo(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        textPaint.textSize = 8.5f * dp
        var y = top + textPaint.textSize
        val lines = listOf(
            "axis 0\u00B70",
            "x  2\u00B70E-1/tick",
            "y    5\u00B70/tick"
        )
        for (line in lines) {
            canvas.drawText(line, left, y, textPaint)
            y += textPaint.textSize * 1.4f
        }

        // Corner coordinate labels
        val coordRight = "(2.2;38.5)"
        val cwR = textPaint.measureText(coordRight)
        canvas.drawText(coordRight, right - cwR - 2f * dp, y, textPaint)
        y += textPaint.textSize * 2.8f

        canvas.drawText("(-2.2;-38.5)", left, y, textPaint)

        // Small left-pointing triangle indicator
        val arrowY = bottom - 10f * dp
        val path = Path()
        path.moveTo(left + 6f * dp, arrowY)
        path.lineTo(left, arrowY - 4f * dp)
        path.lineTo(left, arrowY + 4f * dp)
        path.close()
        canvas.drawPath(path, arrowFillPaint)
    }

    private fun drawSoftkeyBoxes(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val totalH = bottom - top
        val totalW = right - left
        val rowH = totalH / 3f
        val colW = totalW / 2f

        val labels = arrayOf(
            arrayOf("-ZOOM\u2080", "+ZOOM\u2080"),
            arrayOf("X:Y=1\u25A1", "PLTRST"),
            arrayOf("\u2193X\u208B\u2082", "\u2191X\u2082")
        )

        softkeyTextPaint.textSize = 8f * dp

        for (row in 0..2) {
            val rowTop = top + row * rowH
            val rowBot = rowTop + rowH

            for (col in 0..1) {
                val colLeft = left + col * colW
                val colRight = colLeft + colW

                val boxRect = RectF(
                    colLeft + 1.5f * dp,
                    rowTop + 1.5f * dp,
                    colRight - 1.5f * dp,
                    rowBot - 1.5f * dp
                )
                canvas.drawRoundRect(boxRect, 2f * dp, 2f * dp, dashedBoxPaint)

                val label = labels[row][col]
                val tw = softkeyTextPaint.measureText(label)
                val tx = colLeft + (colW - tw) / 2f
                val ty = rowTop + rowH / 2f + softkeyTextPaint.textSize * 0.35f
                canvas.drawText(label, tx, ty, softkeyTextPaint)
            }
        }
    }

    private fun drawGraph(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val gW = right - left
        val gH = bottom - top

        val xMin = -2.2f; val xMax = 2.2f
        val yMin = -38.5f; val yMax = 38.5f

        fun sx(x: Float) = left + (x - xMin) / (xMax - xMin) * gW
        fun sy(y: Float) = bottom - (y - yMin) / (yMax - yMin) * gH

        val x0 = sx(0f)
        val y0 = sy(0f)

        // Axes
        canvas.drawLine(left + 2f, y0, right - 2f, y0, axisPaint)
        canvas.drawLine(x0, top + 2f, x0, bottom - 2f, axisPaint)

        // Axis arrowheads
        val arrowSize = 4f * dp
        // Right arrowhead on x-axis
        val axPath = Path()
        axPath.moveTo(right - 2f, y0)
        axPath.lineTo(right - 2f - arrowSize, y0 - arrowSize * 0.5f)
        axPath.lineTo(right - 2f - arrowSize, y0 + arrowSize * 0.5f)
        axPath.close()
        canvas.drawPath(axPath, arrowFillPaint)

        // X-axis ticks
        var xt = -2.0f
        while (xt <= 2.01f) {
            val sxt = sx(xt)
            canvas.drawLine(sxt, y0 - 3f * dp, sxt, y0 + 3f * dp, tickPaint)
            xt += 0.2f
        }

        // Y-axis ticks
        var yt = -35f
        while (yt <= 35.01f) {
            val syt = sy(yt)
            canvas.drawLine(x0 - 3f * dp, syt, x0 + 3f * dp, syt, tickPaint)
            yt += 5f
        }

        // Cubic S-curve: y = k * x^3
        val k = yMax / (xMax * xMax * xMax)
        val curvePath = Path()
        var first = true
        var px = xMin
        while (px <= xMax + 0.01f) {
            val py = k * px * px * px
            val cpx = sx(px)
            val cpy = sy(py)
            if (first) { curvePath.moveTo(cpx, cpy); first = false }
            else curvePath.lineTo(cpx, cpy)
            px += 0.02f
        }
        canvas.drawPath(curvePath, curvePaint)

        // Small marker at curve end (top right)
        val markerX = sx(2.15f)
        val markerY = sy(37f)
        val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0A0A0A")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(markerX, markerY, 3f * dp, markerPaint)
    }
}
