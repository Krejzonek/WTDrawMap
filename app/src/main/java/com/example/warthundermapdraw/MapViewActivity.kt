package com.example.warthundermapdraw

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

class MapViewActivity : AppCompatActivity() {

    private lateinit var mapName: String
    private lateinit var imageView: ImageView
    private lateinit var back: Button
    private lateinit var drawButton: Button
    private lateinit var eraseButton: Button
    private lateinit var saveButton: Button
    private lateinit var mapFrame: FrameLayout
    private lateinit var mapContent: FrameLayout
    private lateinit var clearAll: Button
    private lateinit var zoomButton: Button
    private lateinit var colorButtons: LinearLayout
    private lateinit var undoButton: Button
    private lateinit var redoButton: Button
    private lateinit var legendContainer: LinearLayout

    private var drawingView: DrawingView? = null
    private var isSavedMap = false
    private var isDrawingMode = false
    private var isZoomMode = false

    private var currentZoom = 1f
    private val minZoom = 1f
    private val maxZoom = 8f

    private var lastX = 0f
    private var lastY = 0f
    private var lastPinchDistance = 0f

    private val legendEntries = linkedMapOf<Int, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_map_view)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        mapName = intent.getStringExtra("MAP_NAME") ?: ""
        isSavedMap = mapName.startsWith("saved_")

        imageView = findViewById(R.id.mapImageView)
        back = findViewById(R.id.Back)
        drawButton = findViewById(R.id.draw)
        eraseButton = findViewById(R.id.Erser)
        saveButton = findViewById(R.id.Save)
        mapFrame = findViewById(R.id.mapFrame)
        mapContent = findViewById(R.id.mapContent)
        clearAll = findViewById(R.id.ClearAll)
        zoomButton = findViewById(R.id.Zoom)
        colorButtons = findViewById(R.id.colorButtons)
        undoButton = findViewById(R.id.Undo)
        redoButton = findViewById(R.id.Redo)
        legendContainer = findViewById(R.id.legendContainer)

        loadImage()

        mapContent.pivotX = 0f
        mapContent.pivotY = 0f

        drawingView = DrawingView(this)
        mapContent.addView(
            drawingView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        drawingView?.onColorUsed = { color ->
            addLegendColor(color)
        }

        drawingView?.onHistoryChanged = {
            updateHistoryButtons()
        }

        mapContent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateDrawingBounds()
        }

        imageView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateDrawingBounds()
        }

        mapContent.post {
            updateDrawingBounds()
        }

        loadLegend()
        updateLegend()
        updateHistoryButtons()

        val colorRed = findViewById<View>(R.id.colorRed)
        val colorGreen = findViewById<View>(R.id.colorGreen)
        val colorBlue = findViewById<View>(R.id.colorBlue)
        val colorYellow = findViewById<View>(R.id.colorYellow)
        val colorOrange = findViewById<View>(R.id.colorOrange)
        val colorPurple = findViewById<View>(R.id.colorPurple)
        val colorWhite = findViewById<View>(R.id.colorWhite)

        mapFrame.isClickable = true
        mapFrame.setOnTouchListener { _, event ->
            if (isZoomMode) {
                handleZoomTouch(event)
                true
            } else {
                false
            }
        }

        drawButton.setOnClickListener {
            isZoomMode = false
            isDrawingMode = true
            drawingView?.isDrawingEnabled = true
            drawingView?.setEraseMode(false)
            drawingView?.setColor(Color.BLUE)
            colorButtons.visibility = View.VISIBLE
            updateZoomButton()
        }

        eraseButton.setOnClickListener {
            if (isDrawingMode && !isZoomMode) {
                drawingView?.isDrawingEnabled = true
                drawingView?.setEraseMode(true)
                colorButtons.visibility = View.GONE
            } else {
                Toast.makeText(this, "Press Draw first", Toast.LENGTH_SHORT).show()
            }
        }

        colorRed.setOnClickListener { selectColor(Color.RED) }
        colorGreen.setOnClickListener { selectColor(Color.GREEN) }
        colorBlue.setOnClickListener { selectColor(Color.BLUE) }
        colorYellow.setOnClickListener { selectColor(Color.YELLOW) }
        colorOrange.setOnClickListener { selectColor(Color.rgb(255, 140, 0)) }
        colorPurple.setOnClickListener { selectColor(Color.rgb(160, 70, 220)) }
        colorWhite.setOnClickListener { selectColor(Color.WHITE) }

        undoButton.setOnClickListener {
            drawingView?.undo()
            updateHistoryButtons()
        }

        redoButton.setOnClickListener {
            drawingView?.redo()
            updateHistoryButtons()
        }

        clearAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear all drawings?")
                .setMessage("Are you sure you want to delete all drawings?")
                .setPositiveButton("Clear") { _, _ ->
                    drawingView?.clearDrawing()
                    updateHistoryButtons()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        zoomButton.setOnClickListener {
            isZoomMode = !isZoomMode
            drawingView?.isDrawingEnabled = isDrawingMode && !isZoomMode

            if (isZoomMode) {
                colorButtons.visibility = View.GONE
            }

            updateZoomButton()
        }

        saveButton.setOnClickListener {
            showSaveDialog()
        }

        back.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Confirm")
                .setMessage("Are you sure you want to go back to the main screen?")
                .setPositiveButton("Yes") { dialog, _ ->
                    dialog.dismiss()
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
                .setNegativeButton("No", null)
                .show()
        }
    }

    private fun updateDrawingBounds() {
        val drawable = imageView.drawable ?: return
        if (imageView.width <= 0 || imageView.height <= 0) return

        val bounds = RectF(
            0f,
            0f,
            drawable.intrinsicWidth.toFloat(),
            drawable.intrinsicHeight.toFloat()
        )

        imageView.imageMatrix.mapRect(bounds)

        drawingView?.setDrawingBounds(bounds)
    }

    private fun selectColor(color: Int) {
        drawingView?.setColor(color)
        drawingView?.setEraseMode(false)
        colorButtons.visibility = View.GONE
    }

    private fun updateHistoryButtons() {
        undoButton.isEnabled = drawingView?.canUndo() == true
        redoButton.isEnabled = drawingView?.canRedo() == true
        undoButton.alpha = if (undoButton.isEnabled) 1f else 0.4f
        redoButton.alpha = if (redoButton.isEnabled) 1f else 0.4f
    }

    private fun addLegendColor(color: Int) {
        if (!legendEntries.containsKey(color)) {
            legendEntries[color] = getColorName(color)
            updateLegend()
        }
    }

    private fun getColorName(color: Int): String {
        return when (color) {
            Color.RED -> "Red"
            Color.GREEN -> "Green"
            Color.BLUE -> "Blue"
            Color.YELLOW -> "Yellow"
            Color.WHITE -> "White"
            Color.rgb(255, 140, 0) -> "Orange"
            Color.rgb(160, 70, 220) -> "Purple"
            else -> "Color"
        }
    }

    private fun updateLegend() {
        legendContainer.removeAllViews()

        for ((color, label) in legendEntries) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(8, 4, 12, 4)
                background = getDrawable(R.drawable.color_popup_background)
                isClickable = true
                isFocusable = true
            }

            val colorView = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(color)
                    setStroke(1, Color.GRAY)
                }
            }

            item.addView(
                colorView,
                LinearLayout.LayoutParams(
                    (16 * resources.displayMetrics.density).toInt(),
                    (16 * resources.displayMetrics.density).toInt()
                )
            )

            val labelView = TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(Color.WHITE)
                setPadding(6, 0, 0, 0)
                maxLines = 1
            }

            item.addView(labelView)

            item.setOnClickListener {
                editLegendLabel(color)
            }

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            params.marginEnd = (6 * resources.displayMetrics.density).toInt()
            legendContainer.addView(item, params)
        }
    }

    private fun editLegendLabel(color: Int) {
        val input = EditText(this).apply {
            setText(legendEntries[color] ?: "")
            setSelection(text.length)
            hint = "Enter legend description"
        }

        AlertDialog.Builder(this)
            .setTitle("Edit legend")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val label = input.text.toString().trim()
                if (label.isNotEmpty()) {
                    legendEntries[color] = label
                    updateLegend()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadLegend() {
        legendEntries.clear()

        if (!isSavedMap) return

        val file = File(filesDir, "$mapName.legend")
        if (!file.exists()) return

        try {
            file.forEachLine { line ->
                val parts = line.split("|", limit = 2)
                if (parts.size == 2) {
                    val color = parts[0].toLongOrNull()?.toInt()
                    if (color != null) {
                        legendEntries[color] = parts[1]
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MapView", "Legend load failed", e)
        }
    }

    private fun saveLegend(fileName: String) {
        try {
            val file = File(filesDir, "$fileName.legend")
            file.writeText(
                legendEntries.entries.joinToString("\n") {
                    "${it.key}|${it.value.replace("\n", " ")}"
                }
            )
        } catch (e: Exception) {
            Log.e("MapView", "Legend save failed", e)
        }
    }

    private fun updateZoomButton() {
        zoomButton.text = if (isZoomMode) "Zoom: ON" else "Zoom"
    }

    private fun handleZoomTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                lastPinchDistance = 0f
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    lastPinchDistance = getPointerDistance(event)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val distance = getPointerDistance(event)

                    if (lastPinchDistance > 0f) {
                        val factor = distance / lastPinchDistance
                        val focusX = (event.getX(0) + event.getX(1)) / 2f
                        val focusY = (event.getY(0) + event.getY(1)) / 2f

                        val newZoom = (currentZoom * factor).coerceIn(minZoom, maxZoom)
                        val appliedFactor = newZoom / currentZoom

                        mapContent.translationX = focusX - (focusX - mapContent.translationX) * appliedFactor
                        mapContent.translationY = focusY - (focusY - mapContent.translationY) * appliedFactor

                        currentZoom = newZoom
                        applyMapTransform()
                    }

                    lastPinchDistance = distance
                } else {
                    val x = event.x
                    val y = event.y

                    mapContent.translationX += x - lastX
                    mapContent.translationY += y - lastY

                    lastX = x
                    lastY = y

                    applyMapTransform()
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val remainingIndex = if (event.actionIndex == 0) 1 else 0

                if (remainingIndex < event.pointerCount) {
                    lastX = event.getX(remainingIndex)
                    lastY = event.getY(remainingIndex)
                }

                lastPinchDistance = 0f
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastPinchDistance = 0f
            }
        }
    }

    private fun getPointerDistance(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private fun applyMapTransform() {
        mapContent.scaleX = currentZoom
        mapContent.scaleY = currentZoom

        val minX = mapContent.width * (1f - currentZoom)
        val minY = mapContent.height * (1f - currentZoom)

        mapContent.translationX = mapContent.translationX.coerceIn(minX, 0f)
        mapContent.translationY = mapContent.translationY.coerceIn(minY, 0f)
    }

    private fun loadImage() {
        if (isSavedMap) {
            val file = File(filesDir, "$mapName.png")
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                imageView.setImageBitmap(bitmap)
            } else {
                Log.e("MapView", "Saved file not found: $mapName.png")
            }
        } else {
            val resId = resources.getIdentifier(mapName, "drawable", packageName)
            if (resId != 0) {
                imageView.setImageResource(resId)
            } else {
                Log.e("MapView", "Drawable not found: $mapName")
            }
        }
    }

    private fun showSaveDialog() {
        val editText = EditText(this)

        AlertDialog.Builder(this)
            .setTitle("Save Drawing")
            .setMessage("Enter file name:")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val name = editText.text.toString()
                if (name.isNotBlank()) saveDrawing(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveDrawing(fileName: String) {
        val safeName = fileName.trim().replace("[^a-zA-Z0-9_]".toRegex(), "_")
        val drawable = imageView.drawable
        val drawing = drawingView

        if (drawable == null || drawing == null) {
            Toast.makeText(this, "Map could not be saved", Toast.LENGTH_SHORT).show()
            return
        }

        val originalWidth = drawable.intrinsicWidth
        val originalHeight = drawable.intrinsicHeight

        if (originalWidth <= 0 || originalHeight <= 0 || drawing.width <= 0 || drawing.height <= 0) {
            Toast.makeText(this, "Invalid map dimensions", Toast.LENGTH_SHORT).show()
            return
        }

        val bitmap = Bitmap.createBitmap(originalWidth, originalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        try {
            drawable.setBounds(0, 0, originalWidth, originalHeight)
            drawable.draw(canvas)

            val inverseMatrix = android.graphics.Matrix()
            if (!imageView.imageMatrix.invert(inverseMatrix)) {
                throw IllegalStateException("Unable to calculate map coordinates")
            }

            canvas.save()
            canvas.concat(inverseMatrix)
            drawing.draw(canvas)
            canvas.restore()

            val savedFileName = "saved_$safeName"
            val file = File(filesDir, "$savedFileName.png")

            FileOutputStream(file).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }

            saveLegend(savedFileName)

            Toast.makeText(this, "Saved as $savedFileName.png", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MapView", "Save failed", e)
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            bitmap.recycle()
        }
    }
}