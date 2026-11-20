package com.example.warthundermapdraw

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.PathMeasure
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

class MapDrawingManager(private val density: Float) {

    enum class Tool {
        NONE, PENCIL, ERASER
    }

    data class Stroke(
        val color: Int,
        val widthDp: Float,
        val isEraser: Boolean,
        val points: MutableList<PointF>,
        val id: String = UUID.randomUUID().toString(),
        val authorUid: String = "",
        val authorName: String = ""
    )

    data class LegendEntry(
        val color: Int,
        val colorName: String,
        val description: String
    )

    private data class DrawingLayer(
        val strokes: MutableList<Stroke> = mutableListOf(),
        val redo: MutableMap<String, MutableList<Stroke>> = mutableMapOf(),
        val legendDescriptions: MutableMap<Int, String> = mutableMapOf()
    )

    private val layers = mutableMapOf<String, DrawingLayer>()

    var currentKey = "default"
        private set

    var activeTool = Tool.NONE
    var currentColor = Color.RED
    var brushSizeDp = 4f
        set(value) {
            field = value.coerceIn(1f, 10f)
        }

    var onDrawingChanged: (() -> Unit)? = null
    var onLocalOperation: ((JSONObject) -> Unit)? = null

    private var activeStroke: Stroke? = null
    private var localUid = "local"
    private var localName = ""

    var canDraw = true
        private set

    private val currentLayer: DrawingLayer
        get() = layers.getOrPut(currentKey) { DrawingLayer() }

    val canUndo: Boolean
        get() = canDraw && currentLayer.strokes.any { it.authorUid == localUid }

    val canRedo: Boolean
        get() = canDraw && currentLayer.redo[localUid]?.isNotEmpty() == true

    fun setLocalAuthor(uid: String, name: String) {
        localUid = uid.ifBlank { "local" }
        localName = name.trim()
    }

    fun setDrawingPermission(allowed: Boolean) {
        if (canDraw == allowed) return

        canDraw = allowed

        if (!allowed) {
            activeStroke?.let { stroke ->
                currentLayer.strokes.removeAll { it.id == stroke.id }
            }
            activeStroke = null
            activeTool = Tool.NONE
        }

        onDrawingChanged?.invoke()
    }

    fun switchLayer(key: String): Boolean {
        if (key == currentKey) return false

        activeStroke = null
        currentKey = key
        currentLayer
        onDrawingChanged?.invoke()
        return true
    }

    fun createMapKey(address: String, info: JSONObject): String {
        val mapName = info.optString(
            "map_name",
            info.optString("map_id", info.optString("map", ""))
        )

        return listOf(
            address,
            info.optInt("map_generation", -1).toString(),
            mapName,
            info.optJSONArray("map_min")?.toString() ?: "",
            info.optJSONArray("map_max")?.toString() ?: ""
        ).joinToString("|")
    }

    fun startStroke(normalizedX: Float, normalizedY: Float) {
        if (!canDraw || activeTool == Tool.NONE) return

        val stroke = Stroke(
            color = currentColor,
            widthDp = brushSizeDp,
            isEraser = activeTool == Tool.ERASER,
            points = mutableListOf(
                PointF(normalizedX.coerceIn(0f, 1f), normalizedY.coerceIn(0f, 1f))
            ),
            authorUid = localUid,
            authorName = localName
        )

        currentLayer.strokes.add(stroke)
        currentLayer.redo[localUid]?.clear()
        activeStroke = stroke
        onDrawingChanged?.invoke()
    }

    fun addPoint(normalizedX: Float, normalizedY: Float) {
        if (!canDraw) return

        val stroke = activeStroke ?: return
        stroke.points.add(
            PointF(normalizedX.coerceIn(0f, 1f), normalizedY.coerceIn(0f, 1f))
        )
        onDrawingChanged?.invoke()
    }

    fun finishStroke() {
        if (!canDraw) {
            activeStroke = null
            return
        }

        val stroke = activeStroke ?: return
        activeStroke = null

        onLocalOperation?.invoke(
            strokeToJson(stroke, currentKey).put("type", "drawingStroke")
        )
        onDrawingChanged?.invoke()
    }

    fun undo() {
        if (!canDraw) return

        val index = currentLayer.strokes.indexOfLast { it.authorUid == localUid }
        if (index < 0) return

        val stroke = currentLayer.strokes.removeAt(index)
        currentLayer.redo.getOrPut(localUid) { mutableListOf() }.add(stroke)

        if (activeStroke?.id == stroke.id) activeStroke = null

        onLocalOperation?.invoke(
            JSONObject()
                .put("type", "drawingUndo")
                .put("strokeId", stroke.id)
                .put("authorUid", localUid)
                .put("mapKey", currentKey)
        )
        onDrawingChanged?.invoke()
    }

    fun redo() {
        if (!canDraw) return

        val redoList = currentLayer.redo[localUid] ?: return
        if (redoList.isEmpty()) return

        val stroke = redoList.removeAt(redoList.lastIndex)
        currentLayer.strokes.add(stroke)

        onLocalOperation?.invoke(
            JSONObject()
                .put("type", "drawingRedo")
                .put("strokeId", stroke.id)
                .put("authorUid", localUid)
                .put("mapKey", currentKey)
        )
        onDrawingChanged?.invoke()
    }

    fun clearAll() {
        layers.clear()
        currentKey = "default"
        activeStroke = null
        onDrawingChanged?.invoke()
    }

    fun clearUserDrawings(uid: String) {
        if (uid.isBlank()) return

        var changed = false

        layers.values.forEach { layer ->
            if (layer.strokes.removeAll { it.authorUid == uid }) changed = true
            if (layer.redo.remove(uid) != null) changed = true
        }

        if (activeStroke?.authorUid == uid) {
            activeStroke = null
            changed = true
        }

        if (changed) onDrawingChanged?.invoke()
    }

    fun getLegendEntries(): List<LegendEntry> {
        return currentLayer.strokes
            .filterNot { it.isEraser }
            .map { it.color }
            .distinct()
            .map { color ->
                LegendEntry(
                    color,
                    getColorName(color),
                    currentLayer.legendDescriptions[color].orEmpty()
                )
            }
    }

    fun setColorDescription(color: Int, description: String) {
        if (!canDraw) return

        val cleanDescription = description.trim().take(40)
        currentLayer.legendDescriptions[color] = cleanDescription

        onLocalOperation?.invoke(
            JSONObject()
                .put("type", "drawingLegend")
                .put("color", color)
                .put("description", cleanDescription)
                .put("mapKey", currentKey)
        )
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

    fun createSnapshot(): JSONObject {
        val strokes = JSONArray()
        currentLayer.strokes.forEach { strokes.put(strokeToJson(it, currentKey)) }

        val legend = JSONArray()
        currentLayer.legendDescriptions.forEach { (color, description) ->
            legend.put(
                JSONObject()
                    .put("color", color)
                    .put("description", description)
            )
        }

        return JSONObject()
            .put("type", "drawingSnapshot")
            .put("mapKey", currentKey)
            .put("strokes", strokes)
            .put("legend", legend)
    }

    fun applyRemoteMessage(message: JSONObject) {
        when (message.optString("type")) {
            "drawingSnapshot" -> applySnapshot(message)
            "drawingStroke" -> applyRemoteStroke(message)
            "drawingUndo" -> applyRemoteUndo(message)
            "drawingRedo" -> applyRemoteRedo(message)
            "drawingLegend" -> applyRemoteLegend(message)
            "drawingClearUser" -> clearUserDrawings(message.optString("uid"))
        }
    }

    private fun applySnapshot(message: JSONObject) {
        val mapKey = message.optString("mapKey", currentKey)
        val layer = layers.getOrPut(mapKey) { DrawingLayer() }

        layer.strokes.clear()
        layer.redo.clear()
        layer.legendDescriptions.clear()

        val strokes = message.optJSONArray("strokes") ?: JSONArray()
        for (i in 0 until strokes.length()) {
            val stroke = strokeFromJson(strokes.optJSONObject(i) ?: continue) ?: continue
            layer.strokes.add(stroke)
        }

        val legend = message.optJSONArray("legend") ?: JSONArray()
        for (i in 0 until legend.length()) {
            val entry = legend.optJSONObject(i) ?: continue
            layer.legendDescriptions[entry.optInt("color")] = entry.optString("description").trim().take(40)
        }

        if (mapKey == currentKey) {
            activeStroke = null
            onDrawingChanged?.invoke()
        }
    }

    private fun applyRemoteStroke(message: JSONObject) {
        val mapKey = message.optString("mapKey", currentKey)
        val layer = layers.getOrPut(mapKey) { DrawingLayer() }
        val stroke = strokeFromJson(message) ?: return

        if (layer.strokes.any { it.id == stroke.id }) return

        layer.strokes.add(stroke)
        layer.redo[stroke.authorUid]?.clear()

        if (mapKey == currentKey) onDrawingChanged?.invoke()
    }

    private fun applyRemoteUndo(message: JSONObject) {
        val mapKey = message.optString("mapKey", currentKey)
        val layer = layers.getOrPut(mapKey) { DrawingLayer() }
        val strokeId = message.optString("strokeId")
        val authorUid = message.optString("authorUid")

        val index = layer.strokes.indexOfLast {
            it.id == strokeId && (authorUid.isBlank() || it.authorUid == authorUid)
        }
        if (index < 0) return

        val stroke = layer.strokes.removeAt(index)
        layer.redo.getOrPut(stroke.authorUid) { mutableListOf() }.add(stroke)

        if (activeStroke?.id == stroke.id) activeStroke = null
        if (mapKey == currentKey) onDrawingChanged?.invoke()
    }

    private fun applyRemoteRedo(message: JSONObject) {
        val mapKey = message.optString("mapKey", currentKey)
        val layer = layers.getOrPut(mapKey) { DrawingLayer() }
        val strokeId = message.optString("strokeId")
        val authorUid = message.optString("authorUid")
        val redoList = layer.redo[authorUid] ?: return

        val index = redoList.indexOfLast { it.id == strokeId }
        if (index < 0) return

        layer.strokes.add(redoList.removeAt(index))
        if (mapKey == currentKey) onDrawingChanged?.invoke()
    }

    private fun applyRemoteLegend(message: JSONObject) {
        val mapKey = message.optString("mapKey", currentKey)
        val layer = layers.getOrPut(mapKey) { DrawingLayer() }

        layer.legendDescriptions[message.optInt("color")] = message.optString("description").trim().take(40)
        if (mapKey == currentKey) onDrawingChanged?.invoke()
    }

    private fun strokeToJson(stroke: Stroke, mapKey: String): JSONObject {
        val points = JSONArray()
        stroke.points.forEach { point ->
            points.put(
                JSONObject()
                    .put("x", point.x.toDouble())
                    .put("y", point.y.toDouble())
            )
        }

        return JSONObject()
            .put("id", stroke.id)
            .put("color", stroke.color)
            .put("widthDp", stroke.widthDp.toDouble())
            .put("isEraser", stroke.isEraser)
            .put("authorUid", stroke.authorUid)
            .put("authorName", stroke.authorName)
            .put("points", points)
            .put("mapKey", mapKey)
    }

    private fun strokeFromJson(json: JSONObject): Stroke? {
        val pointsJson = json.optJSONArray("points") ?: return null
        val points = mutableListOf<PointF>()

        for (i in 0 until pointsJson.length()) {
            val point = pointsJson.optJSONObject(i) ?: continue
            points.add(
                PointF(
                    point.optDouble("x").toFloat().coerceIn(0f, 1f),
                    point.optDouble("y").toFloat().coerceIn(0f, 1f)
                )
            )
        }

        if (points.isEmpty()) return null

        return Stroke(
            id = json.optString("id", UUID.randomUUID().toString()),
            color = json.optInt("color", Color.RED),
            widthDp = json.optDouble("widthDp", 4.0).toFloat().coerceIn(1f, 10f),
            isEraser = json.optBoolean("isEraser", false),
            points = points,
            authorUid = json.optString("authorUid"),
            authorName = json.optString("authorName")
        )
    }

    fun draw(canvas: Canvas, mapRect: RectF) {
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
                canvas.drawCircle(firstX, firstY, strokeWidth / 2f, paint)
            } else {
                val path = Path().apply { moveTo(firstX, firstY) }

                for (i in 1 until stroke.points.size) {
                    val point = stroke.points[i]
                    path.lineTo(
                        mapRect.left + point.x * mapRect.width(),
                        mapRect.top + point.y * mapRect.height()
                    )
                }

                canvas.drawPath(path, paint)
            }

            if (!stroke.isEraser && stroke.authorName.isNotBlank()) {
                drawAuthorName(canvas, mapRect, stroke, strokeWidth)
            }
        }

        canvas.restore()
    }

    private fun drawAuthorName(canvas: Canvas, mapRect: RectF, stroke: Stroke, strokeWidth: Float) {
        if (stroke.points.size < 2) return

        val text = stroke.authorName.trim()
        if (text.isBlank()) return

        val points = stroke.points.map {
            PointF(
                mapRect.left + it.x * mapRect.width(),
                mapRect.top + it.y * mapRect.height()
            )
        }

        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
        }

        val pathMeasure = PathMeasure(path, false)
        val pathLength = pathMeasure.length
        if (pathLength <= 0f) return

        val maxSize = 11f * density
        val minSize = 1.5f * density
        val padding = max(1f * density, strokeWidth * 0.5f)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.LEFT
            style = Paint.Style.FILL
            typeface = android.graphics.Typeface.create(
                "sans-serif-medium",
                android.graphics.Typeface.BOLD
            )
            textSize = maxSize
        }

        val originalWidth = paint.measureText(text)
        if (originalWidth <= 0f) return

        val availableWidth = max(1f, pathLength - padding * 2f)
        paint.textSize = max(minSize, min(maxSize, maxSize * (availableWidth / originalWidth)))

        val textWidth = paint.measureText(text)
        if (textWidth > pathLength) paint.textSize *= pathLength / textWidth

        val outlinePaint = Paint(paint).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            this.strokeWidth = max(1f * density, strokeWidth * 0.2f)
            strokeJoin = Paint.Join.ROUND
        }

        val metrics = paint.fontMetrics
        val verticalOffset = -(metrics.ascent + metrics.descent) / 2f
        val offset = max(0f, (pathLength - paint.measureText(text)) / 2f)

        val middlePosition = FloatArray(2)
        val middleTangent = FloatArray(2)
        pathMeasure.getPosTan(pathLength / 2f, middlePosition, middleTangent)

        if (middleTangent[0] < 0f) {
            path.set(Path().apply {
                moveTo(points.last().x, points.last().y)
                for (i in points.lastIndex - 1 downTo 0) {
                    lineTo(points[i].x, points[i].y)
                }
            })
        }

        canvas.drawTextOnPath(text, path, offset, verticalOffset, outlinePaint)
        canvas.drawTextOnPath(text, path, offset, verticalOffset, paint)
    }
}