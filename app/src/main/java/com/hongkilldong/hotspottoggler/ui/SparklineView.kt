package com.hongkilldong.hotspottoggler.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.hongkilldong.hotspottoggler.R

class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val down = mutableListOf<Double>()
    private val up = mutableListOf<Double>()
    private val downPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.pri)
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val upPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ok)
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val downFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (context.getColor(R.color.pri) and 0x00FFFFFF) or 0x22000000
        style = Paint.Style.FILL
    }
    private val upFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (context.getColor(R.color.ok) and 0x00FFFFFF) or 0x18000000
        style = Paint.Style.FILL
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.surf2)
        strokeWidth = context.dp(1).toFloat()
    }
    private val downPath = Path()
    private val upPath = Path()
    private val downArea = Path()
    private val upArea = Path()
    private val rect = RectF()

    init {
        minimumHeight = context.dp(72)
    }

    fun setData(downValues: List<Double>, upValues: List<Double>) {
        down.clear()
        up.clear()
        down.addAll(downValues.takeLast(60))
        up.addAll(upValues.takeLast(60))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val pad = context.dp(2).toFloat()
        val top = pad
        val bottom = h - pad
        canvas.drawLine(pad, bottom, w - pad, bottom, grid)
        canvas.drawLine(pad, (top + bottom) / 2f, w - pad, (top + bottom) / 2f, grid)
        val max = (down + up).maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
        drawSeries(canvas, down, downPath, downArea, downPaint, downFill, max, w, top, bottom)
        drawSeries(canvas, up, upPath, upArea, upPaint, upFill, max, w, top, bottom)
    }

    private fun drawSeries(
        canvas: Canvas,
        values: List<Double>,
        path: Path,
        area: Path,
        paint: Paint,
        fill: Paint,
        max: Double,
        w: Float,
        top: Float,
        bottom: Float,
    ) {
        path.reset()
        area.reset()
        if (values.size < 2) return
        val step = if (values.size > 1) (w - context.dp(4)) / (values.size - 1) else 0f
        val left = context.dp(2).toFloat()
        for ((index, value) in values.withIndex()) {
            val x = left + step * index
            val y = bottom - ((value / max).coerceIn(0.0, 1.0) * (bottom - top)).toFloat()
            if (index == 0) {
                path.moveTo(x, y)
                area.moveTo(x, bottom)
                area.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        area.lineTo(left + step * (values.size - 1), bottom)
        area.close()
        canvas.drawPath(area, fill)
        canvas.drawPath(path, paint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, context.dp(72))
    }
}
