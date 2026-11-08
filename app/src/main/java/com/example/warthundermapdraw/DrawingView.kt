package com.example.warthundermapdraw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject

private data class DrawAction(val path: Path?, val paint: Paint?, val clear: Boolean)

class DrawingView(context: Context) : View(context) {

    var isDrawingEnabled = false
    var onColorUsed: ((Int) -> Unit)? = null
    var onHistoryChanged: (() -> Unit)? = null

    private var drawPath = Path()
    private var drawPaint = Paint().apply {
        color = Color.BLUE
        strokeWidth = 12f
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private var erasePaint = Paint().apply {
        strokeWidth = 36f
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private var isEraserOn = false
    private var canvasBitmap: Bitmap? = null
    private var drawCanvas: Canvas? = null
    private var drawingBounds = RectF()

    private val actions = mutableListOf<DrawAction>()
    private val redoActions = mutableListOf<DrawAction>()

    private var pendingBitmap: Bitmap? = null
    private var activePaint: Paint? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        if (w <= 0 || h <= 0) return

        val oldBitmap = canvasBitmap
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (pendingBitmap != null) {
            val source = pendingBitmap!!
            canvas.drawBitmap(source, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
            source.recycle()
            pendingBitmap = null
        } else if (oldBitmap != null) {
            canvas.drawBitmap(oldBitmap, 0f, 0f, null)
        }

        oldBitmap?.recycle()
        canvasBitmap = bitmap
        drawCanvas = canvas
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val save = canvas.save()
        if (!drawingBounds.isEmpty) canvas.clipRect(drawingBounds)

        canvasBitmap?.let {
            canvas.drawBitmap(it, 0f, 0f, null)
        }

        if (!drawPath.isEmpty) {
            canvas.drawPath(drawPath, activePaint ?: drawPaint)
        }

        canvas.restoreToCount(save)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isDrawingEnabled) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!drawingBounds.contains(event.x, event.y)) return false

                drawPath = Path()
                drawPath.moveTo(event.x, event.y)
                activePaint = Paint(if (isEraserOn) erasePaint else drawPaint)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) return true

                drawPath.lineTo(event.x, event.y)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!drawPath.isEmpty) {
                    drawCanvas?.drawPath(drawPath, activePaint ?: drawPaint)

                    actions.add(
                        DrawAction(
                            Path(drawPath),
                            Paint(activePaint ?: drawPaint),
                            isEraserOn
                        )
                    )

                    if (!isEraserOn) {
                        onColorUsed?.invoke(drawPaint.color)
                    }

                    drawPath.reset()
                    activePaint = null
                    redoActions.clear()
                    invalidate()
                    onHistoryChanged?.invoke()
                }

                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                drawPath.reset()
                activePaint = null
                invalidate()
                return true
            }
        }

        return true
    }

    fun setDrawingBounds(bounds: RectF) {
        drawingBounds = RectF(bounds)
        invalidate()
    }

    fun setColor(color: Int) {
        drawPaint.color = color
        isEraserOn = false
    }

    fun setEraseMode(enabled: Boolean) {
        isEraserOn = enabled
    }

    fun canUndo(): Boolean = actions.isNotEmpty()

    fun canRedo(): Boolean = redoActions.isNotEmpty()

    fun undo() {
        if (actions.isEmpty()) return

        redoActions.add(actions.removeAt(actions.lastIndex))
        redrawActions()
        onHistoryChanged?.invoke()
    }

    fun redo() {
        if (redoActions.isEmpty()) return

        actions.add(redoActions.removeAt(redoActions.lastIndex))
        redrawActions()
        onHistoryChanged?.invoke()
    }

    fun clearDrawing() {
        actions.add(DrawAction(null, null, true))
        redoActions.clear()
        redrawActions()
        onHistoryChanged?.invoke()
    }

    fun loadBitmap(bitmap: Bitmap) {
        if (width <= 0 || height <= 0) {
            pendingBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            return
        }

        val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        canvas.drawBitmap(bitmap, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))

        canvasBitmap?.recycle()
        canvasBitmap = target
        drawCanvas = Canvas(target)

        bitmap.recycle()
        actions.clear()
        redoActions.clear()
        invalidate()
        onHistoryChanged?.invoke()
    }

    fun exportActions(): JSONArray {
        val result = JSONArray()

        if (width <= 0 || height <= 0) return result

        val scale = minOf(width, height).toFloat()

        for (action in actions) {
            val item = JSONObject()
            item.put("clear", action.clear)

            if (action.path == null || action.paint == null) {
                item.put("points", JSONArray())
                item.put("color", Color.TRANSPARENT)
                item.put("stroke", 0f)
            } else {
                item.put("color", action.paint.color)
                item.put("stroke", action.paint.strokeWidth / scale)

                val points = JSONArray()
                val values = action.path.approximate(0.5f)

                var i = 0
                while (i + 2 < values.size) {
                    points.put(
                        JSONArray()
                            .put(values[i + 1] / width.toFloat())
                            .put(values[i + 2] / height.toFloat())
                    )
                    i += 3
                }

                item.put("points", points)
            }

            result.put(item)
        }

        return result
    }

    fun importActions(data: JSONArray) {
        actions.clear()
        redoActions.clear()

        if (width <= 0 || height <= 0) return

        val scale = minOf(width, height).toFloat()

        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val clear = item.optBoolean("clear", false)
            val points = item.optJSONArray("points") ?: JSONArray()

            if (points.length() == 0) {
                if (clear) actions.add(DrawAction(null, null, true))
                continue
            }

            val path = Path()

            for (j in 0 until points.length()) {
                val point = points.optJSONArray(j) ?: continue
                if (point.length() < 2) continue

                val x = point.optDouble(0).toFloat() * width
                val y = point.optDouble(1).toFloat() * height

                if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }

            val paint = Paint().apply {
                color = item.optInt("color", Color.BLUE)
                strokeWidth = item.optDouble("stroke", 0.01).toFloat() * scale
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND

                if (clear) {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                }
            }

            actions.add(DrawAction(path, paint, clear))
        }

        redrawActions()
        onHistoryChanged?.invoke()
    }

    fun redrawActions() {
        val bitmap = canvasBitmap ?: return
        bitmap.eraseColor(Color.TRANSPARENT)
        drawCanvas = Canvas(bitmap)

        for (action in actions) {
            if (action.clear) {
                if (action.path == null) {
                    bitmap.eraseColor(Color.TRANSPARENT)
                } else {
                    drawCanvas?.drawPath(action.path, action.paint ?: erasePaint)
                }
            } else if (action.path != null && action.paint != null) {
                drawCanvas?.drawPath(action.path, action.paint)
            }
        }

        invalidate()
    }
}