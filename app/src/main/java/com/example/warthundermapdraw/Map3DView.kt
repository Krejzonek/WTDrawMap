package com.example.warthundermapdraw

import android.content.Context
import android.graphics.Color
import android.view.Choreographer
import android.view.Gravity
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer

class Map3DView(context: Context) : FrameLayout(context) {

    private val surfaceView = SurfaceView(context)
    private val status = TextView(context)
    private var modelViewer: ModelViewer? = null
    private var isRendering = false
    private var isInitialized = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isRendering) return
            modelViewer?.render(frameTimeNanos)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        setBackgroundColor(Color.rgb(21, 25, 31))

        addView(
            surfaceView,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
        )

        status.apply {
            text = "Loading 3D map..."
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(12, 8, 12, 8)
            setBackgroundColor(0x99000000.toInt())
            gravity = Gravity.CENTER_VERTICAL
        }

        addView(
            status,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )
    }

    fun loadModel(bytes: ByteArray?) {
        if (bytes == null) {
            status.text = "No 3D model available for this map."
            return
        }

        if (isInitialized) return

        try {
            Utils.init()
            modelViewer = ModelViewer(surfaceView)
            surfaceView.setOnTouchListener(modelViewer)
            modelViewer?.loadModelGlb(ByteBuffer.wrap(bytes))
            modelViewer?.transformToUnitCube()

            isInitialized = true
            status.text = "Drag to rotate · Pinch to zoom · Two fingers to pan"
        } catch (e: Exception) {
            status.text = "Unable to load 3D model: ${e.localizedMessage ?: "invalid model"}"
        }
    }

    fun onResumeRendering() {
        if (!isInitialized || isRendering) return
        isRendering = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun onPauseRendering() {
        isRendering = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    fun release() {
        onPauseRendering()
        surfaceView.setOnTouchListener(null)
        modelViewer?.destroy()
        modelViewer = null
        isInitialized = false
    }
}