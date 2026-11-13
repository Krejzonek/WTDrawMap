package com.example.warthundermapdraw

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WarThunderScanner"

        private const val UPDATE_INTERVAL = 200L

        private const val MIN_ZOOM = 1f
        private const val MAX_ZOOM = 5f
    }

    private lateinit var image: ImageView
    private lateinit var status: TextView
    private lateinit var searchMapHint: TextView

    private lateinit var mapContainer: FrameLayout
    private lateinit var mapLegend: LinearLayout
    private lateinit var mapLegendScroll: android.widget.HorizontalScrollView

    private lateinit var changeDeviceButton: TextView

    private lateinit var apiClient: WarThunderApiClient
    private lateinit var scaleDetector: ScaleGestureDetector

    private lateinit var drawingManager: MapDrawingManager
    private lateinit var mapOverlay: MapOverlayView
    private lateinit var drawingToolbar: MapDrawingToolbar

    private var currentBitmap: Bitmap? = null

    private var hasLoadedImage = false
    private var lastGeneration = -1
    private var lastHudType = -1

    private var currentMapData = MapData(
        emptyList(),
        emptyList(),
        false
    )

    private var zoom = 1f
    private var translateX = 0f
    private var translateY = 0f

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    private var updateTask: ScheduledFuture<*>? = null

    @Volatile
    private var isDiscovering = false

    @Volatile
    private var isLoading = false

    private var deviceSelectionDialog: AlertDialog? = null
    private var deviceSelectionDialogShowing = false

    private val createPngDocument =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("image/png")
        ) { uri: Uri? ->
            if (uri != null) {
                exportTacticalMap(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(
            window,
            false
        )

        WindowInsetsControllerCompat(
            window,
            window.decorView
        ).let {
            it.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContentView(R.layout.activity_main)

        findViewById<View>(android.R.id.content)
            .setOnApplyWindowInsetsListener { view, insets ->

                val systemBars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                )

                view.setPadding(
                    systemBars.left,
                    systemBars.top,
                    systemBars.right,
                    systemBars.bottom
                )

                insets
            }

        initializeViews()
        initializeMap()
        initializeDrawing()
        initializeGestures()
        initializeApi()

        updateStatus("Searching for War Thunder...")
        updateMapHint("Waiting for War Thunder...")

        startSearching()
    }

    private fun initializeViews() {
        image = findViewById(R.id.Map)
        status = findViewById(R.id.ConnectionStatus)
        searchMapHint = findViewById(R.id.SearchMapHint)

        mapLegend = findViewById(R.id.MapLegend)
        mapLegendScroll = findViewById(R.id.MapLegendScroll)

        changeDeviceButton =
            findViewById(R.id.ChangeDeviceButton)

        changeDeviceButton.setOnClickListener {
            changeDevice()
        }

        mapContainer = image.parent as? FrameLayout
            ?: throw IllegalStateException(
                "Map ImageView must be inside a FrameLayout"
            )

        image.scaleType = ImageView.ScaleType.FIT_CENTER
        image.adjustViewBounds = false
        image.pivotX = 0f
        image.pivotY = 0f
    }

    private fun initializeMap() {
        drawingManager = MapDrawingManager(
            resources.displayMetrics.density
        )

        mapOverlay = MapOverlayView(
            this,
            image,
            { currentBitmap },
            drawingManager
        )

        mapOverlay.pivotX = 0f
        mapOverlay.pivotY = 0f
        mapOverlay.isClickable = false
        mapOverlay.isFocusable = false

        mapContainer.addView(
            mapOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun initializeDrawing() {
        drawingToolbar = MapDrawingToolbar(
            context = this,
            container = mapContainer,
            drawingManager = drawingManager,
            onInvalidate = {
                mapOverlay.invalidate()
            },
            onExport = {
                if (currentBitmap != null) {
                    createPngDocument.launch(
                        "war_thunder_tactical_map.png"
                    )
                }
            }
        )
    }

    private fun initializeGestures() {
        scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

                override fun onScale(
                    detector: ScaleGestureDetector
                ): Boolean {
                    if (
                        drawingManager.activeTool !=
                        MapDrawingManager.Tool.NONE
                    ) {
                        return false
                    }

                    val oldZoom = zoom

                    zoom = (
                            zoom * detector.scaleFactor
                            ).coerceIn(
                            MIN_ZOOM,
                            MAX_ZOOM
                        )

                    val factor = zoom / oldZoom

                    translateX =
                        detector.focusX -
                                (detector.focusX - translateX) * factor

                    translateY =
                        detector.focusY -
                                (detector.focusY - translateY) * factor

                    applyMapTransform()

                    return true
                }
            }
        )

        mapContainer.setOnTouchListener { _, event ->
            if (
                drawingManager.activeTool !=
                MapDrawingManager.Tool.NONE
            ) {
                handleDrawingTouch(event)
            } else {
                handleTouch(event)
            }
        }
    }

    private fun initializeApi() {
        apiClient = WarThunderApiClient(
            applicationContext
        )
    }

    private fun handleDrawingTouch(
        event: MotionEvent
    ): Boolean {
        val mapRect = mapOverlay.getMapRect() ?: return true

        val x = (event.x - translateX) / zoom
        val y = (event.y - translateY) / zoom

        val insideMap = mapRect.contains(x, y)

        val normalizedX =
            ((x - mapRect.left) / mapRect.width())
                .coerceIn(0f, 1f)

        val normalizedY =
            ((y - mapRect.top) / mapRect.height())
                .coerceIn(0f, 1f)

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                if (!insideMap) return true

                drawingManager.startStroke(
                    normalizedX,
                    normalizedY
                )

                mapOverlay.invalidate()
                drawingToolbar.updateButtons()
            }

            MotionEvent.ACTION_MOVE -> {
                if (insideMap) {
                    drawingManager.addPoint(
                        normalizedX,
                        normalizedY
                    )

                    mapOverlay.invalidate()
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                drawingManager.finishStroke()

                drawingToolbar.updateButtons()
                mapOverlay.invalidate()
            }
        }

        return true
    }

    private fun exportTacticalMap(uri: Uri) {
        val bitmap = currentBitmap ?: run {
            updateStatus("No map to export")
            return
        }

        val success = TacticalMapExporter.export(
            contentResolver,
            uri,
            bitmap,
            mapOverlay
        )

        updateStatus(
            if (success) {
                "Map exported successfully"
            } else {
                "Map export failed"
            }
        )
    }

    private fun startSearching() {
        updateTask?.cancel(false)

        updateTask = scheduler.scheduleWithFixedDelay(
            {
                try {
                    if (apiClient.getServerAddress() == null) {
                        discoverServer()
                    } else {
                        loadMap()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Update loop error", e)
                }
            },
            0,
            UPDATE_INTERVAL,
            TimeUnit.MILLISECONDS
        )
    }

    private fun discoverServer() {
        if (isDiscovering) return
        if (deviceSelectionDialogShowing) return

        isDiscovering = true

        Log.d(TAG, "Starting device discovery")

        apiClient.discover { devices ->
            runOnUiThread {
                isDiscovering = false

                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }

                Log.i(
                    TAG,
                    "Discovery finished. Devices found: ${devices.size}"
                )

                devices.forEach {
                    Log.i(
                        TAG,
                        "Found device: ${it.name} - ${it.address}"
                    )
                }

                if (devices.isEmpty()) {
                    updateStatus(
                        "Searching for War Thunder..."
                    )
                } else {
                    showDeviceSelectionDialog(devices)
                }
            }
        }
    }

    private fun showDeviceSelectionDialog(
        devices: List<WarThunderApiClient.ServerDevice>
    ) {
        if (isFinishing || isDestroyed || devices.isEmpty()) {
            return
        }

        deviceSelectionDialog?.dismiss()
        deviceSelectionDialogShowing = true

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }

        devices.forEachIndexed { index, device ->

            Log.i(
                TAG,
                "Dialog device $index: name='${device.name}', address='${device.address}'"
            )

            val button = TextView(this).apply {
                text = "${device.name}\n${device.address}"
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(16, 16, 16, 16)

                setBackgroundResource(
                    android.R.drawable.list_selector_background
                )

                isClickable = true
                isFocusable = true

                setOnClickListener {
                    apiClient.selectServer(device.address)

                    deviceSelectionDialog?.dismiss()

                    clearMap()

                    updateStatus(
                        "Connected: ${device.name}"
                    )

                    updateMapHint(
                        "Waiting for a battle..."
                    )
                }
            }

            layout.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            if (index < devices.lastIndex) {
                val divider = View(this).apply {
                    setBackgroundColor(Color.DKGRAY)
                }

                layout.addView(
                    divider,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1
                    )
                )
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Select War Thunder device")
            .setView(layout)
            .setCancelable(false)
            .setNegativeButton("CANCEL", null)
            .create()

        dialog.setOnDismissListener {
            deviceSelectionDialogShowing = false
            deviceSelectionDialog = null
        }

        deviceSelectionDialog = dialog
        dialog.show()
    }

    private fun changeDevice() {
        deviceSelectionDialog?.dismiss()
        deviceSelectionDialog = null
        deviceSelectionDialogShowing = false

        apiClient.disconnect()

        isLoading = false
        isDiscovering = false

        clearMap()

        updateStatus("Searching for War Thunder...")
        updateMapHint("Searching for War Thunder...")

        discoverServer()
    }

    private fun loadMap() {
        if (isLoading) return

        isLoading = true

        apiClient.loadMap { data ->
            runOnUiThread {
                isLoading = false

                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }

                if (
                    data != null &&
                    data.address != apiClient.getServerAddress()
                ) {
                    return@runOnUiThread
                }

                if (data == null) {
                    if (apiClient.getServerAddress() == null) {
                        updateStatus(
                            "Connection lost. Searching again..."
                        )

                        updateMapHint(
                            "Searching for War Thunder..."
                        )

                        updateLegend(
                            MapData(
                                emptyList(),
                                emptyList(),
                                false
                            )
                        )
                    }

                    return@runOnUiThread
                }

                if (!data.info.optBoolean("valid", false)) {
                    clearMap()

                    updateMapHint("Waiting for a battle...")

                    updateStatus(
                        "Connected: ${getSelectedDeviceName(data.address)}"
                    )

                    return@runOnUiThread
                }

                val generation = data.info.optInt("map_generation", -1)

                val hudType = data.info.optInt("hud_type", -1)

                val newDrawingKey = drawingManager.createMapKey(
                    data.address,
                    data.info
                )

                if (drawingManager.switchLayer(newDrawingKey)) {
                    mapOverlay.invalidate()
                    drawingToolbar.updateButtons()
                }

                if (data.image != null) {
                    val shouldUpdateImage =
                        !hasLoadedImage ||
                                generation != lastGeneration ||
                                hudType != lastHudType

                    if (shouldUpdateImage) {
                        val bitmap = BitmapFactory.decodeByteArray(
                            data.image,
                            0,
                            data.image.size
                        )

                        if (bitmap != null) {
                            val previousBitmap = currentBitmap

                            currentBitmap = bitmap
                            image.setImageBitmap(bitmap)

                            hasLoadedImage = true

                            drawingToolbar.setExportVisible(true)

                            lastGeneration = generation
                            lastHudType = hudType

                            resetMapTransform()

                            image.post {
                                applyMapTransform()
                                mapOverlay.invalidate()
                            }

                            if (
                                previousBitmap != null &&
                                previousBitmap !== bitmap &&
                                !previousBitmap.isRecycled
                            ) {
                                previousBitmap.recycle()
                            }

                            Log.i(
                                TAG,
                                "Map image loaded: ${bitmap.width}x${bitmap.height}, key=${drawingManager.currentKey}"
                            )
                        }
                    }
                }

                updateMapHint(
                    if (currentBitmap != null) {
                        ""
                    } else {
                        "Loading map..."
                    }
                )

                val newMapData =
                    WarThunderMapParser.parse(data.objects)

                currentMapData = newMapData

                mapOverlay.setMapData(currentMapData)

                updateLegend(currentMapData)

                drawingToolbar.updateButtons()

                mapOverlay.invalidate()

                updateStatus(
                    "Connected: ${getSelectedDeviceName(data.address)}"
                )
            }
        }
    }

    private fun getSelectedDeviceName(
        address: String
    ): String {
        return address
            .removePrefix("http://")
            .substringBefore(":")
    }

    private fun updateLegend(data: MapData) {
        mapLegend.removeAllViews()

        val visibleUnits = data.markers
            .filter {
                when {
                    data.isAirView ->
                        it.type == MarkerType.AIR_UNIT

                    else ->
                        it.type == MarkerType.GROUND_UNIT
                }
            }
            .distinctBy {
                it.iconName
            }

        if (visibleUnits.isEmpty()) {
            mapLegendScroll.visibility = View.GONE
            return
        }

        mapLegendScroll.visibility = View.VISIBLE

        val labels = mapOf(
            "LightTank" to "Light Tank",
            "MediumTank" to "Medium Tank",
            "HeavyTank" to "Heavy Tank",
            "TankDestroyer" to "Tank Destroyer",
            "SPAA" to "SPAA",
            "Fighter" to "Fighter",
            "Assault" to "Assault Aircraft",
            "Bomber" to "Bomber",
            "Helicopter" to "Helicopter",
            "Ship" to "Ship",
            "TorpedoBoat" to "Torpedo Boat",
            "Frigate" to "Frigate",
            "Gunboat" to "Gunboat",
            "Destroyer" to "Destroyer",
            "Cruiser" to "Cruiser",
            "LightCruiser" to "Light Cruiser",
            "HeavyCruiser" to "Heavy Cruiser",
            "Battleship" to "Battleship",
            "Battlecruiser" to "Battlecruiser"
        )

        val density = resources.displayMetrics.density

        for (unit in visibleUnits) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                setPadding(
                    (8f * density).toInt(),
                    0,
                    (12f * density).toInt(),
                    0
                )
            }

            val icon = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (19f * density).toInt(),
                    (19f * density).toInt()
                )

                setImageResource(
                    MapIconProvider.getIconRes(unit.iconName)
                )

                setColorFilter(unit.color)

                scaleType = ImageView.ScaleType.FIT_CENTER

                contentDescription =
                    labels[unit.iconName] ?: unit.iconName
            }

            val name = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = (5f * density).toInt()
                }

                text = labels[unit.iconName] ?: unit.iconName
                setTextColor(Color.rgb(220, 232, 245))
                textSize = 10f
                maxLines = 1

                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )
            }

            item.addView(icon)
            item.addView(name)

            mapLegend.addView(item)
        }
    }

    private fun clearMap() {
        drawingManager.clearAll()

        val previousBitmap = currentBitmap

        currentBitmap = null

        hasLoadedImage = false
        lastGeneration = -1
        lastHudType = -1

        image.setImageDrawable(null)

        if (
            previousBitmap != null &&
            !previousBitmap.isRecycled
        ) {
            previousBitmap.recycle()
        }

        currentMapData = MapData(
            emptyList(),
            emptyList(),
            false
        )

        mapOverlay.setMapData(currentMapData)

        updateLegend(currentMapData)

        drawingToolbar.closeDrawingTools()
        drawingToolbar.setExportVisible(false)

        resetMapTransform()

        mapOverlay.invalidate()
    }

    private fun resetMapTransform() {
        zoom = 1f
        translateX = 0f
        translateY = 0f

        applyMapTransform()
    }

    private fun handleTouch(
        event: MotionEvent
    ): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y

                isDragging = true

                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                isDragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (
                    event.pointerCount == 1 &&
                    isDragging &&
                    !scaleDetector.isInProgress &&
                    zoom > MIN_ZOOM
                ) {
                    translateX += event.x - lastTouchX
                    translateY += event.y - lastTouchY

                    applyMapTransform()
                }

                lastTouchX = event.x
                lastTouchY = event.y

                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                isDragging = false
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                return true
            }
        }

        return true
    }

    private fun applyMapTransform() {
        image.pivotX = 0f
        image.pivotY = 0f

        image.scaleX = zoom
        image.scaleY = zoom

        image.translationX = translateX
        image.translationY = translateY

        mapOverlay.pivotX = 0f
        mapOverlay.pivotY = 0f

        mapOverlay.scaleX = zoom
        mapOverlay.scaleY = zoom

        mapOverlay.translationX = translateX
        mapOverlay.translationY = translateY

        mapOverlay.invalidate()
    }

    private fun updateStatus(message: String) {
        if (!::status.isInitialized) return

        status.text = message
    }

    private fun updateMapHint(message: String) {
        if (!::searchMapHint.isInitialized) return

        searchMapHint.text = message

        searchMapHint.visibility =
            if (message.isBlank()) {
                View.GONE
            } else {
                View.VISIBLE
            }
    }

    override fun onDestroy() {
        deviceSelectionDialog?.dismiss()

        updateTask?.cancel(true)

        scheduler.shutdownNow()

        if (::apiClient.isInitialized) {
            apiClient.shutdown()
        }

        super.onDestroy()
    }
}