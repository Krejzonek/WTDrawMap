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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

private data class TacticalPlan(
    val id: String,
    var name: String,
    val drawing: DrawingView
)

class MapViewActivity : AppCompatActivity() {

    private lateinit var mapName: String
    private var mapAssetPath = ""

    private lateinit var imageView: ImageView
    private lateinit var back: Button
    private lateinit var drawButton: Button
    private lateinit var eraseButton: Button
    private lateinit var saveButton: Button
    private lateinit var view3D: Button
    private lateinit var mapFrame: FrameLayout
    private lateinit var mapContent: FrameLayout
    private lateinit var clearAll: Button
    private lateinit var zoomButton: Button
    private lateinit var colorButtons: LinearLayout
    private lateinit var undoButton: Button
    private lateinit var redoButton: Button
    private lateinit var legendContainer: LinearLayout
    private lateinit var tacticSpinner: Spinner
    private lateinit var addTacticButton: Button
    private lateinit var renameTacticButton: Button
    private lateinit var deleteTacticButton: Button
    private lateinit var map3DView: Map3DView
    private lateinit var tacticControls: LinearLayout
    private lateinit var legendBar: LinearLayout
    private lateinit var drawingControls: LinearLayout

    private var is3DMode = false
    private val tactics = mutableListOf<TacticalPlan>()
    private val legendEntries = linkedMapOf<Int, String>()

    private var activeTacticIndex = 0
    private var isSavedMap = false
    private var isDrawingMode = false
    private var isZoomMode = false
    private var isChangingSpinner = false

    private var currentZoom = 1f
    private val minZoom = 1f
    private val maxZoom = 8f

    private var lastX = 0f
    private var lastY = 0f
    private var lastPinchDistance = 0f

    private val activeDrawing: DrawingView?
        get() = tactics.getOrNull(activeTacticIndex)?.drawing

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
        mapAssetPath = intent.getStringExtra("MAP_ASSET_PATH") ?: ""
        isSavedMap = mapName.startsWith("saved_")

        imageView = findViewById(R.id.mapImageView)
        back = findViewById(R.id.Back)
        drawButton = findViewById(R.id.draw)
        eraseButton = findViewById(R.id.Erser)
        saveButton = findViewById(R.id.Save)
        view3D = findViewById(R.id.View3D)
        mapFrame = findViewById(R.id.mapFrame)
        mapContent = findViewById(R.id.mapContent)
        clearAll = findViewById(R.id.ClearAll)
        zoomButton = findViewById(R.id.Zoom)
        colorButtons = findViewById(R.id.colorButtons)
        undoButton = findViewById(R.id.Undo)
        redoButton = findViewById(R.id.Redo)
        legendContainer = findViewById(R.id.legendContainer)
        tacticSpinner = findViewById(R.id.tacticSpinner)
        addTacticButton = findViewById(R.id.AddTactic)
        renameTacticButton = findViewById(R.id.RenameTactic)
        deleteTacticButton = findViewById(R.id.DeleteTactic)
        tacticControls = findViewById(R.id.tacticControls)
        legendBar = findViewById(R.id.legendBar)
        drawingControls = findViewById(R.id.drawingControls)

        map3DView = Map3DView(this)
        map3DView.visibility = View.GONE

        mapFrame.addView(
            map3DView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        loadImage()

        view3D.setOnClickListener {
            if (is3DMode) {
                switchTo2D()
            } else {
                switchTo3D()
            }
        }

        mapContent.pivotX = 0f
        mapContent.pivotY = 0f

        loadLegend()
        loadTactics()

        mapContent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateDrawingBounds()
        }

        imageView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateDrawingBounds()
        }

        mapContent.post {
            updateDrawingBounds()
            loadTacticDrawings()
        }

        setupColorButtons()
        setupTacticControls()

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
            activeDrawing?.isDrawingEnabled = true
            activeDrawing?.setEraseMode(false)
            activeDrawing?.setColor(Color.BLUE)
            colorButtons.visibility = View.VISIBLE
            updateZoomButton()
        }

        eraseButton.setOnClickListener {
            if (isDrawingMode && !isZoomMode) {
                activeDrawing?.isDrawingEnabled = true
                activeDrawing?.setEraseMode(true)
                colorButtons.visibility = View.GONE
            } else {
                Toast.makeText(this, "Press Draw first", Toast.LENGTH_SHORT).show()
            }
        }

        undoButton.setOnClickListener {
            activeDrawing?.undo()
            updateHistoryButtons()
        }

        redoButton.setOnClickListener {
            activeDrawing?.redo()
            updateHistoryButtons()
        }

        clearAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear this tactic?")
                .setMessage("Are you sure you want to delete all drawings from this tactic?")
                .setPositiveButton("Clear") { _, _ ->
                    activeDrawing?.clearDrawing()
                    updateHistoryButtons()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        zoomButton.setOnClickListener {
            isZoomMode = !isZoomMode
            activeDrawing?.isDrawingEnabled = isDrawingMode && !isZoomMode

            if (isZoomMode) {
                colorButtons.visibility = View.GONE
            }

            updateZoomButton()
        }

        saveButton.setOnClickListener {
            if (isSavedMap) {
                saveDrawing(mapName)
            } else {
                showSaveDialog()
            }
        }

        back.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Confirm")
                .setMessage("Are you sure you want to go back to the main screen?")
                .setPositiveButton("Yes") { dialog, _ ->
                    dialog.dismiss()
                    finish()
                }
                .setNegativeButton("No", null)
                .show()
        }

        updateLegend()
        updateHistoryButtons()
    }

    private fun setupColorButtons() {
        findViewById<View>(R.id.colorRed).setOnClickListener { selectColor(Color.RED) }
        findViewById<View>(R.id.colorGreen).setOnClickListener { selectColor(Color.GREEN) }
        findViewById<View>(R.id.colorBlue).setOnClickListener { selectColor(Color.BLUE) }
        findViewById<View>(R.id.colorYellow).setOnClickListener { selectColor(Color.YELLOW) }
        findViewById<View>(R.id.colorOrange).setOnClickListener { selectColor(Color.rgb(255, 140, 0)) }
        findViewById<View>(R.id.colorPurple).setOnClickListener { selectColor(Color.rgb(160, 70, 220)) }
        findViewById<View>(R.id.colorWhite).setOnClickListener { selectColor(Color.WHITE) }
    }

    private fun setupTacticControls() {
        addTacticButton.setOnClickListener {
            showTacticDialog()
        }

        renameTacticButton.setOnClickListener {
            val tactic = tactics.getOrNull(activeTacticIndex) ?: return@setOnClickListener
            showTacticDialog(tactic.name)
        }

        deleteTacticButton.setOnClickListener {
            deleteActiveTactic()
        }

        tacticSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isChangingSpinner || position !in tactics.indices) return
                selectTactic(position)
            }
        }

        updateTacticSpinner()
    }

    private fun switchTo3D() {
        if (is3DMode) return

        activeDrawing?.isDrawingEnabled = false

        mapContent.visibility = View.GONE
        colorButtons.visibility = View.GONE

        tacticControls.visibility = View.GONE
        legendBar.visibility = View.GONE
        drawingControls.visibility = View.GONE

        map3DView.visibility = View.VISIBLE

        val sourceMapName = if (isSavedMap) {
            val sourceFile = File(filesDir, "$mapName.source")

            if (sourceFile.exists()) {
                sourceFile.readText().trim()
            } else {
                mapName
            }
        } else {
            mapName
        }

        map3DView.post {
            if (is3DMode) return@post
            map3DView.onResumeRendering()
        }

        view3D.text = "2D"
        is3DMode = true
    }

    private fun switchTo2D() {
        if (!is3DMode) return

        map3DView.onPauseRendering()
        map3DView.visibility = View.GONE

        mapContent.visibility = View.VISIBLE

        tacticControls.visibility = View.VISIBLE
        legendBar.visibility = View.VISIBLE
        drawingControls.visibility = View.VISIBLE

        activeDrawing?.isDrawingEnabled = isDrawingMode && !isZoomMode

        colorButtons.visibility = if (isDrawingMode && !isZoomMode) {
            View.VISIBLE
        } else {
            View.GONE
        }

        view3D.text = "3D"
        is3DMode = false

        updateHistoryButtons()
    }

    private fun createTactic(id: String, name: String): TacticalPlan {
        val drawing = DrawingView(this)

        drawing.isDrawingEnabled = false
        drawing.onColorUsed = { color ->
            addLegendColor(color)
        }
        drawing.onHistoryChanged = {
            if (activeDrawing === drawing) updateHistoryButtons()
        }

        mapContent.addView(
            drawing,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        drawing.setDrawingBounds(getCurrentDrawingBounds())

        return TacticalPlan(id, name, drawing)
    }

    private fun loadTactics() {
        tactics.clear()

        if (isSavedMap) {
            val file = File(filesDir, "$mapName.tactics.json")

            if (file.exists()) {
                try {
                    val json = JSONObject(file.readText())
                    val array = json.getJSONArray("tactics")

                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        tactics.add(
                            createTactic(
                                item.getString("id"),
                                item.getString("name")
                            )
                        )
                    }

                    activeTacticIndex = json.optInt("active", 0)

                    val requestedTacticId = intent.getStringExtra("ACTIVE_TACTIC_ID")

                    if (!requestedTacticId.isNullOrBlank()) {
                        val requestedIndex = tactics.indexOfFirst {
                            it.id == requestedTacticId
                        }

                        if (requestedIndex >= 0) {
                            activeTacticIndex = requestedIndex
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MapView", "Tactics load failed", e)
                }
            }
        }

        if (tactics.isEmpty()) {
            tactics.add(createTactic("t0", "Tactic 1"))
            activeTacticIndex = 0
        }

        activeTacticIndex = activeTacticIndex.coerceIn(0, tactics.lastIndex)

        tactics.forEachIndexed { index, tactic ->
            tactic.drawing.visibility = if (index == activeTacticIndex) View.VISIBLE else View.GONE
            tactic.drawing.isDrawingEnabled = false
        }

        updateTacticSpinner()
    }

    private fun loadTacticDrawings() {
        if (!isSavedMap) return

        for (tactic in tactics) {
            val vectorFile = File(filesDir, "$mapName.${tactic.id}.json")

            if (vectorFile.exists()) {
                try {
                    tactic.drawing.importActions(
                        JSONArray(vectorFile.readText())
                    )
                } catch (e: Exception) {
                    Log.e("MapView", "Vector drawing load failed", e)
                }
            } else {
                val file = File(filesDir, "$mapName.${tactic.id}.png")
                if (!file.exists()) continue

                val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: continue
                tactic.drawing.loadBitmap(bitmap)
            }

            tactic.drawing.setDrawingBounds(getCurrentDrawingBounds())
        }

        updateHistoryButtons()
    }

    private fun updateTacticSpinner() {
        if (!::tacticSpinner.isInitialized) return

        isChangingSpinner = true

        val adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            tactics.map { it.name }
        ) {
            private fun createTextView(text: String): TextView {
                return TextView(this@MapViewActivity).apply {
                    this.text = text
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(
                        (12 * resources.displayMetrics.density).toInt(),
                        0,
                        (8 * resources.displayMetrics.density).toInt(),
                        0
                    )
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(getColor(R.color.dark_button))
                        cornerRadius = 6 * resources.displayMetrics.density
                    }
                    minHeight = (40 * resources.displayMetrics.density).toInt()
                }
            }

            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                return createTextView(getItem(position) ?: "")
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                return createTextView(getItem(position) ?: "").apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(getColor(R.color.dark_button))
                    }
                }
            }
        }

        tacticSpinner.adapter = adapter

        if (tactics.isNotEmpty()) {
            tacticSpinner.setSelection(activeTacticIndex, false)
        }

        isChangingSpinner = false
    }

    private fun selectTactic(index: Int) {
        if (index !in tactics.indices) return

        tactics.forEach {
            it.drawing.isDrawingEnabled = false
            it.drawing.visibility = View.GONE
        }

        activeTacticIndex = index

        val drawing = tactics[index].drawing
        drawing.visibility = View.VISIBLE
        drawing.isDrawingEnabled = isDrawingMode && !isZoomMode
        drawing.setDrawingBounds(getCurrentDrawingBounds())

        colorButtons.visibility = if (isDrawingMode && !isZoomMode) View.VISIBLE else View.GONE

        updateHistoryButtons()
    }

    private fun showTacticDialog(oldName: String? = null) {
        val input = EditText(this).apply {
            hint = "Tactic name"
            if (oldName != null) setText(oldName)
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle(if (oldName == null) "New tactic" else "Rename tactic")
            .setView(input)
            .setPositiveButton(if (oldName == null) "Create" else "Save") { _, _ ->
                val name = input.text.toString().trim()

                if (name.isEmpty()) {
                    Toast.makeText(this, "Enter a tactic name", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (oldName == null) {
                    val id = "t${System.currentTimeMillis()}"
                    tactics.add(createTactic(id, name))
                    activeTacticIndex = tactics.lastIndex
                    selectTactic(activeTacticIndex)
                    updateTacticSpinner()
                } else {
                    tactics.getOrNull(activeTacticIndex)?.name = name
                    updateTacticSpinner()
                }

                if (isSavedMap) {
                    saveTacticMetadata()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteActiveTactic() {
        if (tactics.size <= 1) {
            Toast.makeText(this, "At least one tactic is required", Toast.LENGTH_SHORT).show()
            return
        }

        val tactic = tactics.getOrNull(activeTacticIndex) ?: return

        AlertDialog.Builder(this)
            .setTitle("Delete tactic?")
            .setMessage("Delete \"${tactic.name}\"?")
            .setPositiveButton("Delete") { _, _ ->
                mapContent.removeView(tactic.drawing)
                tactics.removeAt(activeTacticIndex)

                File(filesDir, "$mapName.${tactic.id}.png").delete()
                File(filesDir, "$mapName.${tactic.id}.json").delete()
                File(filesDir, "$mapName.${tactic.id}.bin").delete()

                activeTacticIndex = activeTacticIndex.coerceAtMost(tactics.lastIndex)

                tactics.forEachIndexed { index, item ->
                    item.drawing.visibility = if (index == activeTacticIndex) View.VISIBLE else View.GONE
                    item.drawing.isDrawingEnabled = false
                }

                updateTacticSpinner()
                selectTactic(activeTacticIndex)
                saveTacticMetadata()
                updateHistoryButtons()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateDrawingBounds() {
        val bounds = getCurrentDrawingBounds()
        tactics.forEach {
            it.drawing.setDrawingBounds(bounds)
        }
    }

    private fun getCurrentDrawingBounds(): RectF {
        val drawable = imageView.drawable ?: return RectF()
        if (imageView.width <= 0 || imageView.height <= 0) return RectF()

        val bounds = RectF(
            0f,
            0f,
            drawable.intrinsicWidth.toFloat(),
            drawable.intrinsicHeight.toFloat()
        )

        imageView.imageMatrix.mapRect(bounds)
        return bounds
    }

    private fun selectColor(color: Int) {
        activeDrawing?.setColor(color)
        activeDrawing?.setEraseMode(false)
        colorButtons.visibility = View.GONE
    }

    private fun updateHistoryButtons() {
        undoButton.isEnabled = activeDrawing?.canUndo() == true
        redoButton.isEnabled = activeDrawing?.canRedo() == true
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

            val size = (16 * resources.displayMetrics.density).toInt()

            item.addView(
                colorView,
                LinearLayout.LayoutParams(size, size)
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

    private fun saveTacticMetadata(fileName: String = mapName) {
        try {
            val json = JSONObject()
            val array = JSONArray()

            tactics.forEach {
                array.put(
                    JSONObject().apply {
                        put("id", it.id)
                        put("name", it.name)
                    }
                )
            }

            json.put("tactics", array)
            json.put("active", activeTacticIndex)

            File(filesDir, "$fileName.tactics.json").writeText(json.toString())
        } catch (e: Exception) {
            Log.e("MapView", "Tactics metadata save failed", e)
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
            val baseFile = File(filesDir, "$mapName.base.png")
            val sourceFile = File(filesDir, "$mapName.source")
            val mapFile = File(filesDir, "$mapName.png")

            when {
                baseFile.exists() -> {
                    val bitmap = BitmapFactory.decodeFile(baseFile.absolutePath)
                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap)
                    } else {
                        Log.e("MapView", "Could not decode base image: ${baseFile.absolutePath}")
                    }
                }

                sourceFile.exists() -> {
                    val sourceName = sourceFile.readText().trim()
                    var bitmap: Bitmap? = null

                    try {
                        bitmap = assets.open(sourceName).use {
                            BitmapFactory.decodeStream(it)
                        }
                    } catch (e: Exception) {
                        Log.w("MapView", "Asset source not found: $sourceName", e)
                    }

                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap)
                    } else {
                        val resId = resources.getIdentifier(sourceName, "drawable", packageName)

                        if (resId != 0) {
                            imageView.setImageResource(resId)
                        } else if (mapFile.exists()) {
                            val fallbackBitmap = BitmapFactory.decodeFile(mapFile.absolutePath)

                            if (fallbackBitmap != null) {
                                imageView.setImageBitmap(fallbackBitmap)
                            } else {
                                Log.e("MapView", "Could not decode saved image: ${mapFile.absolutePath}")
                            }
                        } else {
                            Log.e("MapView", "Source image not found: $sourceName")
                        }
                    }
                }

                mapFile.exists() -> {
                    val bitmap = BitmapFactory.decodeFile(mapFile.absolutePath)

                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap)
                    } else {
                        Log.e("MapView", "Could not decode saved image: ${mapFile.absolutePath}")
                    }
                }

                else -> Log.e("MapView", "Saved file not found: $mapName")
            }
        } else {
            if (mapAssetPath.isNotBlank()) {
                try {
                    val bitmap = assets.open(mapAssetPath).use {
                        BitmapFactory.decodeStream(it)
                    }

                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap)
                    } else {
                        Log.e("MapView", "Could not decode map asset: $mapAssetPath")
                    }
                } catch (e: Exception) {
                    Log.e("MapView", "Map asset not found: $mapAssetPath", e)
                }
            } else {
                val resId = resources.getIdentifier(mapName, "drawable", packageName)

                if (resId != 0) {
                    imageView.setImageResource(resId)
                } else {
                    Log.e("MapView", "Map asset path and drawable not found: $mapName")
                }
            }
        }
    }

    private fun showSaveDialog() {
        val editText = EditText(this).apply {
            hint = "Map name"
        }

        AlertDialog.Builder(this)
            .setTitle("Save Drawing")
            .setMessage("Enter map name:")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val name = editText.text.toString().trim()

                if (name.isNotBlank()) {
                    saveDrawing(name)
                } else {
                    Toast.makeText(this, "Enter a map name", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveDrawing(fileName: String) {
        val safeName = fileName.trim()
            .replace(Regex("[^a-zA-Z0-9_]+"), "_")
            .trim('_')
            .ifBlank { "Map" }

        val savedFileName = if (isSavedMap) {
            mapName
        } else {
            "saved_$safeName"
        }

        val drawable = imageView.drawable

        if (drawable == null || mapContent.width <= 0 || mapContent.height <= 0) {
            Toast.makeText(this, "Map could not be saved", Toast.LENGTH_SHORT).show()
            return
        }

        val originalWidth = drawable.intrinsicWidth
        val originalHeight = drawable.intrinsicHeight

        if (originalWidth <= 0 || originalHeight <= 0) {
            Toast.makeText(this, "Invalid map dimensions", Toast.LENGTH_SHORT).show()
            return
        }

        val baseBitmap = Bitmap.createBitmap(
            originalWidth,
            originalHeight,
            Bitmap.Config.ARGB_8888
        )

        try {
            val baseCanvas = Canvas(baseBitmap)
            drawable.setBounds(0, 0, originalWidth, originalHeight)
            drawable.draw(baseCanvas)

            FileOutputStream(File(filesDir, "$savedFileName.base.png")).use {
                baseBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }

            if (!isSavedMap) {
                File(filesDir, "$savedFileName.source").writeText(mapAssetPath)
            } else {
                val sourceFile = File(filesDir, "$mapName.source")

                if (sourceFile.exists()) {
                    File(filesDir, "$savedFileName.source").writeText(sourceFile.readText())
                } else {
                    File(filesDir, "$savedFileName.source").delete()
                }
            }

            val preview = baseBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val previewCanvas = Canvas(preview)

            for (tactic in tactics) {
                val vectorFile = File(filesDir, "$savedFileName.${tactic.id}.json")
                vectorFile.writeText(tactic.drawing.exportActions().toString())

                val overlay = Bitmap.createBitmap(
                    mapContent.width,
                    mapContent.height,
                    Bitmap.Config.ARGB_8888
                )

                tactic.drawing.draw(Canvas(overlay))

                val inverseMatrix = android.graphics.Matrix()

                if (!imageView.imageMatrix.invert(inverseMatrix)) {
                    overlay.recycle()
                    throw IllegalStateException("Unable to calculate map coordinates")
                }

                val originalOverlay = Bitmap.createBitmap(
                    originalWidth,
                    originalHeight,
                    Bitmap.Config.ARGB_8888
                )

                val overlayCanvas = Canvas(originalOverlay)
                overlayCanvas.concat(inverseMatrix)
                overlayCanvas.drawBitmap(overlay, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))

                FileOutputStream(File(filesDir, "$savedFileName.${tactic.id}.png")).use {
                    originalOverlay.compress(Bitmap.CompressFormat.PNG, 100, it)
                }

                if (tactic === tactics.getOrNull(activeTacticIndex)) {
                    previewCanvas.drawBitmap(originalOverlay, 0f, 0f, null)
                }

                overlay.recycle()
                originalOverlay.recycle()
            }

            FileOutputStream(File(filesDir, "$savedFileName.png")).use {
                preview.compress(Bitmap.CompressFormat.PNG, 100, it)
            }

            preview.recycle()

            saveLegend(savedFileName)
            saveTacticMetadata(savedFileName)

            mapName = savedFileName
            isSavedMap = true

            Toast.makeText(
                this,
                "Map saved successfully",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Log.e("MapView", "Save failed", e)
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            baseBitmap.recycle()
        }
    }

    override fun onResume() {
        super.onResume()

        if (is3DMode) {
            map3DView.onResumeRendering()
        }
    }

    override fun onPause() {
        if (::map3DView.isInitialized) {
            map3DView.onPauseRendering()
        }

        super.onPause()
    }

    override fun onDestroy() {
        if (::map3DView.isInitialized) {
            map3DView.release()
        }

        super.onDestroy()
    }
}