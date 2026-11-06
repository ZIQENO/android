package dev.ziqeno.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.log10
import kotlin.math.pow

internal class SpeedChart(context: Context, private val ui: PixelUi) : View(context) {
    private val paint = Paint()
    private val path = Path()
    private var points = emptyList<TrafficPoint>()
    private var now = 0L
    private var ceiling = 1.0

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "График загрузки и отдачи через VPN за последние 10 минут"
    }

    fun update(samples: List<TrafficPoint>, time: Long): Double? {
        points = samples
        now = time
        if (samples.isNotEmpty()) {
            val peak = samples.maxOf { maxOf(it.download, it.upload) }.coerceAtLeast(0.1)
            val magnitude = 10.0.pow(kotlin.math.floor(log10(peak)))
            ceiling = listOf(1, 2, 5, 10).first { it * magnitude >= peak } * magnitude
        }
        invalidate()
        return ceiling.takeIf { samples.isNotEmpty() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = ui.dp(2).toFloat()
        val right = width - left
        val top = ui.dp(5).toFloat()
        val bottom = height - top
        if (right <= left || bottom <= top) return
        val unit = ui.dp(1).toFloat().coerceAtLeast(1f)
        paint.color = ui.line
        paint.style = Paint.Style.FILL
        repeat(4) { index ->
            val y = top + (bottom - top) * index / 3
            canvas.drawRect(left, y, right, y + unit, paint)
        }
        repeat(11) { index ->
            val x = left + (right - left) * index / 10
            canvas.drawRect(x, bottom - ui.dp(5), x + unit, bottom, paint)
        }
        if (points.isEmpty()) return
        val graphBottom = bottom - unit
        val start = now - trafficWindowMillis
        fun x(point: TrafficPoint) = left + (right - left) * (point.at - start) / trafficWindowMillis
        fun plot(color: Int, level: (TrafficPoint) -> Double) {
            path.rewind()
            paint.color = color
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = unit * 2
            var lastPoint: TrafficPoint? = null
            var lastY = 0f
            for (point in points) {
                val nextX = x(point)
                val nextY = graphBottom - ((graphBottom - top) * level(point) / ceiling).toFloat()
                val previous = lastPoint
                if (previous == null || point.at - previous.at > trafficGapMillis) {
                    path.moveTo(nextX, nextY)
                    canvas.drawPoint(nextX, nextY, paint)
                } else {
                    path.lineTo(nextX, lastY)
                    path.lineTo(nextX, nextY)
                }
                lastPoint = point
                lastY = nextY
            }
            canvas.drawPath(path, paint)
        }
        plot(ui.ink, TrafficPoint::upload)
        plot(ui.accent, TrafficPoint::download)
    }
}
