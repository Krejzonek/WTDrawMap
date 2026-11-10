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
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
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
        private const val UPDATE_INTERVAL = 500L
        private const val MIN_ZOOM = 1f
        private const val MAX_ZOOM = 5f
        private const val SPAWN_CLUSTER_DISTANCE = 0.035f

        private val FRIENDLY_COLORS = setOf(
            "#1952FF",
            "#AAFF8E"
        )
    }

    private lateinit var image: ImageView
    private lateinit var status: TextView
    private lateinit var searchMapHint: TextView
    private lateinit var mapOverlay: MapOverlayView
    private lateinit var mapContainer: FrameLayout
    private lateinit var apiClient: WarThunderApiClient
    private lateinit var scaleDetector: ScaleGestureDetector

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var updateTask: ScheduledFuture<*>? = null

    @Volatile
    private var isDiscovering = false

    @Volatile
    private var isLoading = false

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

    private var currentMapData = MapData(
        markers = emptyList(),
        sectors = emptyList(),
        isAirView = false
    )

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
        if (isDiscovering) return
        isDiscovering = true

        apiClient.discover { address ->
            runOnUiThread {
                isDiscovering = false

                if (address != null) {
                    Log.i(TAG, "Server found: $address")
                    updateStatus("Connected: $address")
                } else {
                    updateStatus("Searching for War Thunder...")
                }
            }
        }
    }

    private fun loadMap() {
        if (isLoading) return
        isLoading = true

        apiClient.loadMap { data ->
            runOnUiThread {
                isLoading = false

                if (data == null) {
                    if (apiClient.getServerAddress() == null) {
                        updateStatus("Connection lost. Searching again...")
                        updateMapHint("Searching for War Thunder...")
                    }
                    return@runOnUiThread
                }

                if (!data.info.optBoolean("valid", false)) {
                    currentMapData = MapData(
                        markers = emptyList(),
                        sectors = emptyList(),
                        isAirView = false
                    )

                    mapOverlay.setMapData(currentMapData)
                    updateMapHint("Waiting for a battle...")
                    updateStatus("Connected: ${data.address}")
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

                            Log.i(
                                TAG,
                                "Map image loaded: ${bitmap.width}x${bitmap.height}"
                            )
                        }
                    }
                }

                if (currentBitmap != null) {
                    updateMapHint("")
                } else {
                    updateMapHint("Loading map...")
                }

                currentMapData = parseMapObjects(data.objects)
                mapOverlay.setMapData(currentMapData)

                updateStatus("Connected: ${data.address}")
            }
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
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY

                    translateX += dx
                    translateY += dy

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
            if (message.isBlank()) View.GONE else View.VISIBLE
    }

    private fun getMapObjectList(objects: JSONObject): List<JSONObject> {
        val result = mutableListOf<JSONObject>()

        val array = objects.optJSONArray("items")
            ?: objects.optJSONArray("objects")

        if (array != null) {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                result.add(obj)
            }
        } else {
            val keys = objects.keys()

            while (keys.hasNext()) {
                val key = keys.next()
                val obj = objects.optJSONObject(key) ?: continue
                result.add(obj)
            }
        }

        return result
    }

    private fun isAirView(objects: List<JSONObject>): Boolean {
        return objects.any { obj ->
            val type = obj.optString("type", "").lowercase()
            val icon = obj.optString("icon", "").lowercase()

            type == "aircraft" &&
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
                SpawnPoint(
                    x = x,
                    y = y,
                    color = color,
                    type = getSpawnType(type, icon, obj)
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
            val captureColor = parseColorOrDefault(
                color,
                Color.rgb(180, 180, 180)
            )

            val label = getCaptureLabel(obj)

            markers.add(
                MapMarker(
                    x,
                    y,
                    label,
                    captureColor,
                    MarkerType.CAPTURE
                )
            )
            return
        }

        val isPlayer =
            icon == "player" ||
                    type == "player" ||
                    obj.optBoolean("is_player", false)

        if (isPlayer) {
            val isAircraft = type == "aircraft"

            markers.add(
                MapMarker(
                    x = x,
                    y = y,
                    label = "",
                    color = Color.WHITE,
                    type = if (isAircraft) {
                        MarkerType.PLAYER_AIRCRAFT
                    } else {
                        MarkerType.PLAYER
                    },
                    directionX = getFloat(obj, "dx") ?: 0f,
                    directionY = getFloat(obj, "dy") ?: -1f
                )
            )
            return
        }

        val isAircraft = type == "aircraft"
        val isGroundVehicle =
            type == "ground_model" ||
                    type == "ship" ||
                    type == "vehicle"

        if (airView) {
            if (isAircraft && isFriendlyColor(color)) {
                markers.add(
                    MapMarker(
                        x = x,
                        y = y,
                        label = "",
                        color = Color.rgb(25, 82, 255),
                        type = MarkerType.AIRCRAFT_TEAMMATE,
                        directionX = getFloat(obj, "dx") ?: 0f,
                        directionY = getFloat(obj, "dy") ?: -1f
                    )
                )
            }
        } else {
            if (isGroundVehicle && isFriendlyColor(color)) {
                markers.add(
                    MapMarker(
                        x = x,
                        y = y,
                        label = "",
                        color = Color.rgb(25, 82, 255),
                        type = MarkerType.TEAMMATE
                    )
                )
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

            if (label.matches(Regex("[A-Z]"))) {
                return label
            }
        }

        return ""
    }

    private fun getSpawnType(
        type: String,
        icon: String,
        obj: JSONObject
    ): String {
        val combined = "$type $icon ${obj.toString()}".lowercase()

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

            if (number != null && number.isFinite()) {
                return number
            }
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

        if (!result.startsWith("#")) {
            result = "#$result"
        }

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

    private fun distance(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): Float {
        return hypot(x2 - x1, y2 - y1)
    }

    override fun onDestroy() {
        updateTask?.cancel(true)
        scheduler.shutdownNow()
        apiClient.disconnect()
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
        TEAMMATE,
        AIRCRAFT_TEAMMATE,
        PLAYER,
        PLAYER_AIRCRAFT
    }

    private inner class MapOverlayView : View(this@MainActivity) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val imageRect = RectF()
        private val bitmapRect = RectF()

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(
                "sans-serif-condensed",
                Typeface.BOLD
            )
        }

        private val textBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(15, 20, 28)
            style = Paint.Style.FILL
        }

        private val aircraftPath = Path().apply {
            moveTo(0f, -9f)
            lineTo(1.8f, -2.5f)
            lineTo(7f, 2f)
            lineTo(7f, 3.5f)
            lineTo(1.5f, 2f)
            lineTo(1.5f, 6f)
            lineTo(3f, 7.5f)
            lineTo(3f, 8.5f)
            lineTo(0f, 7.5f)
            lineTo(-3f, 8.5f)
            lineTo(-3f, 7.5f)
            lineTo(-1.5f, 6f)
            lineTo(-1.5f, 2f)
            lineTo(-7f, 3.5f)
            lineTo(-7f, 2f)
            lineTo(-1.8f, -2.5f)
            close()
        }

        private var mapData = MapData(
            emptyList(),
            emptyList(),
            false
        )

        fun setMapData(data: MapData) {
            mapData = data
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val bitmap = currentBitmap ?: return
            if (image.drawable == null) return

            bitmapRect.set(
                0f,
                0f,
                bitmap.width.toFloat(),
                bitmap.height.toFloat()
            )

            imageRect.set(bitmapRect)
            image.imageMatrix.mapRect(imageRect)

            imageRect.offset(
                image.left.toFloat() + image.paddingLeft,
                image.top.toFloat() + image.paddingTop
            )

            if (
                imageRect.width() <= 0f ||
                imageRect.height() <= 0f
            ) return

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

                    val labelX = mapRect.left + centerX * mapRect.width()
                    val labelY = mapRect.top + centerY * mapRect.height()

                    drawFitLabel(
                        canvas,
                        sector.type,
                        labelX,
                        labelY,
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

            val captureRadius = 7f * density
            val teammateWidth = 5f * density
            val teammateHeight = 3f * density
            val playerWidth = 7f * density
            val playerHeight = 4f * density
            val outlineWidth = 0.7f * density

            for (marker in mapData.markers) {
                val x = mapRect.left + marker.x * mapRect.width()
                val y = mapRect.top + marker.y * mapRect.height()

                when (marker.type) {
                    MarkerType.CAPTURE -> {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = 1f * density
                        paint.color = marker.color
                        paint.alpha = 190
                        canvas.drawCircle(x, y, captureRadius, paint)

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

                    MarkerType.TEAMMATE -> {
                        if (!mapData.isAirView) {
                            drawRectangleMarker(
                                canvas,
                                x,
                                y,
                                teammateWidth,
                                teammateHeight,
                                marker.color,
                                outlineWidth
                            )
                        }
                    }

                    MarkerType.PLAYER -> {
                        if (!mapData.isAirView) {
                            drawRectangleMarker(
                                canvas,
                                x,
                                y,
                                playerWidth,
                                playerHeight,
                                Color.WHITE,
                                outlineWidth
                            )
                        }
                    }

                    MarkerType.AIRCRAFT_TEAMMATE -> {
                        if (mapData.isAirView) {
                            drawAircraftMarker(
                                canvas,
                                x,
                                y,
                                marker.directionX,
                                marker.directionY,
                                marker.color,
                                density
                            )
                        }
                    }

                    MarkerType.PLAYER_AIRCRAFT -> {
                        if (mapData.isAirView) {
                            drawAircraftMarker(
                                canvas,
                                x,
                                y,
                                marker.directionX,
                                marker.directionY,
                                Color.WHITE,
                                density
                            )
                        }
                    }
                }
            }
        }

        private fun drawAircraftMarker(
            canvas: Canvas,
            x: Float,
            y: Float,
            directionX: Float,
            directionY: Float,
            color: Int,
            density: Float
        ) {
            val angle = Math.toDegrees(
                atan2(
                    directionY.toDouble(),
                    directionX.toDouble()
                )
            ).toFloat() + 90f

            val scale = 1.15f * density

            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(angle)
            canvas.scale(scale, scale)

            paint.style = Paint.Style.FILL
            paint.color = color
            paint.alpha = 255
            canvas.drawPath(aircraftPath, paint)

            paint.style = Paint.Style.STROKE
            paint.color = Color.BLACK
            paint.strokeWidth = 1.1f
            paint.strokeJoin = Paint.Join.ROUND
            paint.alpha = 255
            canvas.drawPath(aircraftPath, paint)

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

        private fun drawLabel(
            canvas: Canvas,
            text: String,
            x: Float,
            y: Float,
            color: Int,
            textSize: Float
        ) {
            if (text.isBlank()) return

            val density = resources.displayMetrics.density

            textPaint.textSize = textSize
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.style = Paint.Style.FILL
            textPaint.color = Color.WHITE

            val metrics = textPaint.fontMetrics
            val textWidth = textPaint.measureText(text)
            val padding = 3f * density

            val background = RectF(
                x - textWidth / 2f - padding,
                y + metrics.ascent - padding,
                x + textWidth / 2f + padding,
                y + metrics.descent + padding
            )

            canvas.drawRoundRect(
                background,
                3f * density,
                3f * density,
                textBackgroundPaint
            )

            textPaint.style = Paint.Style.STROKE
            textPaint.strokeWidth = 2f * density
            textPaint.strokeJoin = Paint.Join.ROUND
            textPaint.color = Color.BLACK

            canvas.drawText(text, x, y, textPaint)

            textPaint.style = Paint.Style.FILL
            textPaint.color = Color.WHITE

            canvas.drawText(text, x, y, textPaint)
        }

        private fun drawRectangleMarker(
            canvas: Canvas,
            x: Float,
            y: Float,
            width: Float,
            height: Float,
            color: Int,
            outlineWidth: Float
        ) {
            val rect = RectF(
                x - width / 2f,
                y - height / 2f,
                x + width / 2f,
                y + height / 2f
            )

            paint.style = Paint.Style.FILL
            paint.color = color
            paint.alpha = 255
            canvas.drawRect(rect, paint)

            paint.style = Paint.Style.STROKE
            paint.color = Color.BLACK
            paint.strokeWidth = outlineWidth
            paint.alpha = 255
            canvas.drawRect(rect, paint)
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