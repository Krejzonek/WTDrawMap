package com.example.warthundermapdraw

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

class DrawingView(context: Context) : View(context) {
    private var drawPath = Path()
    private var drawPaint = Paint().apply {
        color = Color.RED
        isAntiAlias = true
        strokeWidth = 12f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    var isDrawingEnabled = true
    var isErasing = false
    private var canvasBitmap: Bitmap? = null
    private var drawCanvas: Canvas? = null
    private val drawColor = Color.RED
    private val erasePaint = Paint(drawPaint).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        strokeWidth = 36f
    }

    private var isEraserOn = false

    fun setEraseMode(erase: Boolean) {
        isEraserOn = erase
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        canvasBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawCanvas = Canvas(canvasBitmap!!)
    }

    override fun onDraw(canvas: Canvas) {
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
    fun startDrawing() {
        isDrawingEnabled = true
        isErasing = false
    }

    fun stopDrawing() {
        isDrawingEnabled = false
    }

    fun startErasing() {
        isDrawingEnabled = true
        isErasing = true
    }

}
