package com.beyza.poseestimation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var keyPoints: List<KeyPoint> = emptyList()

    private val pointPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        strokeWidth = 8f
    }

    private val linePaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val connections = listOf(
        5 to 7, 7 to 9,
        6 to 8, 8 to 10,
        5 to 6,
        11 to 12,
        5 to 11, 6 to 12,
        11 to 13, 13 to 15,
        12 to 14, 14 to 16
    )

    fun setKeyPoints(points: List<KeyPoint>) {
        keyPoints = points
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // ÖNCE çizgileri çiz
        for ((startIdx, endIdx) in connections) {
            val startPoint = keyPoints.getOrNull(startIdx)
            val endPoint = keyPoints.getOrNull(endIdx)

            if (startPoint != null && endPoint != null &&
                startPoint.score > 0.3f && endPoint.score > 0.3f) {

                canvas.drawLine(
                    (1f - startPoint.x) * width, startPoint.y * height,
                    (1f - endPoint.x) * width, endPoint.y * height,
                    linePaint
                )
            }
        }

        // SONRA noktaları çiz
        for (point in keyPoints) {
            if (point.score > 0.3f) {
                val x = (1f - point.x) * width
                val y = point.y * height
                canvas.drawCircle(x, y, 12f, pointPaint)
            }
        }
    }
}