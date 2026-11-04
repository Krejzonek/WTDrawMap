package com.example.warthundermapdraw

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

class DrawingView(context: Context) : View(context) {

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
    private var isEraserOn = false
    private var canvasBitmap: Bitmap? = null
    private var drawCanvas: Canvas? = null

    private val erasePaint = Paint(drawPaint).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        strokeWidth = 36f
    }

    fun setColor(color: Int) {
        drawPaint.color = color
        isEraserOn = false
        invalidate()
    }

    fun setEraseMode(erase: Boolean) {
        isEraserOn = erase
    }

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
        canvasBitmap?.let {
            canvas.drawBitmap(it, 0f, 0f, null)
        }

        val paint = if (isEraserOn) erasePaint else drawPaint
        canvas.drawPath(drawPath, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isDrawingEnabled) return false

        if (event.pointerCount > 1) {
            drawPath.reset()
            invalidate()
            return true
        }

        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drawPath.reset()
                drawPath.moveTo(x, y)
            }

            MotionEvent.ACTION_MOVE -> {
                drawPath.lineTo(x, y)
            }

            MotionEvent.ACTION_UP -> {
                val paint = if (isEraserOn) erasePaint else drawPaint
                drawCanvas?.drawPath(drawPath, paint)
                drawPath.reset()
            }

            MotionEvent.ACTION_CANCEL -> {
                drawPath.reset()
            }
        }

        invalidate()
        return true
    }

    fun clearDrawing() {
        canvasBitmap?.eraseColor(Color.TRANSPARENT)
        drawPath.reset()
        invalidate()
    }
}