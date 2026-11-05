package com.example.warthundermapdraw

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

data class AiMapPoint(
    val x: Float,
    val y: Float
)

data class AiSuggestion(
    val points: List<AiMapPoint>,
    val label: String,
    val color: Int
)

class AiSuggestionOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val suggestions = mutableListOf<AiSuggestion>()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 32f
        style = Paint.Style.FILL
        setShadowLayer(4f, 2f, 2f, android.graphics.Color.BLACK)
    }

    fun setSuggestions(newSuggestions: List<AiSuggestion>) {
        suggestions.clear()
        suggestions.addAll(newSuggestions.filter { suggestion ->
            suggestion.points.all {
                it.x in 0f..1f && it.y in 0f..1f
            }
        })
        invalidate()
    }

    fun clearSuggestions() {
        suggestions.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (suggestion in suggestions) {
            if (suggestion.points.isEmpty()) continue

            val path = Path()
            val firstPoint = suggestion.points.first()

            path.moveTo(firstPoint.x * width, firstPoint.y * height)

            for (point in suggestion.points.drop(1)) {
                path.lineTo(point.x * width, point.y * height)
            }

            linePaint.color = suggestion.color
            canvas.drawPath(path, linePaint)

            for ((index, point) in suggestion.points.withIndex()) {
                val x = point.x * width
                val y = point.y * height

                pointPaint.color = suggestion.color
                canvas.drawCircle(x, y, 10f, pointPaint)

                if (index == suggestion.points.lastIndex && suggestion.label.isNotBlank()) {
                    canvas.drawText(suggestion.label, x + 16f, y - 16f, textPaint)
                }
            }
        }
    }
}