package com.example.warthundermapdraw

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.hypot

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WarThunderScanner"
        private const val UPDATE_INTERVAL = 200L
        private const val MIN_ZOOM = 1f
        private const val MAX_ZOOM = 5f
        private const val SPAWN_CLUSTER_DISTANCE = 0.035f

        private const val TEAM_BLUE = "#1952FF"
        private const val SQUAD_GREEN = "#AAFF8E"

        private val FRIENDLY_COLORS = setOf(TEAM_BLUE, SQUAD_GREEN)
    }

    private lateinit var image: ImageView
    private lateinit var status: TextView
    private lateinit var searchMapHint: TextView
    private lateinit var mapOverlay: MapOverlayView
    private lateinit var mapContainer: FrameLayout
    private lateinit var mapLegend: LinearLayout
    private lateinit var mapLegendScroll: HorizontalScrollView
    private lateinit var changeDeviceButton: TextView
    private lateinit var apiClient: WarThunderApiClient
    private lateinit var scaleDetector: ScaleGestureDetector

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var updateTask: ScheduledFuture<*>? = null

    @Volatile
    private var isDiscovering = false

    @Volatile
    private var isLoading = false

    private var deviceSelectionDialog: AlertDialog? = null
    private var deviceSelectionDialogShowing = false

    private var hasLoadedImage = false
    private var lastGeneration = -1
    private var lastHudType = -1
    private var currentBitmap: Bitmap? = null

    private var zoom = 1f
    private var translateX = 0f
    private var translateY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    private var currentMapData = MapData(emptyList(), emptyList(), false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContentView(R.layout.activity_main)

        findViewById<View>(android.R.id.content).setOnApplyWindowInsetsListener { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )
            insets
        }

        image = findViewById(R.id.Map)
        status = findViewById(R.id.ConnectionStatus)
        searchMapHint = findViewById(R.id.SearchMapHint)
        mapLegend = findViewById(R.id.MapLegend)
        mapLegendScroll = findViewById(R.id.MapLegendScroll)
        changeDeviceButton = findViewById(R.id.ChangeDeviceButton)

        changeDeviceButton.setOnClickListener {
            changeDevice()
        }

        mapContainer = image.parent as? FrameLayout
            ?: throw IllegalStateException("Map ImageView must be inside a FrameLayout")

        image.scaleType = ImageView.ScaleType.FIT_CENTER
        image.adjustViewBounds = false
        image.pivotX = 0f
        image.pivotY = 0f

        mapOverlay = MapOverlayView()
        mapOverlay.pivotX = 0f
        mapOverlay.pivotY = 0f

        mapContainer.addView(
            mapOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        mapOverlay.isClickable = false
        mapOverlay.isFocusable = false

        apiClient = WarThunderApiClient(applicationContext)

        scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val oldZoom = zoom
                    zoom = (zoom * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    val factor = zoom / oldZoom

                    translateX = detector.focusX - (detector.focusX - translateX) * factor
                    translateY = detector.focusY - (detector.focusY - translateY) * factor

                    applyMapTransform()
                    return true
                }
            }
        )

        mapContainer.setOnTouchListener { _, event ->
            handleTouch(event)
        }

        updateStatus("Searching for War Thunder...")
        updateMapHint("Waiting for War Thunder...")
        startSearching()
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
        if (isDiscovering) {
            Log.d(TAG, "Discovery already running")
            return
        }

        if (deviceSelectionDialogShowing) {
            Log.d(TAG, "Device selection dialog already showing")
            return
        }

        isDiscovering = true
        Log.d(TAG, "Starting device discovery")

        apiClient.discover { devices ->
            runOnUiThread {
                isDiscovering = false

                if (isFinishing || isDestroyed) {
                    Log.w(TAG, "Activity is finishing")
                    return@runOnUiThread
                }

                Log.i(TAG, "Discovery finished. Devices found: ${devices.size}")

                devices.forEach {
                    Log.i(TAG, "Found device: ${it.name} - ${it.address}")
                }

                if (devices.isEmpty()) {
                    updateStatus("Searching for War Thunder...")
                } else {
                    showDeviceSelectionDialog(devices)
                }
            }
        }
    }

    private fun showDeviceSelectionDialog(
        devices: List<WarThunderApiClient.ServerDevice>
    ) {
        if (isFinishing || isDestroyed || devices.isEmpty()) return

        deviceSelectionDialog?.dismiss()
        deviceSelectionDialogShowing = true

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }

        devices.forEachIndexed { index, device ->
            Log.i(TAG, "Dialog device $index: name='${device.name}', address='${device.address}'")

            val button = TextView(this).apply {
                text = "${device.name}\n${device.address}"
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(16, 16, 16, 16)
                setBackgroundResource(android.R.drawable.list_selector_background)
                isClickable = true
                isFocusable = true

                setOnClickListener {
                    apiClient.selectServer(device.address)
                    deviceSelectionDialog?.dismiss()
                    clearMap()
                    updateStatus("Connected: ${device.name}")
                    updateMapHint("Waiting for a battle...")
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

    private fun clearMap() {
        currentBitmap = null
        hasLoadedImage = false
        lastGeneration = -1
        lastHudType = -1

        image.setImageDrawable(null)

        currentMapData = MapData(emptyList(), emptyList(), false)
        mapOverlay.setMapData(currentMapData)
        updateLegend(currentMapData)

        resetMapTransform()
    }

    private fun loadMap() {
        if (isLoading) return
        isLoading = true

        apiClient.loadMap { data ->
            runOnUiThread {
                isLoading = false

                if (isFinishing || isDestroyed) return@runOnUiThread

                if (data != null && data.address != apiClient.getServerAddress()) {
                    return@runOnUiThread
                }

                if (data == null) {
                    if (apiClient.getServerAddress() == null) {
                        updateStatus("Connection lost. Searching again...")
                        updateMapHint("Searching for War Thunder...")
                        updateLegend(MapData(emptyList(), emptyList(), false))
                    }
                    return@runOnUiThread
                }

                if (!data.info.optBoolean("valid", false)) {
                    currentMapData = MapData(emptyList(), emptyList(), false)
                    mapOverlay.setMapData(currentMapData)
                    updateLegend(currentMapData)
                    updateMapHint("Waiting for a battle...")
                    updateStatus("Connected: ${getSelectedDeviceName(data.address)}")
                    return@runOnUiThread
                }

                val generation = data.info.optInt("map_generation", -1)
                val hudType = data.info.optInt("hud_type", -1)

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
                            currentBitmap = bitmap
                            image.setImageBitmap(bitmap)
                            hasLoadedImage = true
                            lastGeneration = generation
                            lastHudType = hudType
                            resetMapTransform()

                            image.post {
                                applyMapTransform()
                                mapOverlay.invalidate()
                            }

                            Log.i(TAG, "Map image loaded: ${bitmap.width}x${bitmap.height}")
                        }
                    }
                }

                updateMapHint(if (currentBitmap != null) "" else "Loading map...")

                currentMapData = parseMapObjects(data.objects)
                mapOverlay.setMapData(currentMapData)
                updateLegend(currentMapData)
                updateStatus("Connected: ${getSelectedDeviceName(data.address)}")
            }
        }
    }

    private fun getSelectedDeviceName(address: String): String {
        return address.removePrefix("http://").substringBefore(":")
    }

    private fun updateLegend(data: MapData) {
        mapLegend.removeAllViews()

        val visibleUnits = data.markers
            .filter {
                when {
                    data.isAirView -> it.type == MarkerType.AIR_UNIT
                    else -> it.type == MarkerType.GROUND_UNIT
                }
            }
            .distinctBy { it.iconName }

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
                setImageResource(getWtIconRes(unit.iconName))
                setColorFilter(unit.color)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = labels[unit.iconName] ?: unit.iconName
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
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            }

            item.addView(icon)
            item.addView(name)
            mapLegend.addView(item)
        }
    }

    private fun resetMapTransform() {
        zoom = 1f
        translateX = 0f
        translateY = 0f
        applyMapTransform()
    }

    private fun handleTouch(event: MotionEvent): Boolean {
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
        searchMapHint.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
    }

    private fun getMapObjectList(objects: JSONObject): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val array = objects.optJSONArray("items") ?: objects.optJSONArray("objects")

        if (array != null) {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                result.add(obj)
            }
        } else {
            val keys = objects.keys()

            while (keys.hasNext()) {
                val obj = objects.optJSONObject(keys.next()) ?: continue
                result.add(obj)
            }
        }

        return result
    }

    private fun normalizeUnitValue(value: String): String {
        return value.lowercase()
            .replace("_", "")
            .replace("-", "")
            .replace(" ", "")
    }

    private fun getWtIconRes(iconName: String): Int = when (iconName) {
        "LightTank" -> R.drawable.ic_light_tank
        "MediumTank" -> R.drawable.ic_medium_tank
        "HeavyTank" -> R.drawable.ic_heavy_tank
        "TankDestroyer" -> R.drawable.ic_tank_destroyer
        "SPAA" -> R.drawable.ic_spaa
        "Fighter" -> R.drawable.ic_fighter
        "Assault" -> R.drawable.ic_assault
        "Bomber" -> R.drawable.ic_bomber
        "Helicopter" -> R.drawable.ic_helicopter
        "Ship", "TorpedoBoat" -> R.drawable.ic_boat
        "Frigate", "Gunboat" -> R.drawable.ic_frigate
        "Destroyer" -> R.drawable.ic_destroyer
        "Cruiser", "LightCruiser", "HeavyCruiser" -> R.drawable.ic_cruiser
        "Battleship", "Battlecruiser" -> R.drawable.ic_battleship
        "Player" -> R.drawable.ic_player
        "capture_zone" -> R.drawable.ic_capture_zone
        "airfield" -> R.drawable.ic_airfield
        else -> R.drawable.ic_light_tank
    }

    private fun isAirObject(type: String, icon: String, obj: JSONObject): Boolean {
        val combined = normalizeUnitValue(
            "$type $icon ${obj.optString("unit_class", "")} ${obj.optString("class", "")}"
        )

        return combined.contains("aircraft") ||
                combined.contains("fighter") ||
                combined.contains("bomber") ||
                combined.contains("helicopter") ||
                combined.contains("attacker") ||
                combined.contains("interceptor") ||
                combined.contains("assault")
    }

    private fun isShipObject(type: String, icon: String, obj: JSONObject): Boolean {
        val combined = normalizeUnitValue(
            "$type $icon ${obj.optString("unit_class", "")} ${obj.optString("class", "")}"
        )

        return combined.contains("ship") ||
                combined.contains("destroyer") ||
                combined.contains("cruiser") ||
                combined.contains("battleship") ||
                combined.contains("torpedoboat") ||
                combined.contains("gunboat") ||
                combined.contains("frigate")
    }

    private fun isGroundObject(type: String, icon: String, obj: JSONObject): Boolean {
        val combined = normalizeUnitValue(
            "$type $icon ${obj.optString("unit_class", "")} ${obj.optString("class", "")}"
        )

        return type == "ground_model" ||
                type == "vehicle" ||
                type.contains("ground") ||
                combined.contains("tank") ||
                combined.contains("spaa") ||
                combined.contains("ifv")
    }

    private fun isAirView(objects: List<JSONObject>): Boolean {
        return objects.any { obj ->
            val type = obj.optString("type", "").lowercase()
            val icon = obj.optString("icon", "").lowercase()

            isAirObject(type, icon, obj) &&
                    (icon == "player" || obj.optBoolean("is_player", false))
        }
    }

    private fun parseMapObjects(objects: JSONObject): MapData {
        val markers = mutableListOf<MapMarker>()
        val spawnPoints = mutableListOf<SpawnPoint>()
        val objectList = getMapObjectList(objects)
        val airView = isAirView(objectList)

        for (obj in objectList) {
            parseMapObject(obj, markers, spawnPoints, airView)
        }

        val captureIndices = markers.indices
            .filter { markers[it].type == MarkerType.CAPTURE }
            .sortedWith(
                compareBy<Int> { markers[it].x }
                    .thenBy { markers[it].y }
            )

        captureIndices.forEachIndexed { index, markerIndex ->
            val marker = markers[markerIndex]
            if (marker.label.isBlank()) {
                markers[markerIndex] = marker.copy(
                    label = ('A'.code + index).toChar().toString()
                )
            }
        }

        Log.d(
            TAG,
            "Parsed map objects: markers=${markers.size}, spawnPoints=${spawnPoints.size}, airView=$airView"
        )

        return MapData(
            markers = markers,
            sectors = clusterSpawnPoints(spawnPoints),
            isAirView = airView
        )
    }

    private fun parseMapObject(
        obj: JSONObject,
        markers: MutableList<MapMarker>,
        spawnPoints: MutableList<SpawnPoint>,
        airView: Boolean
    ) {
        val x = getFloat(obj, "x") ?: return
        val y = getFloat(obj, "y") ?: return
        if (x !in 0f..1f || y !in 0f..1f) return

        val type = obj.optString("type", "").lowercase()
        val icon = obj.optString("icon", "").lowercase()
        val color = getMapObjectColor(obj)

        val isSpawn =
            type.contains("respawn") ||
                    type.startsWith("respawn_base") ||
                    icon.contains("respawn") ||
                    obj.has("respawn_base_tank") ||
                    obj.has("respawn_base_fighter") ||
                    obj.has("respawn_base_bomber")

        if (isSpawn) {
            spawnPoints.add(
                SpawnPoint(x, y, color, getSpawnType(type, icon, obj))
            )
            return
        }

        val isAirfield =
            type.contains("airfield") ||
                    icon.contains("airfield") ||
                    obj.has("airfield")

        if (isAirfield) {
            markers.add(
                MapMarker(
                    x = x,
                    y = y,
                    label = "",
                    color = parseColorOrDefault(color, Color.WHITE),
                    type = MarkerType.AIRFIELD,
                    iconName = "airfield"
                )
            )
            return
        }

        val isCapture =
            type.contains("capture") ||
                    type.contains("zone") ||
                    icon.contains("capture") ||
                    icon.contains("zone") ||
                    obj.has("capture_zone")

        if (isCapture) {
            markers.add(
                MapMarker(
                    x = x,
                    y = y,
                    label = getCaptureLabel(obj),
                    color = parseColorOrDefault(color, Color.rgb(180, 180, 180)),
                    type = MarkerType.CAPTURE,
                    iconName = "capture_zone"
                )
            )
            return
        }

        val isPlayer =
            icon == "player" ||
                    type == "player" ||
                    obj.optBoolean("is_player", false)

        val isAircraft = isAirObject(type, icon, obj)
        val isShip = isShipObject(type, icon, obj)
        val isGroundVehicle = isGroundObject(type, icon, obj)

        if (!isPlayer && !isAircraft && !isShip && !isGroundVehicle) return

        val unitIcon = if (isPlayer) "Player" else getUnitIcon(type, icon, obj)

        val directionX = getFloat(obj, "dx") ?: 0f
        val directionY = getFloat(obj, "dy") ?: -1f

        if (isPlayer) {
            markers.add(
                MapMarker(
                    x = x,
                    y = y,
                    label = "",
                    color = Color.WHITE,
                    type = MarkerType.PLAYER_UNIT,
                    iconName = "Player",
                    directionX = directionX,
                    directionY = directionY
                )
            )
            return
        }

        if (!isFriendlyColor(color)) return

        val teamColor = getFriendlyTeamColor(color)

        if (airView && isAircraft) {
            markers.add(
                MapMarker(
                    x = x,
                    y = y,
                    label = "",
                    color = teamColor,
                    type = MarkerType.AIR_UNIT,
                    iconName = unitIcon,
                    directionX = directionX,
                    directionY = directionY
                )
            )
        } else if (!airView && (isGroundVehicle || isShip)) {
            markers.add(
                MapMarker(
                    x = x,
                    y = y,
                    label = "",
                    color = teamColor,
                    type = MarkerType.GROUND_UNIT,
                    iconName = unitIcon,
                    directionX = directionX,
                    directionY = directionY
                )
            )
        }
    }

    private fun getUnitIcon(
        type: String,
        icon: String,
        obj: JSONObject
    ): String {
        val normalizedIcon = normalizeUnitValue(icon)
        val combined = normalizeUnitValue(
            "$type $icon " +
                    obj.optString("name", "") + " " +
                    obj.optString("class", "") + " " +
                    obj.optString("unit_class", "") + " " +
                    obj.optString("vehicle", "") + " " +
                    obj.optString("unit", "") + " " +
                    obj.optString("model", "")
        )

        return when {
            normalizedIcon.contains("heavytank") ||
                    combined.contains("heavytank") -> "HeavyTank"

            normalizedIcon.contains("lighttank") ||
                    combined.contains("lighttank") -> "LightTank"

            normalizedIcon.contains("mediumtank") ||
                    combined.contains("mediumtank") -> "MediumTank"

            normalizedIcon.contains("tankdestroyer") ||
                    combined.contains("tankdestroyer") ||
                    combined.contains("tankhunter") ||
                    combined.contains("casemate") -> "TankDestroyer"

            normalizedIcon.contains("spaa") ||
                    combined.contains("spaa") ||
                    combined.contains("antiaircraft") -> "SPAA"

            normalizedIcon.contains("torpedobomber") ||
                    combined.contains("torpedobomber") -> "Bomber"

            normalizedIcon.contains("heavybomber") ||
                    combined.contains("heavybomber") ||
                    combined.contains("strategicbomber") ||
                    normalizedIcon.contains("bomber") ||
                    combined.contains("bomber") -> "Bomber"

            normalizedIcon.contains("attacker") ||
                    normalizedIcon.contains("assault") ||
                    combined.contains("attacker") ||
                    combined.contains("groundattack") ||
                    combined.contains("assault") -> "Assault"

            normalizedIcon.contains("fighter") ||
                    combined.contains("fighter") ||
                    combined.contains("interceptor") -> "Fighter"

            normalizedIcon.contains("helicopter") ||
                    combined.contains("helicopter") -> "Helicopter"

            normalizedIcon.contains("battleship") ||
                    normalizedIcon.contains("battlecruiser") ||
                    combined.contains("battleship") ||
                    combined.contains("battlecruiser") -> "Battleship"

            normalizedIcon.contains("heavycruiser") ||
                    combined.contains("heavycruiser") -> "HeavyCruiser"

            normalizedIcon.contains("lightcruiser") ||
                    normalizedIcon.contains("cruiser") ||
                    combined.contains("lightcruiser") ||
                    combined.contains("cruiser") -> "Cruiser"

            normalizedIcon.contains("frigate") ||
                    combined.contains("frigate") -> "Frigate"

            normalizedIcon.contains("destroyer") ||
                    combined.contains("destroyer") -> "Destroyer"

            normalizedIcon.contains("torpedoboat") ||
                    normalizedIcon.contains("torpedocraft") ||
                    combined.contains("torpedoboat") ||
                    combined.contains("torpedocraft") -> "TorpedoBoat"

            normalizedIcon.contains("gunboat") ||
                    normalizedIcon.contains("patrol") ||
                    normalizedIcon.contains("motorboat") ||
                    combined.contains("gunboat") ||
                    combined.contains("patrol") ||
                    combined.contains("motorboat") -> "Gunboat"

            isShipObject(type, icon, obj) -> "Ship"
            isAirObject(type, icon, obj) -> "Fighter"
            else -> "LightTank"
        }
    }

    private fun getFriendlyTeamColor(color: String): Int {
        val normalized = normalizeColor(color)

        return when (normalized) {
            TEAM_BLUE -> Color.rgb(25, 82, 255)
            SQUAD_GREEN -> Color.rgb(170, 255, 142)
            else -> {
                try {
                    val parsed = Color.parseColor(normalized)
                    val red = Color.red(parsed)
                    val green = Color.green(parsed)
                    val blue = Color.blue(parsed)

                    if (green > red * 1.3f && green > blue * 0.8f) {
                        Color.rgb(170, 255, 142)
                    } else {
                        Color.rgb(25, 82, 255)
                    }
                } catch (e: Exception) {
                    Color.rgb(25, 82, 255)
                }
            }
        }
    }

    private fun getCaptureLabel(obj: JSONObject): String {
        val possibleLabels = listOf(
            obj.optString("name", ""),
            obj.optString("label", ""),
            obj.optString("id", ""),
            obj.optString("icon", "")
        )

        for (value in possibleLabels) {
            val label = value.trim().uppercase()
            if (label.matches(Regex("[A-Z]"))) return label
        }

        return ""
    }

    private fun getSpawnType(
        type: String,
        icon: String,
        obj: JSONObject
    ): String {
        val combined = normalizeUnitValue("$type $icon $obj")

        return when {
            combined.contains("fighter") -> "FIGHTER"
            combined.contains("bomber") -> "BOMBER"
            combined.contains("tank") -> "TANK"
            else -> "SPAWN"
        }
    }

    private fun getFloat(obj: JSONObject, vararg keys: String): Float? {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue

            val value = obj.opt(key)
            val number = when (value) {
                is Number -> value.toFloat()
                is String -> value.toFloatOrNull()
                else -> null
            }

            if (number != null && number.isFinite()) return number
        }

        return null
    }

    private fun getMapObjectColor(obj: JSONObject): String {
        val value = obj.opt("color")
            ?: obj.opt("colour")
            ?: obj.opt("color[]")
            ?: return ""

        return when (value) {
            is String -> normalizeColor(value)
            is JSONArray -> {
                if (value.length() >= 3) {
                    val r = value.optInt(0).coerceIn(0, 255)
                    val g = value.optInt(1).coerceIn(0, 255)
                    val b = value.optInt(2).coerceIn(0, 255)
                    String.format("#%02X%02X%02X", r, g, b)
                } else {
                    ""
                }
            }
            else -> ""
        }
    }

    private fun normalizeColor(color: String): String {
        var result = color.trim().uppercase()
        if (!result.startsWith("#")) result = "#$result"
        return result
    }

    private fun parseColorOrDefault(color: String, defaultColor: Int): Int {
        if (color.isBlank()) return defaultColor

        return try {
            Color.parseColor(normalizeColor(color))
        } catch (e: Exception) {
            defaultColor
        }
    }

    private fun isFriendlyColor(color: String): Boolean {
        if (color.isBlank()) return false
        val normalized = normalizeColor(color)

        if (normalized in FRIENDLY_COLORS) return true

        return try {
            val parsed = Color.parseColor(normalized)
            val red = Color.red(parsed)
            val green = Color.green(parsed)
            val blue = Color.blue(parsed)

            (blue > red * 1.25f && blue > green * 0.8f) ||
                    (green > red * 1.3f && green > blue * 0.8f)
        } catch (e: Exception) {
            false
        }
    }

    private fun clusterSpawnPoints(points: List<SpawnPoint>): List<SpawnSector> {
        if (points.isEmpty()) return emptyList()

        val remaining = points.toMutableList()
        val sectors = mutableListOf<SpawnSector>()

        while (remaining.isNotEmpty()) {
            val cluster = mutableListOf<SpawnPoint>()
            val queue = ArrayDeque<SpawnPoint>()
            queue.add(remaining.removeAt(0))

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                cluster.add(current)

                val nearby = remaining.filter {
                    distance(current.x, current.y, it.x, it.y) <= SPAWN_CLUSTER_DISTANCE
                }

                for (point in nearby) {
                    remaining.remove(point)
                    queue.add(point)
                }
            }

            sectors.add(
                SpawnSector(
                    points = cluster,
                    color = cluster.first().color,
                    type = cluster.first().type
                )
            )
        }

        return sectors
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return hypot(x2 - x1, y2 - y1)
    }

    override fun onDestroy() {
        deviceSelectionDialog?.dismiss()
        updateTask?.cancel(true)
        scheduler.shutdownNow()
        apiClient.shutdown()
        super.onDestroy()
    }

    data class MapData(
        val markers: List<MapMarker>,
        val sectors: List<SpawnSector>,
        val isAirView: Boolean
    )

    data class MapMarker(
        val x: Float,
        val y: Float,
        val label: String,
        val color: Int,
        val type: MarkerType,
        val iconName: String = "LightTank",
        val directionX: Float = 0f,
        val directionY: Float = -1f
    )

    data class SpawnPoint(
        val x: Float,
        val y: Float,
        val color: String,
        val type: String
    )

    data class SpawnSector(
        val points: List<SpawnPoint>,
        val color: String,
        val type: String
    )

    enum class MarkerType {
        CAPTURE,
        AIRFIELD,
        GROUND_UNIT,
        AIR_UNIT,
        PLAYER_UNIT
    }

    private inner class MapOverlayView : View(this@MainActivity) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val imageRect = RectF()
        private val bitmapRect = RectF()
        private val drawableCache = mutableMapOf<Int, Drawable?>()

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }

        private val textBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(15, 20, 28)
            style = Paint.Style.FILL
        }

        private var mapData = MapData(emptyList(), emptyList(), false)

        fun setMapData(data: MapData) {
            mapData = data
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val bitmap = currentBitmap ?: return
            if (image.drawable == null) return

            bitmapRect.set(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            imageRect.set(bitmapRect)
            image.imageMatrix.mapRect(imageRect)

            imageRect.offset(
                image.left.toFloat() + image.paddingLeft,
                image.top.toFloat() + image.paddingTop
            )

            if (imageRect.width() <= 0f || imageRect.height() <= 0f) return

            drawSpawnSectors(canvas, imageRect)
            drawMarkers(canvas, imageRect)
        }

        private fun drawSpawnSectors(canvas: Canvas, mapRect: RectF) {
            val density = resources.displayMetrics.density

            for (sector in mapData.sectors) {
                val color = parseColorOrDefault(
                    sector.color,
                    Color.rgb(90, 150, 255)
                )

                val hull = convexHull(sector.points)
                if (hull.isEmpty()) continue

                var centerX = 0f
                var centerY = 0f

                for (point in sector.points) {
                    centerX += point.x
                    centerY += point.y
                }

                centerX /= sector.points.size.coerceAtLeast(1)
                centerY /= sector.points.size.coerceAtLeast(1)

                when {
                    hull.size >= 3 -> {
                        val path = Path()
                        path.moveTo(
                            mapRect.left + hull[0].x * mapRect.width(),
                            mapRect.top + hull[0].y * mapRect.height()
                        )

                        for (i in 1 until hull.size) {
                            path.lineTo(
                                mapRect.left + hull[i].x * mapRect.width(),
                                mapRect.top + hull[i].y * mapRect.height()
                            )
                        }

                        path.close()

                        paint.style = Paint.Style.FILL
                        paint.color = color
                        paint.alpha = 10
                        canvas.drawPath(path, paint)

                        paint.style = Paint.Style.STROKE
                        paint.color = color
                        paint.alpha = 190
                        paint.strokeWidth = 1f * density
                        paint.pathEffect = null
                        canvas.drawPath(path, paint)
                    }

                    hull.size == 2 -> {
                        paint.style = Paint.Style.STROKE
                        paint.color = color
                        paint.alpha = 190
                        paint.strokeWidth = 1f * density
                        paint.strokeCap = Paint.Cap.ROUND

                        canvas.drawLine(
                            mapRect.left + hull[0].x * mapRect.width(),
                            mapRect.top + hull[0].y * mapRect.height(),
                            mapRect.left + hull[1].x * mapRect.width(),
                            mapRect.top + hull[1].y * mapRect.height(),
                            paint
                        )
                    }

                    hull.size == 1 -> {
                        paint.style = Paint.Style.STROKE
                        paint.color = color
                        paint.alpha = 190
                        paint.strokeWidth = 0.8f * density

                        canvas.drawCircle(
                            mapRect.left + hull[0].x * mapRect.width(),
                            mapRect.top + hull[0].y * mapRect.height(),
                            2f * density,
                            paint
                        )
                    }
                }

                if (hull.size >= 3) {
                    val bounds = RectF(
                        mapRect.left + hull.minOf { it.x } * mapRect.width(),
                        mapRect.top + hull.minOf { it.y } * mapRect.height(),
                        mapRect.left + hull.maxOf { it.x } * mapRect.width(),
                        mapRect.top + hull.maxOf { it.y } * mapRect.height()
                    )

                    drawFitLabel(
                        canvas,
                        sector.type,
                        mapRect.left + centerX * mapRect.width(),
                        mapRect.top + centerY * mapRect.height(),
                        color,
                        bounds,
                        10f * density
                    )
                }
            }

            paint.pathEffect = null
        }

        private fun drawMarkers(canvas: Canvas, mapRect: RectF) {
            val density = resources.displayMetrics.density

            for (marker in mapData.markers) {
                val x = mapRect.left + marker.x * mapRect.width()
                val y = mapRect.top + marker.y * mapRect.height()

                when (marker.type) {
                    MarkerType.CAPTURE -> {
                        drawUnitIcon(canvas, x, y, marker, density)

                        if (marker.label.isNotBlank()) {
                            textPaint.color = Color.BLACK
                            textPaint.style = Paint.Style.FILL
                            textPaint.textSize = 12f * density
                            textPaint.textAlign = Paint.Align.CENTER
                            textPaint.typeface = Typeface.create(
                                Typeface.DEFAULT,
                                Typeface.BOLD
                            )

                            val textY = y - (textPaint.ascent() + textPaint.descent()) / 2f
                            canvas.drawText(marker.label, x, textY, textPaint)
                        }
                    }

                    MarkerType.AIRFIELD -> {
                        drawUnitIcon(canvas, x, y, marker, density)
                    }

                    MarkerType.GROUND_UNIT -> {
                        if (!mapData.isAirView) {
                            drawUnitIcon(canvas, x, y, marker, density)
                        }
                    }

                    MarkerType.PLAYER_UNIT -> {
                        drawUnitIcon(canvas, x, y, marker, density)
                    }

                    MarkerType.AIR_UNIT -> {
                        if (mapData.isAirView) {
                            drawUnitIcon(canvas, x, y, marker, density)
                        }
                    }
                }
            }
        }

        private fun drawUnitIcon(
            canvas: Canvas,
            x: Float,
            y: Float,
            marker: MapMarker,
            density: Float
        ) {
            val resourceId = getWtIconRes(marker.iconName)

            val drawable = drawableCache.getOrPut(resourceId) {
                try {
                    getDrawable(resourceId)?.mutate()
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot load drawable: ${marker.iconName}", e)
                    null
                }
            } ?: return

            val isPlayer = marker.type == MarkerType.PLAYER_UNIT

            val size = when (marker.iconName) {
                "capture_zone" -> 19f
                "airfield" -> 23f
                "Battleship", "Battlecruiser" -> if (isPlayer) 10f else 9f
                "Bomber" -> if (isPlayer) 9f else 8f
                "Player" -> 7.3f
                else -> if (isPlayer) 7.3f else 6.3f
            } * density

            val width = size * when (marker.iconName) {
                "Ship", "TorpedoBoat", "Frigate", "Gunboat",
                "Destroyer", "Cruiser", "LightCruiser", "HeavyCruiser",
                "Battleship", "Battlecruiser" -> 0.72f
                else -> 0.78f
            }

            val height = size

            val directionLength = hypot(marker.directionX, marker.directionY)
            val directionX = if (directionLength > 0.001f) marker.directionX else 0f
            val directionY = if (directionLength > 0.001f) marker.directionY else -1f

            val angle = Math.toDegrees(
                atan2(directionY.toDouble(), directionX.toDouble())
            ).toFloat() + 90f

            val left = -width / 2f
            val top = -height / 2f
            val right = width / 2f
            val bottom = height / 2f
            val outline = 0.7f * density

            canvas.save()
            canvas.translate(x, y)

            if (
                marker.type == MarkerType.GROUND_UNIT ||
                marker.type == MarkerType.AIR_UNIT ||
                marker.type == MarkerType.PLAYER_UNIT
            ) {
                canvas.rotate(angle)
            }

            drawable.setBounds(
                (left - outline).toInt(),
                (top - outline).toInt(),
                (right + outline).toInt(),
                (bottom + outline).toInt()
            )
            drawable.setTint(Color.BLACK)
            drawable.alpha = 255
            drawable.draw(canvas)

            drawable.setBounds(
                left.toInt(),
                top.toInt(),
                right.toInt(),
                bottom.toInt()
            )
            drawable.setTint(marker.color)
            drawable.alpha = 255
            drawable.draw(canvas)

            canvas.restore()
        }

        private fun drawFitLabel(
            canvas: Canvas,
            text: String,
            x: Float,
            y: Float,
            color: Int,
            bounds: RectF,
            maxTextSize: Float
        ) {
            if (text.isBlank()) return

            val density = resources.displayMetrics.density
            val horizontalPadding = 3f * density
            val verticalPadding = 2f * density
            val availableWidth = bounds.width() - horizontalPadding * 2f
            val availableHeight = bounds.height() - verticalPadding * 2f

            if (availableWidth <= 0f || availableHeight <= 0f) return

            textPaint.textSize = maxTextSize
            textPaint.style = Paint.Style.FILL

            val initialWidth = textPaint.measureText(text)
            val initialMetrics = textPaint.fontMetrics
            val initialHeight = initialMetrics.descent - initialMetrics.ascent

            if (initialWidth <= 0f || initialHeight <= 0f) return

            val scale = minOf(
                1f,
                availableWidth / initialWidth,
                availableHeight / initialHeight
            )

            textPaint.textSize = maxTextSize * scale

            val metrics = textPaint.fontMetrics
            val baseline = y - (metrics.ascent + metrics.descent) / 2f
            val textWidth = textPaint.measureText(text)
            val paddingX = minOf(horizontalPadding, availableWidth * 0.08f)
            val paddingY = minOf(verticalPadding, availableHeight * 0.08f)

            val background = RectF(
                x - textWidth / 2f - paddingX,
                baseline + metrics.ascent - paddingY,
                x + textWidth / 2f + paddingX,
                baseline + metrics.descent + paddingY
            )

            if (
                background.left < bounds.left ||
                background.right > bounds.right ||
                background.top < bounds.top ||
                background.bottom > bounds.bottom
            ) return

            canvas.drawRoundRect(
                background,
                2f * density * scale,
                2f * density * scale,
                textBackgroundPaint
            )

            textPaint.style = Paint.Style.STROKE
            textPaint.strokeWidth = 1.5f * density * scale
            textPaint.strokeJoin = Paint.Join.ROUND
            textPaint.color = Color.BLACK
            canvas.drawText(text, x, baseline, textPaint)

            textPaint.style = Paint.Style.FILL
            textPaint.color = Color.WHITE
            canvas.drawText(text, x, baseline, textPaint)
        }

        private fun convexHull(points: List<SpawnPoint>): List<PointF> {
            val sorted = points
                .map { PointF(it.x, it.y) }
                .distinctBy { "${it.x}:${it.y}" }
                .sortedWith(compareBy<PointF> { it.x }.thenBy { it.y })

            if (sorted.size <= 2) return sorted

            fun cross(o: PointF, a: PointF, b: PointF): Float {
                return (a.x - o.x) * (b.y - o.y) -
                        (a.y - o.y) * (b.x - o.x)
            }

            val lower = mutableListOf<PointF>()

            for (point in sorted) {
                while (
                    lower.size >= 2 &&
                    cross(lower[lower.size - 2], lower[lower.size - 1], point) <= 0f
                ) {
                    lower.removeAt(lower.lastIndex)
                }
                lower.add(point)
            }

            val upper = mutableListOf<PointF>()

            for (point in sorted.asReversed()) {
                while (
                    upper.size >= 2 &&
                    cross(upper[upper.size - 2], upper[upper.size - 1], point) <= 0f
                ) {
                    upper.removeAt(upper.lastIndex)
                }
                upper.add(point)
            }

            lower.removeAt(lower.lastIndex)
            upper.removeAt(upper.lastIndex)
            return lower + upper
        }
    }
}