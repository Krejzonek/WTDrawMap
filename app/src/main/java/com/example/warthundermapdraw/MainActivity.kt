package com.example.warthundermapdraw

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WarThunderScanner"
        private const val UPDATE_INTERVAL = 500L
        private const val MIN_ZOOM = 1f
        private const val MAX_ZOOM = 5f
    }

    private lateinit var image: ImageView
    private lateinit var status: TextView
    private lateinit var searchMapHint: TextView
    private lateinit var mapContainer: FrameLayout
    private lateinit var mapLegend: LinearLayout
    private lateinit var mapLegendScroll: android.widget.HorizontalScrollView
    private lateinit var drawingLegend: LinearLayout
    private lateinit var drawingLegendScroll: android.widget.HorizontalScrollView
    private lateinit var changeDeviceButton: TextView
    private lateinit var sessionCodeView: TextView
    private lateinit var tacticalMapButton: ImageButton
    private lateinit var sideMenu: LinearLayout
    private lateinit var menuOverlay: View
    private lateinit var participantsContainer: LinearLayout
    private lateinit var leaveSessionButton: TextView

    private var activeSessionCode = ""
    private var isSessionMenuOpen = false
    private var hasActiveSession = false
    private var isReturningHome = false

    private var apiClient: WarThunderApiClient? = null
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var drawingManager: MapDrawingManager
    private lateinit var mapOverlay: MapOverlayView
    private lateinit var drawingToolbar: MapDrawingToolbar
    private lateinit var multiplayer: MultiplayerSessionManager

    private var currentBitmap: Bitmap? = null
    private var hasLoadedImage = false
    private var lastGeneration = -1
    private var lastHudType = -1
    private var lastSentMapKey = ""
    private var lastSentObjectsJson = ""

    private var lastMapInfo: JSONObject? = null
    private var lastMapObjects: JSONObject? = null
    private var lastMapImage: ByteArray? = null

    private var currentMapData = MapData(emptyList(), emptyList(), false)

    private var lastLegendSignature = ""
    private var lastDrawingLegendSignature = ""

    private var zoom = 1f
    private var translateX = 0f
    private var translateY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val mapProcessingExecutor = Executors.newSingleThreadExecutor()
    private var updateTask: ScheduledFuture<*>? = null

    @Volatile private var isDiscovering = false
    @Volatile private var isLoading = false
    @Volatile private var isActivityDestroyed = false

    private var deviceSelectionDialog: AlertDialog? = null
    private var deviceSelectionDialogShowing = false

    private val isHost by lazy { intent.getBooleanExtra("SESSION_HOST", true) }
    private val username by lazy { intent.getStringExtra("USERNAME") ?: "User" }
    private val sessionCode by lazy { intent.getStringExtra("SESSION_CODE") ?: "" }

    private val createPngDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
            uri?.let { exportTacticalMap(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContentView(R.layout.activity_main)

        findViewById<View>(android.R.id.content).setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        initializeViews()
        initializeSessionMenu()
        initializeMap()
        initializeDrawing()
        initializeGestures()

        if (isHost) {
            initializeApi()
            changeDeviceButton.visibility = View.VISIBLE
        } else {
            changeDeviceButton.visibility = View.GONE
        }

        updateStatus("Connecting to multiplayer...")
        updateMapHint("Connecting...")
        initializeMultiplayer()
    }

    private fun initializeViews() {
        image = findViewById(R.id.Map)
        status = findViewById(R.id.ConnectionStatus)
        searchMapHint = findViewById(R.id.SearchMapHint)
        mapLegend = findViewById(R.id.MapLegend)
        mapLegendScroll = findViewById(R.id.MapLegendScroll)
        drawingLegend = findViewById(R.id.DrawingLegend)
        drawingLegendScroll = findViewById(R.id.DrawingLegendScroll)
        changeDeviceButton = findViewById(R.id.ChangeDeviceButton)
        sessionCodeView = findViewById(R.id.SessionCode)

        updateSessionCode(sessionCode)

        sessionCodeView.setOnClickListener {
            activeSessionCode.takeIf { it.isNotBlank() }?.let { code ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Session code", code))
                ToastMessage("Session code copied")
            }
        }

        changeDeviceButton.setOnClickListener { changeDevice() }

        mapContainer = image.parent as? FrameLayout
            ?: throw IllegalStateException("Map ImageView must be inside a FrameLayout")

        image.apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = false
            pivotX = 0f
            pivotY = 0f
        }

        drawingLegendScroll.visibility = View.GONE
    }

    private fun initializeSessionMenu() {
        tacticalMapButton = findViewById(R.id.TacticalMap)
        sideMenu = findViewById(R.id.SideMenu)
        menuOverlay = findViewById(R.id.MenuOverlay)
        participantsContainer = findViewById(R.id.ParticipantsContainer)
        leaveSessionButton = findViewById(R.id.LeaveSessionButton)

        tacticalMapButton.setOnClickListener {
            if (isSessionMenuOpen) closeSessionMenu() else openSessionMenu()
        }

        menuOverlay.setOnClickListener { closeSessionMenu() }
        leaveSessionButton.setOnClickListener { confirmLeaveSession() }

        updateParticipants(emptyList())
    }

    private fun openSessionMenu() {
        isSessionMenuOpen = true
        menuOverlay.visibility = View.VISIBLE
        sideMenu.visibility = View.VISIBLE
        menuOverlay.bringToFront()
        sideMenu.bringToFront()
        updateLeaveSessionButton()
    }

    private fun closeSessionMenu() {
        isSessionMenuOpen = false
        sideMenu.visibility = View.GONE
        menuOverlay.visibility = View.GONE
    }

    private fun updateLeaveSessionButton() {
        leaveSessionButton.apply {
            isEnabled = hasActiveSession
            alpha = if (hasActiveSession) 1f else 0.45f
            text = if (hasActiveSession) "LEAVE SESSION" else "NO ACTIVE SESSION"
        }
    }

    private fun updateParticipants(participants: List<SessionParticipant>) {
        if (!::participantsContainer.isInitialized) return

        participantsContainer.removeAllViews()

        val density = resources.displayMetrics.density
        val localUid = if (::multiplayer.isInitialized) multiplayer.getLocalUid() else ""

        if (participants.isEmpty()) {
            participantsContainer.addView(TextView(this).apply {
                text = if (hasActiveSession) "Waiting for participants..." else "No active session"
                setTextColor(Color.rgb(127, 152, 181))
                textSize = 12f
                setPadding(20 * density.toInt(), 12 * density.toInt(), 20 * density.toInt(), 12 * density.toInt())
            })
            updateLeaveSessionButton()
            return
        }

        participants.sortedWith(
            compareByDescending<SessionParticipant> { it.isHost }
                .thenBy { it.name.lowercase() }
        ).forEach { participant ->

            val isLocalPlayer = participant.uid == localUid
            val roleColor = if (participant.isHost) {
                Color.rgb(114, 214, 155)
            } else {
                Color.rgb(114, 183, 245)
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    (18 * density).toInt(),
                    (11 * density).toInt(),
                    (16 * density).toInt(),
                    (11 * density).toInt()
                )
                setBackgroundResource(android.R.drawable.list_selector_background)
            }

            row.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(roleColor)
                }
                layoutParams = LinearLayout.LayoutParams(
                    (8 * density).toInt(),
                    (8 * density).toInt()
                )
            })

            val nameColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12 * density).toInt(), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            nameColumn.addView(TextView(this).apply {
                text = participant.name.ifBlank { "Player" }
                setTextColor(Color.rgb(220, 232, 245))
                textSize = 13f
                maxLines = 1
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            })

            val labels = buildList {
                if (participant.isHost) add("HOST")
                if (isLocalPlayer) add("YOU")
                if (!participant.isHost) add(if (participant.canDraw) "DRAW ENABLED" else "DRAW DISABLED")
            }

            nameColumn.addView(TextView(this).apply {
                text = labels.joinToString("  •  ")
                setTextColor(roleColor)
                textSize = 9f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                letterSpacing = 0.08f
                visibility = if (labels.isEmpty()) View.GONE else View.VISIBLE
            })

            row.addView(nameColumn)

            if (isHost && !participant.isHost && !isLocalPlayer) {
                row.addView(TextView(this).apply {
                    text = "MANAGE"
                    textSize = 10f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(Color.rgb(114, 183, 245))
                    gravity = Gravity.CENTER
                    setPadding(10 * density.toInt(), 8 * density.toInt(), 10 * density.toInt(), 8 * density.toInt())
                    setBackgroundResource(android.R.drawable.list_selector_background)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { showParticipantManagement(participant) }
                })
            }

            participantsContainer.addView(row)
            participantsContainer.addView(View(this).apply {
                setBackgroundColor(Color.rgb(41, 57, 76))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                density.toInt()
            ))
        }

        updateLeaveSessionButton()
    }

    private fun showParticipantManagement(participant: SessionParticipant) {
        if (!isHost || !::multiplayer.isInitialized) return

        val options = arrayOf(
            if (participant.canDraw) "Disable drawing" else "Allow drawing",
            "Delete all drawings",
            "Kick from session",
            "Ban from session"
        )

        AlertDialog.Builder(this)
            .setTitle(participant.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> multiplayer.setParticipantDrawingPermission(
                        participant.uid,
                        !participant.canDraw
                    ) { success, error ->
                        runOnUiThread {
                            ToastMessage(
                                if (success) {
                                    if (participant.canDraw) "Drawing permission removed"
                                    else "Drawing permission granted"
                                } else {
                                    error ?: "Permission update failed"
                                }
                            )
                        }
                    }

                    1 -> confirmDeleteParticipantDrawings(participant)
                    2 -> confirmParticipantModeration(participant, false)
                    3 -> confirmParticipantModeration(participant, true)
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun confirmDeleteParticipantDrawings(participant: SessionParticipant) {
        AlertDialog.Builder(this)
            .setTitle("Delete all drawings")
            .setMessage("Delete all drawings made by ${participant.name}?\n\nThis action cannot be undone.")
            .setPositiveButton("DELETE") { _, _ ->
                multiplayer.clearParticipantDrawings(participant.uid) { success, error ->
                    runOnUiThread {
                        ToastMessage(
                            if (success) "All drawings by ${participant.name} deleted"
                            else error ?: "Could not delete participant drawings"
                        )
                    }
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun confirmParticipantModeration(participant: SessionParticipant, ban: Boolean) {
        val action = if (ban) "BAN" else "KICK"

        AlertDialog.Builder(this)
            .setTitle(action)
            .setMessage(
                if (ban) "Block ${participant.name} from rejoining this session?"
                else "Remove ${participant.name} from this session?"
            )
            .setPositiveButton(action) { _, _ ->
                multiplayer.moderateParticipant(participant.uid, ban) { success, error ->
                    runOnUiThread {
                        ToastMessage(
                            if (success) {
                                if (ban) "${participant.name} banned" else "${participant.name} kicked"
                            } else {
                                error ?: "Moderation failed"
                            }
                        )
                    }
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun confirmLeaveSession() {
        if (!hasActiveSession || !::multiplayer.isInitialized) return

        AlertDialog.Builder(this)
            .setTitle("Leave session")
            .setMessage(
                if (multiplayer.isHost()) {
                    "Leaving will close the session for all participants. Do you want to continue?"
                } else {
                    "Do you want to leave this multiplayer session?"
                }
            )
            .setPositiveButton("LEAVE") { _, _ -> leaveMultiplayerSession() }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun leaveMultiplayerSession() {
        if (!::multiplayer.isInitialized) return

        leaveSessionButton.apply {
            isEnabled = false
            text = "LEAVING..."
        }

        multiplayer.leaveSession { success, error ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                hasActiveSession = false
                updateSessionCode("")
                updateParticipants(emptyList())

                returnToHome(
                    if (success) "Leaving session..." else "Session disconnected locally",
                    if (!success) "Firebase error: ${error ?: "Unknown error"}" else null
                )
            }
        }
    }

    private fun returnToHome(message: String, toastMessage: String? = null) {
        if (isReturningHome || isFinishing || isDestroyed) return

        isReturningHome = true
        hasActiveSession = false
        activeSessionCode = ""

        updateSessionCode("")
        updateParticipants(emptyList())
        updateStatus(message)
        updateMapHint("Disconnected from session")

        if (::drawingToolbar.isInitialized) drawingToolbar.setDrawingAllowed(false)

        closeSessionMenu()

        toastMessage?.let {
            android.widget.Toast.makeText(this, it, android.widget.Toast.LENGTH_LONG).show()
        }

        startActivity(Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        finish()
    }

    private fun updateSessionCode(code: String) {
        if (!::sessionCodeView.isInitialized) return

        activeSessionCode = code.trim()
        hasActiveSession = activeSessionCode.isNotBlank()

        sessionCodeView.post {
            if (isActivityDestroyed) return@post

            sessionCodeView.apply {
                visibility = if (activeSessionCode.isBlank()) View.GONE else View.VISIBLE
                if (activeSessionCode.isNotBlank()) text = "CODE: $activeSessionCode"
            }

            if (::leaveSessionButton.isInitialized) updateLeaveSessionButton()
        }
    }

    private fun initializeMap() {
        drawingManager = MapDrawingManager(resources.displayMetrics.density)
        mapOverlay = MapOverlayView(this, image, { currentBitmap }, drawingManager).apply {
            pivotX = 0f
            pivotY = 0f
            isClickable = false
            isFocusable = false
        }

        mapContainer.addView(
            mapOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun initializeDrawing() {
        drawingManager.onDrawingChanged = {
            runOnUiThread {
                if (isFinishing || isDestroyed || isActivityDestroyed) return@runOnUiThread

                updateDrawingLegend()
                if (::drawingToolbar.isInitialized) drawingToolbar.updateButtons()
                if (::mapOverlay.isInitialized) mapOverlay.invalidate()
            }
        }

        drawingManager.onLocalOperation = { message ->
            if (::multiplayer.isInitialized) multiplayer.sendDrawingMessage(message)
        }

        drawingToolbar = MapDrawingToolbar(
            context = this,
            container = mapContainer,
            drawingManager = drawingManager,
            onInvalidate = { mapOverlay.invalidate() },
            onExport = {
                if (currentBitmap != null) {
                    createPngDocument.launch("war_thunder_tactical_map.png")
                }
            }
        )

        drawingToolbar.setDrawingAllowed(isHost)
        updateDrawingLegend()
    }

    private fun updateDrawingLegend() {
        if (!::drawingLegend.isInitialized) return

        val entries = drawingManager.getLegendEntries()
        val signature = entries.joinToString("|") {
            "${it.color}:${it.colorName}:${it.description}"
        }

        if (signature == lastDrawingLegendSignature) return
        lastDrawingLegendSignature = signature

        drawingLegend.removeAllViews()

        if (entries.isEmpty()) {
            drawingLegendScroll.visibility = View.INVISIBLE
            return
        }

        drawingLegendScroll.visibility = View.VISIBLE
        val density = resources.displayMetrics.density

        entries.forEach { entry ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((9 * density).toInt(), 0, (12 * density).toInt(), 0)
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    editDrawingLegendDescription(entry.color, entry.colorName, entry.description)
                }
            }

            item.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(entry.color)
                    if (entry.color == Color.WHITE) {
                        setStroke(density.toInt(), Color.LTGRAY)
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    (13 * density).toInt(),
                    (13 * density).toInt()
                )
            })

            item.addView(TextView(this).apply {
                text = if (entry.description.isBlank()) {
                    entry.colorName
                } else {
                    "${entry.colorName} — ${entry.description}"
                }
                setTextColor(Color.rgb(220, 232, 245))
                textSize = 11f
                setPadding((6 * density).toInt(), 0, 0, 0)
                maxLines = 1
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            })

            drawingLegend.addView(item)
        }
    }

    private fun editDrawingLegendDescription(color: Int, colorName: String, currentDescription: String) {
        val density = resources.displayMetrics.density

        val input = EditText(this).apply {
            setText(currentDescription)
            hint = "Enter description..."
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(
                (12 * density).toInt(),
                (12 * density).toInt(),
                (12 * density).toInt(),
                (12 * density).toInt()
            )
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), 0)
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(colorName)
            .setMessage("Enter your own description for this color.")
            .setView(layout)
            .setPositiveButton("SAVE", null)
            .setNegativeButton("CANCEL", null)
            .setNeutralButton("CLEAR", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                drawingManager.setColorDescription(color, input.text.toString())
                dialog.dismiss()
            }

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                drawingManager.setColorDescription(color, "")
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun initializeGestures() {
        scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (drawingManager.activeTool != MapDrawingManager.Tool.NONE) return false

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
            if (drawingManager.activeTool != MapDrawingManager.Tool.NONE) {
                handleDrawingTouch(event)
            } else {
                handleTouch(event)
            }
        }
    }

    private fun initializeApi() {
        apiClient = WarThunderApiClient(applicationContext)
    }

    private fun initializeMultiplayer() {
        multiplayer = MultiplayerSessionManager(
            applicationContext,
            object : MultiplayerSessionManager.Listener {

                override fun onStatus(message: String) {
                    runOnUiThread {
                        if (!isReturningHome && !isActivityDestroyed) updateStatus(message)
                    }
                }

                override fun onSessionCreated(code: String) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed || isReturningHome) return@runOnUiThread

                        hasActiveSession = true
                        updateSessionCode(code)
                        updateStatus("Session: $code")
                        updateMapHint("Share this code: $code")
                        showSessionCodeDialog(code)
                        startSearching()
                    }
                }

                override fun onParticipantJoined(name: String) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed && !isReturningHome) {
                            ToastMessage("$name joined the session")
                        }
                    }
                }

                override fun onParticipantLeft(uid: String) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed && !isReturningHome) {
                            ToastMessage("A participant left the session")
                        }
                    }
                }

                override fun onParticipantsChanged(participants: List<SessionParticipant>) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed || isReturningHome) return@runOnUiThread

                        if (participants.isNotEmpty()) {
                            hasActiveSession = activeSessionCode.isNotBlank()
                        } else if (activeSessionCode.isBlank()) {
                            hasActiveSession = false
                        }

                        updateParticipants(participants)
                    }
                }

                override fun onSessionClosed() {
                    runOnUiThread {
                        if (isFinishing || isDestroyed || isReturningHome) return@runOnUiThread
                        returnToHome("Host closed the session", "Host closed the session")
                    }
                }

                override fun onMapReceived(info: JSONObject, objects: JSONObject, image: ByteArray?) {
                    if (isActivityDestroyed) return

                    val infoCopy = JSONObject(info.toString())
                    val objectsCopy = JSONObject(objects.toString())
                    val imageCopy = image?.copyOf()

                    mapProcessingExecutor.execute {
                        val decodedBitmap = if (imageCopy != null && imageCopy.isNotEmpty()) {
                            BitmapFactory.decodeByteArray(imageCopy, 0, imageCopy.size)
                        } else {
                            null
                        }

                        runOnUiThread {
                            if (isFinishing || isDestroyed || isReturningHome || isActivityDestroyed) {
                                decodedBitmap?.recycle()
                                return@runOnUiThread
                            }

                            if (!infoCopy.optBoolean("valid", false)) {
                                decodedBitmap?.recycle()
                                clearMap()
                                updateMapHint("Waiting for a battle...")
                                updateStatus("Host is waiting for a battle...")
                                return@runOnUiThread
                            }

                            val sharedKey = drawingManager.createMapKey("shared", infoCopy)
                            if (drawingManager.switchLayer(sharedKey)) {
                                drawingToolbar.updateButtons()
                                mapOverlay.invalidate()
                            }

                            if (decodedBitmap != null) {
                                setMapBitmap(decodedBitmap)
                            }

                            applyReceivedObjects(objectsCopy)
                            updateMapHint(if (currentBitmap != null) "" else "Loading map...")
                            updateStatus(
                                if (currentBitmap != null) "Connected: Host"
                                else "Receiving map from host..."
                            )
                        }
                    }
                }

                override fun onObjectsReceived(objects: JSONObject) {
                    if (isActivityDestroyed) return

                    val objectsCopy = JSONObject(objects.toString())

                    mapProcessingExecutor.execute {
                        val parsed = WarThunderMapParser.parse(objectsCopy)

                        runOnUiThread {
                            if (!isFinishing && !isDestroyed && !isReturningHome && !isActivityDestroyed) {
                                applyParsedObjects(objectsCopy, parsed)
                            }
                        }
                    }
                }

                override fun onDrawingMessage(senderUid: String, message: JSONObject) {
                    if (message.optString("type") == "drawingSnapshotRequest") {
                        if (isHost) {
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed && !isReturningHome &&
                                    ::drawingManager.isInitialized
                                ) {
                                    multiplayer.sendDrawingMessage(
                                        drawingManager.createSnapshot(),
                                        senderUid
                                    )
                                }
                            }
                        }
                        return
                    }

                    val messageCopy = JSONObject(message.toString())

                    runOnUiThread {
                        if (isFinishing || isDestroyed || isReturningHome || isActivityDestroyed) return@runOnUiThread
                        drawingManager.applyRemoteMessage(messageCopy)
                    }
                }

                override fun onDrawingPermissionChanged(allowed: Boolean) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed || isReturningHome ||
                            !::drawingToolbar.isInitialized
                        ) return@runOnUiThread

                        drawingToolbar.setDrawingAllowed(allowed)
                        ToastMessage(
                            if (allowed) "You can now draw on the map"
                            else "Your drawing permission was removed"
                        )
                    }
                }

                override fun onRemovedFromSession(reason: String) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed || isReturningHome) return@runOnUiThread
                        returnToHome(reason, reason)
                    }
                }

                override fun onDrawingChannelReady(uid: String) {
                    if (!isHost || isActivityDestroyed) return

                    runOnUiThread {
                        if (isFinishing || isDestroyed || isReturningHome || isActivityDestroyed) {
                            return@runOnUiThread
                        }

                        val info = lastMapInfo?.let { JSONObject(it.toString()) }
                        val objects = lastMapObjects?.let { JSONObject(it.toString()) }
                        val mapImage = lastMapImage?.copyOf()

                        if (info != null && objects != null) {
                            Log.d(TAG, "Sending current map to new participant: $uid")
                            multiplayer.sendMap(info, objects, mapImage, uid)
                        }

                        multiplayer.sendDrawingMessage(drawingManager.createSnapshot(), uid)
                    }
                }

                override fun onError(message: String) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed || isReturningHome || isActivityDestroyed) return@runOnUiThread
                        updateStatus(message)
                        ToastMessage(message)
                    }
                }
            }
        )

        multiplayer.initialize { success ->
            runOnUiThread {
                if (!success || isFinishing || isDestroyed || isReturningHome || isActivityDestroyed) return@runOnUiThread

                drawingManager.setLocalAuthor(multiplayer.getLocalUid(), username)

                if (isHost) {
                    multiplayer.createSession(username)
                } else {
                    if (sessionCode.isBlank()) {
                        updateStatus("Missing session code")
                        return@runOnUiThread
                    }

                    updateSessionCode(sessionCode)
                    multiplayer.joinSession(sessionCode, username)
                }
            }
        }
    }

    private fun showSessionCodeDialog(code: String) {
        if (isFinishing || isDestroyed || isReturningHome) return

        AlertDialog.Builder(this)
            .setTitle("Multiplayer session created")
            .setMessage("Give this code to other players:\n\n$code")
            .setPositiveButton("OK", null)
            .setCancelable(false)
            .show()
    }

    private fun ToastMessage(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun startSearching() {
        if (!isHost || isReturningHome || isActivityDestroyed) return

        updateTask?.cancel(false)
        updateTask = scheduler.scheduleWithFixedDelay({
            try {
                if (isReturningHome || isFinishing || isDestroyed || isActivityDestroyed) {
                    return@scheduleWithFixedDelay
                }

                val client = apiClient ?: return@scheduleWithFixedDelay
                if (client.getServerAddress() == null) discoverServer() else loadMap()
            } catch (e: Exception) {
                Log.e(TAG, "Update loop error", e)
            }
        }, 0, UPDATE_INTERVAL, TimeUnit.MILLISECONDS)
    }

    private fun discoverServer() {
        val client = apiClient ?: return
        if (isDiscovering || deviceSelectionDialogShowing || isReturningHome || isActivityDestroyed) return

        isDiscovering = true
        client.discover { devices ->
            runOnUiThread {
                isDiscovering = false
                if (isFinishing || isDestroyed || isReturningHome || isActivityDestroyed) return@runOnUiThread

                if (devices.isEmpty()) {
                    updateStatus("Searching for War Thunder...")
                } else {
                    showDeviceSelectionDialog(devices)
                }
            }
        }
    }

    private fun showDeviceSelectionDialog(devices: List<WarThunderApiClient.ServerDevice>) {
        if (isFinishing || isDestroyed || devices.isEmpty() || isReturningHome || isActivityDestroyed) return

        deviceSelectionDialog?.dismiss()
        deviceSelectionDialogShowing = true

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }

        devices.forEachIndexed { index, device ->
            layout.addView(TextView(this).apply {
                text = "${device.name}\n${device.address}"
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(16, 16, 16, 16)
                setBackgroundResource(android.R.drawable.list_selector_background)
                isClickable = true
                isFocusable = true

                setOnClickListener {
                    apiClient?.selectServer(device.address)
                    deviceSelectionDialog?.dismiss()
                    clearMap()

                    lastMapInfo = null
                    lastMapObjects = null
                    lastMapImage = null
                    lastSentMapKey = ""
                    lastSentObjectsJson = ""
                    lastLegendSignature = ""
                    lastDrawingLegendSignature = ""

                    updateStatus("Connected: ${device.name}")
                    updateMapHint("Waiting for a battle...")
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))

            if (index < devices.lastIndex) {
                layout.addView(View(this).apply {
                    setBackgroundColor(Color.DKGRAY)
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1
                ))
            }
        }

        deviceSelectionDialog = AlertDialog.Builder(this)
            .setTitle("Select War Thunder device")
            .setView(layout)
            .setCancelable(false)
            .setNegativeButton("CANCEL", null)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener {
                    deviceSelectionDialogShowing = false
                    deviceSelectionDialog = null
                }
                dialog.show()
            }
    }

    private fun changeDevice() {
        if (!isHost || isReturningHome || isActivityDestroyed) return

        deviceSelectionDialog?.dismiss()
        deviceSelectionDialog = null
        deviceSelectionDialogShowing = false

        apiClient?.disconnect()
        isLoading = false
        isDiscovering = false

        lastSentMapKey = ""
        lastSentObjectsJson = ""
        lastMapInfo = null
        lastMapObjects = null
        lastMapImage = null
        lastLegendSignature = ""
        lastDrawingLegendSignature = ""

        clearMap()
        updateStatus("Searching for War Thunder...")
        updateMapHint("Searching for War Thunder...")
        discoverServer()
    }

    private fun loadMap() {
        val client = apiClient ?: return
        if (isLoading || isReturningHome || isActivityDestroyed) return

        isLoading = true
        client.loadMap { data ->
            if (isActivityDestroyed) {
                isLoading = false
                return@loadMap
            }

            runOnUiThread {
                isLoading = false
                if (isFinishing || isDestroyed || isReturningHome || isActivityDestroyed) return@runOnUiThread

                if (data != null && data.address != client.getServerAddress()) return@runOnUiThread

                if (data == null) {
                    if (client.getServerAddress() == null) {
                        updateStatus("Connection lost. Searching again...")
                        updateMapHint("Searching for War Thunder...")
                    }
                    return@runOnUiThread
                }

                val generation = data.info.optInt("map_generation", -1)
                val hudType = data.info.optInt("hud_type", -1)

                if (!data.info.optBoolean("valid", false)) {
                    lastMapInfo = JSONObject(data.info.toString())
                    lastMapObjects = JSONObject()
                    lastMapImage = null

                    if (currentBitmap != null) clearMap()

                    val invalidMapKey = "invalid:$generation:$hudType"
                    if (lastSentMapKey != invalidMapKey) {
                        multiplayer.sendMap(data.info, JSONObject(), null)
                        lastSentMapKey = invalidMapKey
                        lastSentObjectsJson = "{}"
                    }

                    updateMapHint("Waiting for a battle...")
                    updateStatus("Connected: ${getSelectedDeviceName(data.address)}")
                    return@runOnUiThread
                }

                lastMapInfo = JSONObject(data.info.toString())
                lastMapObjects = JSONObject(data.objects.toString())

                val newDrawingKey = drawingManager.createMapKey("shared", data.info)
                if (drawingManager.switchLayer(newDrawingKey)) {
                    mapOverlay.invalidate()
                    drawingToolbar.updateButtons()
                    if (::multiplayer.isInitialized) {
                        multiplayer.sendDrawingMessage(drawingManager.createSnapshot())
                    }
                }

                val shouldUpdateImage = data.image != null && (
                        !hasLoadedImage ||
                                generation != lastGeneration ||
                                hudType != lastHudType
                        )

                if (shouldUpdateImage) {
                    val imageBytes = data.image
                    if (imageBytes != null) {
                        mapProcessingExecutor.execute {
                            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

                            runOnUiThread {
                                if (isFinishing || isDestroyed || isReturningHome || isActivityDestroyed) {
                                    bitmap?.recycle()
                                    return@runOnUiThread
                                }

                                if (bitmap != null) {
                                    setMapBitmap(bitmap)
                                    lastGeneration = generation
                                    lastHudType = hudType
                                    lastMapImage = imageBytes.copyOf()

                                    val mapKey = "$generation:$hudType"
                                    if (lastSentMapKey != mapKey) {
                                        multiplayer.sendMap(data.info, data.objects, imageBytes)
                                        lastSentMapKey = mapKey
                                        lastSentObjectsJson = data.objects.toString()
                                    }
                                }
                            }
                        }
                    }
                } else if (lastMapImage == null && data.image != null) {
                    lastMapImage = data.image.copyOf()
                }

                val objectsCopy = JSONObject(data.objects.toString())
                mapProcessingExecutor.execute {
                    val parsed = WarThunderMapParser.parse(objectsCopy)

                    runOnUiThread {
                        if (isFinishing || isDestroyed || isReturningHome || isActivityDestroyed) {
                            return@runOnUiThread
                        }

                        currentMapData = parsed
                        mapOverlay.setMapData(parsed)
                        updateLegend(parsed)
                        updateDrawingLegend()
                        drawingToolbar.updateButtons()
                        mapOverlay.invalidate()

                        val objectsJson = objectsCopy.toString()
                        if (objectsJson != lastSentObjectsJson) {
                            lastSentObjectsJson = objectsJson
                            multiplayer.sendObjects(objectsCopy)
                        }

                        updateMapHint(if (currentBitmap != null) "" else "Loading map...")
                        updateStatus("Connected: ${getSelectedDeviceName(data.address)}")
                    }
                }
            }
        }
    }

    private fun setMapBitmap(bitmap: Bitmap) {
        val previousBitmap = currentBitmap
        currentBitmap = bitmap
        image.setImageBitmap(bitmap)
        hasLoadedImage = true
        drawingToolbar.setExportVisible(true)
        resetMapTransform()

        image.post {
            if (isActivityDestroyed) return@post
            applyMapTransform()
            mapOverlay.invalidate()
        }

        if (previousBitmap != null && previousBitmap !== bitmap && !previousBitmap.isRecycled) {
            previousBitmap.recycle()
        }
    }

    private fun applyReceivedObjects(objects: JSONObject) {
        val objectsCopy = JSONObject(objects.toString())

        mapProcessingExecutor.execute {
            val parsed = WarThunderMapParser.parse(objectsCopy)

            runOnUiThread {
                if (!isFinishing && !isDestroyed && !isReturningHome && !isActivityDestroyed) {
                    applyParsedObjects(objectsCopy, parsed)
                }
            }
        }
    }

    private fun applyParsedObjects(objects: JSONObject, parsed: MapData) {
        currentMapData = parsed
        mapOverlay.setMapData(parsed)
        updateLegend(parsed)
        drawingToolbar.updateButtons()
        mapOverlay.invalidate()
    }

    private fun getSelectedDeviceName(address: String): String =
        address.removePrefix("http://").substringBefore(":")

    private fun updateLegend(data: MapData) {
        val visibleUnits = data.markers.filter {
            if (data.isAirView) it.type == MarkerType.AIR_UNIT
            else it.type == MarkerType.GROUND_UNIT
        }.distinctBy { it.iconName }

        val signature = visibleUnits
            .sortedBy { it.iconName }
            .joinToString("|") { "${it.iconName}:${it.color}" }

        if (signature == lastLegendSignature) return
        lastLegendSignature = signature

        mapLegend.removeAllViews()

        if (visibleUnits.isEmpty()) {
            mapLegendScroll.visibility = View.INVISIBLE
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

        visibleUnits.forEach { unit ->
            val label = labels[unit.iconName] ?: unit.iconName
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((8 * density).toInt(), 0, (12 * density).toInt(), 0)
            }

            item.addView(ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (19 * density).toInt(),
                    (19 * density).toInt()
                )
                setImageResource(MapIconProvider.getIconRes(unit.iconName))
                setColorFilter(unit.color)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = label
            })

            item.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = (5 * density).toInt()
                }
                text = label
                setTextColor(Color.rgb(220, 232, 245))
                textSize = 10f
                maxLines = 1
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            })

            mapLegend.addView(item)
        }
    }

    private fun clearMap() {
        drawingManager.clearAll()

        currentBitmap?.let {
            if (!it.isRecycled) it.recycle()
        }

        currentBitmap = null
        hasLoadedImage = false
        lastGeneration = -1
        lastHudType = -1
        image.setImageDrawable(null)

        currentMapData = MapData(emptyList(), emptyList(), false)
        mapOverlay.setMapData(currentMapData)

        lastLegendSignature = ""
        lastDrawingLegendSignature = ""

        updateLegend(currentMapData)
        updateDrawingLegend()
        drawingToolbar.closeDrawingTools()
        drawingToolbar.setExportVisible(false)

        resetMapTransform()
        mapOverlay.invalidate()
    }

    private fun handleDrawingTouch(event: MotionEvent): Boolean {
        val mapRect = mapOverlay.getMapRect() ?: return true

        val x = (event.x - translateX) / zoom
        val y = (event.y - translateY) / zoom
        val insideMap = mapRect.contains(x, y)

        val normalizedX = ((x - mapRect.left) / mapRect.width()).coerceIn(0f, 1f)
        val normalizedY = ((y - mapRect.top) / mapRect.height()).coerceIn(0f, 1f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!insideMap) return true
                drawingManager.startStroke(normalizedX, normalizedY)
                mapOverlay.invalidate()
                drawingToolbar.updateButtons()
            }

            MotionEvent.ACTION_MOVE -> {
                if (insideMap) {
                    drawingManager.addPoint(normalizedX, normalizedY)
                    mapOverlay.invalidate()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drawingManager.finishStroke()
                drawingToolbar.updateButtons()
                mapOverlay.invalidate()
            }
        }

        return true
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = true
            }

            MotionEvent.ACTION_POINTER_DOWN -> isDragging = false

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && isDragging &&
                    !scaleDetector.isInProgress && zoom > MIN_ZOOM
                ) {
                    translateX += event.x - lastTouchX
                    translateY += event.y - lastTouchY
                    applyMapTransform()
                }

                lastTouchX = event.x
                lastTouchY = event.y
            }

            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> isDragging = false
        }

        return true
    }

    private fun applyMapTransform() {
        image.apply {
            pivotX = 0f
            pivotY = 0f
            scaleX = zoom
            scaleY = zoom
            translationX = translateX
            translationY = translateY
        }

        mapOverlay.apply {
            pivotX = 0f
            pivotY = 0f
            scaleX = zoom
            scaleY = zoom
            translationX = translateX
            translationY = translateY
            invalidate()
        }
    }

    private fun resetMapTransform() {
        zoom = 1f
        translateX = 0f
        translateY = 0f
        applyMapTransform()
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
            mapOverlay,
            drawingManager.getLegendEntries()
        )

        updateStatus(if (success) "Map exported successfully" else "Map export failed")
        if (success) ToastMessage("Map with drawings exported")
    }

    private fun updateStatus(message: String) {
        if (::status.isInitialized) status.text = message
    }

    private fun updateMapHint(message: String) {
        if (!::searchMapHint.isInitialized) return
        searchMapHint.text = message
        searchMapHint.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
    }

    override fun onDestroy() {
        isActivityDestroyed = true

        deviceSelectionDialog?.dismiss()
        updateTask?.cancel(true)
        scheduler.shutdownNow()
        mapProcessingExecutor.shutdownNow()
        apiClient?.shutdown()

        if (::multiplayer.isInitialized) multiplayer.shutdown()

        currentBitmap?.let {
            if (!it.isRecycled) it.recycle()
        }
        currentBitmap = null

        super.onDestroy()
    }
}