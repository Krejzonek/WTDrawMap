package com.example.warthundermapdraw

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

class DrawingView(context: Context) : View(context) {

    private data class DrawAction(
        val path: Path? = null,
        val paint: Paint? = null,
        val clear: Boolean = false
    )

    private var drawPath = Path()
    private var drawPaint = Paint().apply {
        color = Color.BLUE
        isAntiAlias = true
        strokeWidth = 12f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    var isDrawingEnabled = false
    var onColorUsed: ((Int) -> Unit)? = null
    var onHistoryChanged: (() -> Unit)? = null

    private var isEraserOn = false
    private var canvasBitmap: Bitmap? = null
    private var drawCanvas: Canvas? = null
    private var drawingBounds = RectF()

    private val actions = mutableListOf<DrawAction>()
    private val redoActions = mutableListOf<DrawAction>()

    private val erasePaint = Paint().apply {
        isAntiAlias = true
        strokeWidth = 36f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    fun setDrawingBounds(bounds: RectF) {
        drawingBounds.set(bounds)
        invalidate()
    }

    fun setColor(color: Int) {
        drawPaint.color = color
        isEraserOn = false
        invalidate()
    }

    fun setEraseMode(erase: Boolean) {
        isEraserOn = erase
    }

    fun canUndo(): Boolean = actions.isNotEmpty()

    fun canRedo(): Boolean = redoActions.isNotEmpty()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        if (w <= 0 || h <= 0) return

        val oldBitmap = canvasBitmap
        canvasBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        canvasBitmap?.eraseColor(Color.TRANSPARENT)

        if (oldBitmap != null) {
            Canvas(canvasBitmap!!).drawBitmap(oldBitmap, 0f, 0f, null)
            oldBitmap.recycle()
        }

        drawCanvas = Canvas(canvasBitmap!!)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (drawingBounds.isEmpty) return

        val saveCount = canvas.save()
        canvas.clipRect(drawingBounds)

        canvasBitmap?.let {
            canvas.drawBitmap(it, 0f, 0f, null)
        }

        val paint = if (isEraserOn) erasePaint else drawPaint
        canvas.drawPath(drawPath, paint)

        canvas.restoreToCount(saveCount)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isDrawingEnabled || drawingBounds.isEmpty) return false

        if (event.pointerCount > 1) {
            drawPath.reset()
            invalidate()
            return true
        }

        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!drawingBounds.contains(x, y)) return false

                drawPath.reset()
                drawPath.moveTo(x, y)
            }

            MotionEvent.ACTION_MOVE -> {
                drawPath.lineTo(x, y)
            }

            MotionEvent.ACTION_UP -> {
                if (!drawPath.isEmpty) {
                    val paint = if (isEraserOn) Paint(erasePaint) else Paint(drawPaint)
                    val path = Path(drawPath)

                    drawCanvas?.let {
                        val saveCount = it.save()
                        it.clipRect(drawingBounds)
                        it.drawPath(path, paint)
                        it.restoreToCount(saveCount)
                    }

                    actions.add(DrawAction(path, paint))
                    redoActions.clear()

                    if (!isEraserOn) {
                        onColorUsed?.invoke(drawPaint.color)
                    }
                }

                drawPath.reset()
                onHistoryChanged?.invoke()
            }

            MotionEvent.ACTION_CANCEL -> {
                drawPath.reset()
            }
        }

        invalidate()
        return true
    }

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
        if (canvasBitmap == null) return

        actions.add(DrawAction(clear = true))
        redoActions.clear()
        drawPath.reset()

        redrawActions()
        onHistoryChanged?.invoke()
    }

    private fun redrawActions() {
        canvasBitmap?.eraseColor(Color.TRANSPARENT)

        val canvas = drawCanvas ?: return
        val saveCount = canvas.save()
        canvas.clipRect(drawingBounds)

        for (action in actions) {
            if (action.clear) {
                canvasBitmap?.eraseColor(Color.TRANSPARENT)
            } else if (action.path != null && action.paint != null) {
                canvas.drawPath(action.path, action.paint)
            }
        }

        canvas.restoreToCount(saveCount)
        invalidate()
    }
}