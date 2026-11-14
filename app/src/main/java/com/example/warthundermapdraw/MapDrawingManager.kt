package com.example.warthundermapdraw

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import org.json.JSONObject

class MapDrawingManager(
    private val density: Float
) {

    enum class Tool {
        NONE,
        PENCIL,
        ERASER
    }

    data class Stroke(
        val color: Int,
        val widthDp: Float,
        val isEraser: Boolean,
        val points: MutableList<PointF>
    )

    data class LegendEntry(
        val color: Int,
        val colorName: String,
        val description: String
    )

    private data class DrawingLayer(
        val strokes: MutableList<Stroke> = mutableListOf(),
        val redo: MutableList<Stroke> = mutableListOf(),
        val legendDescriptions: MutableMap<Int, String> = mutableMapOf()
    )

    private val layers = mutableMapOf<String, DrawingLayer>()

    var currentKey: String = "default"
        private set

    var activeTool = Tool.NONE
    var currentColor = Color.RED

    var brushSizeDp = 4f
        set(value) {
            field = value.coerceIn(1f, 10f)
        }

    var onDrawingChanged: (() -> Unit)? = null

    private var activeStroke: Stroke? = null

    private val currentLayer: DrawingLayer
        get() = layers.getOrPut(currentKey) {
            DrawingLayer()
        }

    val canUndo: Boolean
        get() = currentLayer.strokes.isNotEmpty()

    val canRedo: Boolean
        get() = currentLayer.redo.isNotEmpty()

    fun switchLayer(key: String): Boolean {
        if (key == currentKey) return false

        activeStroke = null
        currentKey = key
        currentLayer

        onDrawingChanged?.invoke()
        return true
    }

    fun createMapKey(
        address: String,
        info: JSONObject
    ): String {
        val generation = info.optInt("map_generation", -1)
        val hudType = info.optInt("hud_type", -1)

        val mapName = info.optString(
            "map_name",
            info.optString(
                "map_id",
                info.optString("map", "")
            )
        )

        val mapMin = info.optJSONArray("map_min")?.toString() ?: ""
        val mapMax = info.optJSONArray("map_max")?.toString() ?: ""

        return listOf(
            address,
            generation.toString(),
            hudType.toString(),
            mapName,
            mapMin,
            mapMax
        ).joinToString("|")
    }

    fun startStroke(
        normalizedX: Float,
        normalizedY: Float
    ) {
        if (activeTool == Tool.NONE) return

        val eraser = activeTool == Tool.ERASER

        val stroke = Stroke(
            color = currentColor,
            widthDp = brushSizeDp.coerceIn(1f, 10f),
            isEraser = eraser,
            points = mutableListOf(
                PointF(
                    normalizedX.coerceIn(0f, 1f),
                    normalizedY.coerceIn(0f, 1f)
                )
            )
        )

        currentLayer.strokes.add(stroke)
        currentLayer.redo.clear()
        activeStroke = stroke

        onDrawingChanged?.invoke()
    }

    fun addPoint(
        normalizedX: Float,
        normalizedY: Float
    ) {
        val stroke = activeStroke ?: return

        stroke.points.add(
            PointF(
                normalizedX.coerceIn(0f, 1f),
                normalizedY.coerceIn(0f, 1f)
            )
        )
    }

    fun finishStroke() {
        activeStroke = null
        onDrawingChanged?.invoke()
    }

    fun undo() {
        if (!canUndo) return

        currentLayer.redo.add(
            currentLayer.strokes.removeAt(
                currentLayer.strokes.lastIndex
            )
        )

        activeStroke = null
        onDrawingChanged?.invoke()
    }

    fun redo() {
        if (!canRedo) return

        currentLayer.strokes.add(
            currentLayer.redo.removeAt(
                currentLayer.redo.lastIndex
            )
        )

        activeStroke = null
        onDrawingChanged?.invoke()
    }

    fun clearAll() {
        layers.clear()
        currentKey = "default"
        activeStroke = null

        onDrawingChanged?.invoke()
    }

    fun getLegendEntries(): List<LegendEntry> {
        val usedColors = currentLayer.strokes
            .filter { !it.isEraser }
            .map { it.color }
            .distinct()

        return usedColors.map { color ->
            LegendEntry(
                color = color,
                colorName = getColorName(color),
                description = currentLayer.legendDescriptions[color].orEmpty()
            )
        }
    }

    fun setColorDescription(
        color: Int,
        description: String
    ) {
        currentLayer.legendDescriptions[color] = description.trim()
        onDrawingChanged?.invoke()
    }

    private fun getColorName(color: Int): String {
        return when (color) {
            Color.RED -> "Red"
            Color.rgb(255, 140, 0) -> "Orange"
            Color.YELLOW -> "Yellow"
            Color.GREEN -> "Green"
            Color.CYAN -> "Cyan"
            Color.BLUE -> "Blue"
            Color.rgb(160, 70, 220) -> "Purple"
            Color.MAGENTA -> "Magenta"
            Color.WHITE -> "White"
            Color.BLACK -> "Black"
            else -> "Custom"
        }
    }

    fun draw(
        canvas: Canvas,
        mapRect: RectF
    ) {
        canvas.saveLayer(mapRect, null)

        for (stroke in currentLayer.strokes) {
            if (stroke.points.isEmpty()) continue

            val strokeWidth = stroke.widthDp.coerceIn(1f, 10f) * density

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                this.strokeWidth = strokeWidth
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND

                if (stroke.isEraser) {
                    color = Color.TRANSPARENT
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                } else {
                    color = stroke.color
                }
            }

            val first = stroke.points.first()

            val firstX = mapRect.left + first.x * mapRect.width()
            val firstY = mapRect.top + first.y * mapRect.height()

            if (stroke.points.size == 1) {
                paint.style = Paint.Style.FILL

                canvas.drawCircle(
                    firstX,
                    firstY,
                    strokeWidth / 2f,
                    paint
                )
            } else {
                val path = Path()
                path.moveTo(firstX, firstY)

                for (i in 1 until stroke.points.size) {
                    val point = stroke.points[i]

                    path.lineTo(
                        mapRect.left + point.x * mapRect.width(),
                        mapRect.top + point.y * mapRect.height()
                    )
                }

                canvas.drawPath(path, paint)
            }
        }

        canvas.restore()
    }
}