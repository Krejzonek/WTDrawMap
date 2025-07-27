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
    var isDrawingEnabled = true
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
        canvasBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        canvasBitmap?.eraseColor(Color.TRANSPARENT)
        drawCanvas = Canvas(canvasBitmap!!)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawBitmap(canvasBitmap!!, 0f, 0f, null)
        val paint = if (isEraserOn) erasePaint else drawPaint
        canvas.drawPath(drawPath, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isDrawingEnabled) return false

        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> drawPath.moveTo(x, y)
            MotionEvent.ACTION_MOVE -> drawPath.lineTo(x, y)
            MotionEvent.ACTION_UP -> {
                val paint = if (isEraserOn) erasePaint else drawPaint
                drawCanvas?.drawPath(drawPath, paint)
                drawPath.reset()
            }
        }
        invalidate()
        return true
    }

    fun clearDrawing() {
        canvasBitmap?.eraseColor(Color.TRANSPARENT)
        invalidate()
    }
}
