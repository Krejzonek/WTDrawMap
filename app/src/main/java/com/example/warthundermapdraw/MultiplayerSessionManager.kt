package com.example.warthundermapdraw

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

data class SessionParticipant(
    val uid: String,
    val name: String,
    val isHost: Boolean,
    val canDraw: Boolean = false
)

class MultiplayerSessionManager(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onStatus(message: String)
        fun onSessionCreated(code: String)
        fun onParticipantJoined(name: String)
        fun onParticipantLeft(uid: String)

        fun onParticipantsChanged(
            participants: List<SessionParticipant>
        ) {
        }

        fun onSessionClosed() {}

        fun onMapReceived(
            info: JSONObject,
            objects: JSONObject,
            image: ByteArray?
        )

        fun onObjectsReceived(objects: JSONObject)
        fun onError(message: String)

        fun onDrawingMessage(
            senderUid: String,
            message: JSONObject
        ) {
        }

        fun onDrawingChannelReady(uid: String) {}
        fun onDrawingPermissionChanged(allowed: Boolean) {}
        fun onRemovedFromSession(reason: String) {}
    }

    companion object {
        private const val TAG = "WTMultiplayer"
        private const val DATABASE_URL =
            "https://wt-tactical-map-5f0fb-default-rtdb.europe-west1.firebasedatabase.app/"

        private const val MAX_PARTICIPANTS = 8
        private const val CHUNK_SIZE = 12000
        private const val MAX_BUFFERED_AMOUNT = 256L * 1024L
        private const val MAX_MAP_BYTES = 8 * 1024 * 1024
        private const val MAX_MAP_CHUNKS = 700
        private const val MAX_METADATA_BYTES = 512 * 1024
        private const val MAX_MESSAGE_BYTES = 2 * 1024 * 1024
        private const val MAX_QUEUED_BYTES = 12 * 1024 * 1024
    }

    private val auth = FirebaseAuth.getInstance()

    private val database =
        FirebaseDatabase.getInstance(DATABASE_URL).reference

    private var factory: PeerConnectionFactory? = null
    private var roomRef: DatabaseReference? = null
    private var membersListener: ChildEventListener? = null
    private var participantsListener: ValueEventListener? = null
    private var activeSessionListener: ValueEventListener? = null
    private var drawingPermissionListener: ValueEventListener? = null
    private var moderationListener: ValueEventListener? = null

    private val participantDrawingPermissions =
        ConcurrentHashMap<String, Boolean>()

    private val peers =
        ConcurrentHashMap<String, PeerConnection>()

    private val channels =
        ConcurrentHashMap<String, DataChannel>()

    private val outgoingQueues =
        ConcurrentHashMap<String, ConcurrentLinkedQueue<ByteArray>>()

    private val outgoingQueueSizes =
        ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    private data class ValueListenerRegistration(
        val ref: DatabaseReference,
        val listener: ValueEventListener
    )

    private data class ChildListenerRegistration(
        val ref: DatabaseReference,
        val listener: ChildEventListener
    )

    private val peerListeners =
        ConcurrentHashMap<String, MutableList<ValueListenerRegistration>>()

    private val peerChildListeners =
        ConcurrentHashMap<String, MutableList<ChildListenerRegistration>>()

    private val pendingIceCandidates =
        ConcurrentHashMap<String, MutableList<IceCandidate>>()

    private val remoteDescriptionPending =
        ConcurrentHashMap.newKeySet<String>()

    private var sessionCode: String? = null
    private var localUid: String? = null

    @Volatile
    private var isHost = false

    @Volatile
    private var isClosed = false

    @Volatile
    private var lastModerationTimestamp = 0L

    private data class IncomingMap(
        val info: JSONObject,
        val objects: JSONObject,
        val chunks: MutableMap<Int, ByteArray>,
        val totalChunks: Int
    )

    private val incomingMaps =
        ConcurrentHashMap<String, IncomingMap>()

    fun getLocalUid(): String {
        return localUid ?: "local"
    }

    fun isHost(): Boolean {
        return isHost
    }

    fun getSessionCode(): String? {
        return sessionCode
    }

    fun initialize(onReady: (Boolean) -> Unit) {
        isClosed = false

        val currentUser = auth.currentUser

        if (currentUser != null) {
            localUid = currentUser.uid

            try {
                initializeWebRtc()
                onReady(true)
            } catch (e: Exception) {
                Log.e(TAG, "WebRTC initialization failed", e)
                listener.onError("WebRTC initialization failed: ${e.message}")
                onReady(false)
            }

            return
        }

        auth.signInAnonymously()
            .addOnSuccessListener { result ->
                localUid = result.user?.uid

                try {
                    initializeWebRtc()
                    Log.d(TAG, "Firebase authenticated: $localUid")
                    onReady(localUid != null)
                } catch (e: Exception) {
                    Log.e(TAG, "WebRTC initialization failed", e)
                    listener.onError("WebRTC initialization failed: ${e.message}")
                    onReady(false)
                }
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "Firebase authentication failed", error)
                listener.onError("Firebase authentication failed: ${error.message}")
                onReady(false)
            }
    }

    private fun initializeWebRtc() {
        if (factory != null) return

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(context.applicationContext)
                .createInitializationOptions()
        )

        factory = PeerConnectionFactory.builder()
            .createPeerConnectionFactory()

        Log.d(TAG, "WebRTC initialized")
    }

    fun createSession(username: String) {
        if (localUid == null || factory == null) {
            listener.onError("Multiplayer is not initialized")
            return
        }

        if (sessionCode != null) {
            listener.onError("You are already in a session")
            return
        }

        isClosed = false
        createUniqueCode(0, username)
    }

    private fun createUniqueCode(
        attempt: Int,
        username: String
    ) {
        if (attempt >= 10) {
            listener.onError("Could not create a unique session code")
            return
        }

        val uid = localUid ?: return

        val code = "WT-" + UUID.randomUUID()
            .toString()
            .replace("-", "")
            .take(6)
            .uppercase()

        val ref = database
            .child("rooms")
            .child(code)

        ref.runTransaction(
            object : Transaction.Handler {

                override fun doTransaction(
                    currentData: MutableData
                ): Transaction.Result {
                    if (currentData.value != null) {
                        return Transaction.abort()
                    }

                    currentData.value = mapOf(
                        "host" to uid,
                        "hostName" to username,
                        "createdAt" to System.currentTimeMillis(),
                        "active" to true,
                        "members" to mapOf(
                            uid to mapOf(
                                "name" to username,
                                "host" to true,
                                "canDraw" to true
                            )
                        )
                    )

                    return Transaction.success(currentData)
                }

                override fun onComplete(
                    error: DatabaseError?,
                    committed: Boolean,
                    snapshot: DataSnapshot?
                ) {
                    if (error != null) {
                        Log.e(TAG, "Session creation Firebase error", error.toException())
                        listener.onError("Firebase error: ${error.message}")
                        return
                    }

                    if (!committed) {
                        createUniqueCode(attempt + 1, username)
                        return
                    }

                    if (isClosed) {
                        ref.child("active").setValue(false)
                        ref.child("members").removeValue()
                        return
                    }

                    sessionCode = code
                    roomRef = ref
                    isHost = true

                    Log.d(TAG, "Session created: $code")

                    listenForMembers()
                    listenForParticipants()

                    listener.onSessionCreated(code)
                    listener.onStatus("Waiting for participants...")
                }
            }
        )
    }

    fun joinSession(
        codeInput: String,
        username: String
    ) {
        if (localUid == null || factory == null) {
            listener.onError("Multiplayer is not initialized")
            return
        }

        if (sessionCode != null) {
            listener.onError("You are already in a session")
            return
        }

        val code = codeInput.trim().uppercase()

        if (!code.matches(Regex("WT-[A-Z0-9]{6}"))) {
            listener.onError("Invalid session code")
            return
        }

        val uid = localUid ?: run {
            listener.onError("Firebase authentication is missing")
            return
        }

        val ref = database.child("rooms").child(code)

        listener.onStatus("Checking session...")

        ref.get()
            .addOnSuccessListener { snapshot ->
                if (isClosed) return@addOnSuccessListener

                if (!snapshot.exists()) {
                    listener.onError("Session does not exist")
                    return@addOnSuccessListener
                }

                val hostUid = snapshot.child("host")
                    .getValue(String::class.java)

                if (hostUid.isNullOrBlank()) {
                    listener.onError("Invalid session: host not found")
                    return@addOnSuccessListener
                }

                val active = snapshot.child("active")
                    .getValue(Boolean::class.java) == true

                if (!active) {
                    listener.onError("Session is closed")
                    return@addOnSuccessListener
                }

                if (!snapshot.child("members").hasChild(hostUid)) {
                    listener.onError("Invalid session: host is not a member")
                    return@addOnSuccessListener
                }

                if (snapshot.child("bans").hasChild(uid)) {
                    listener.onError("You are banned from this session")
                    return@addOnSuccessListener
                }

                lastModerationTimestamp = snapshot.child("moderation")
                    .child(uid)
                    .child("timestamp")
                    .getValue(Long::class.java) ?: 0L

                Log.d(
                    TAG,
                    "Previous moderation timestamp for $uid: $lastModerationTimestamp"
                )

                val members = snapshot.child("members")
                val alreadyMember = members.hasChild(uid)

                if (
                    members.childrenCount >= MAX_PARTICIPANTS &&
                    !alreadyMember
                ) {
                    listener.onError("Session is full")
                    return@addOnSuccessListener
                }

                val memberRef = ref.child("members").child(uid)

                memberRef.setValue(
                    mapOf(
                        "name" to username,
                        "host" to false,
                        "canDraw" to false
                    )
                )
                    .addOnSuccessListener {
                        if (isClosed) {
                            memberRef.removeValue()
                            return@addOnSuccessListener
                        }

                        sessionCode = code
                        roomRef = ref
                        isHost = false

                        Log.d(TAG, "Joined session $code as $uid")

                        listener.onStatus("Joining session...")

                        listenForParticipants()
                        listenForSessionState()
                        listenForOwnPermissions()
                        listenForModeration()
                        listenForHostOffer()
                    }
                    .addOnFailureListener { error ->
                        Log.e(TAG, "Session join failed", error)

                        listener.onError(
                            "Could not join session: ${error.message}"
                        )
                    }
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "Session lookup failed", error)

                listener.onError(
                    "Could not check session: ${error.message}"
                )
            }
    }

    private fun listenForParticipants() {
        val ref = roomRef?.child("members") ?: return

        participantsListener?.let {
            ref.removeEventListener(it)
        }

        val valueListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (isClosed) return

                val participants = snapshot.children.mapNotNull { child ->
                    val uid = child.key ?: return@mapNotNull null

                    val name = child.child("name")
                        .getValue(String::class.java) ?: "Player"

                    val host = child.child("host")
                        .getValue(Boolean::class.java) ?: false

                    val canDraw = child.child("canDraw")
                        .getValue(Boolean::class.java) ?: false

                    SessionParticipant(
                        uid = uid,
                        name = name,
                        isHost = host,
                        canDraw = host || canDraw
                    )
                }.sortedWith(
                    compareByDescending<SessionParticipant> { it.isHost }
                        .thenBy { it.name.lowercase() }
                )

                if (isHost) {
                    participantDrawingPermissions.clear()

                    participants.forEach { participant ->
                        participantDrawingPermissions[participant.uid] =
                            participant.isHost || participant.canDraw
                    }
                }

                listener.onParticipantsChanged(participants)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Participants listener cancelled", error.toException())
            }
        }

        participantsListener = valueListener
        ref.addValueEventListener(valueListener)
    }

    private fun listenForOwnPermissions() {
        val uid = localUid ?: return
        val ref = roomRef?.child("members")?.child(uid)?.child("canDraw") ?: return

        drawingPermissionListener?.let {
            ref.removeEventListener(it)
        }

        val valueListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (isClosed || isHost) return

                val allowed = snapshot.getValue(Boolean::class.java) ?: false
                listener.onDrawingPermissionChanged(allowed)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Drawing permission listener failed", error.toException())
            }
        }

        drawingPermissionListener = valueListener
        ref.addValueEventListener(valueListener)
    }

    private fun listenForModeration() {
        val uid = localUid ?: return
        val ref = roomRef?.child("moderation")?.child(uid) ?: return

        moderationListener?.let {
            ref.removeEventListener(it)
        }

        val valueListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (isClosed || isHost || !snapshot.exists()) return

                val action = snapshot.child("action")
                    .getValue(String::class.java) ?: return

                val timestamp = snapshot.child("timestamp")
                    .getValue(Long::class.java) ?: return

                if (timestamp <= lastModerationTimestamp) {
                    Log.d(
                        TAG,
                        "Ignoring old moderation event: $action, timestamp: $timestamp"
                    )
                    return
                }

                lastModerationTimestamp = timestamp

                val reason = when (action) {
                    "ban" -> "You have been banned from this session"
                    "kick" -> "You have been kicked from this session"
                    else -> return
                }

                Log.d(
                    TAG,
                    "New moderation event: $action, timestamp: $timestamp"
                )

                isClosed = true
                cleanupLocalSession()
                listener.onRemovedFromSession(reason)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Moderation listener failed", error.toException())
            }
        }

        moderationListener = valueListener
        ref.addValueEventListener(valueListener)
    }

    fun setParticipantDrawingPermission(
        uid: String,
        allowed: Boolean,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        if (!isHost || isClosed) {
            onComplete?.invoke(false, "Only the host can change permissions")
            return
        }

        if (uid.isBlank() || uid == localUid) {
            onComplete?.invoke(false, "Invalid participant")
            return
        }

        val ref = roomRef?.child("members")?.child(uid) ?: run {
            onComplete?.invoke(false, "Session is not available")
            return
        }

        ref.get()
            .addOnSuccessListener { snapshot ->
                if (
                    !snapshot.exists() ||
                    snapshot.child("host").getValue(Boolean::class.java) == true
                ) {
                    onComplete?.invoke(false, "Participant is not available")
                    return@addOnSuccessListener
                }

                ref.child("canDraw").setValue(allowed)
                    .addOnSuccessListener {
                        participantDrawingPermissions[uid] = allowed
                        onComplete?.invoke(true, null)
                    }
                    .addOnFailureListener { error ->
                        onComplete?.invoke(false, error.message ?: "Firebase error")
                    }
            }
            .addOnFailureListener { error ->
                onComplete?.invoke(false, error.message ?: "Firebase error")
            }
    }

    fun moderateParticipant(
        uid: String,
        ban: Boolean,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        if (!isHost || isClosed) {
            onComplete?.invoke(false, "Only the host can moderate participants")
            return
        }

        if (uid.isBlank() || uid == localUid) {
            onComplete?.invoke(false, "Cannot moderate this participant")
            return
        }

        val ref = roomRef ?: run {
            onComplete?.invoke(false, "Session is not available")
            return
        }

        ref.child("members").child(uid).get()
            .addOnSuccessListener { snapshot ->
                if (
                    !snapshot.exists() ||
                    snapshot.child("host").getValue(Boolean::class.java) == true
                ) {
                    onComplete?.invoke(false, "Participant is not available")
                    return@addOnSuccessListener
                }

                val timestamp = System.currentTimeMillis()
                val action = if (ban) "ban" else "kick"

                val updates = mutableMapOf<String, Any?>(
                    "moderation/$uid" to mapOf(
                        "action" to action,
                        "timestamp" to timestamp
                    ),
                    "members/$uid" to null,
                    "signals/$uid" to null
                )

                if (ban) {
                    updates["bans/$uid"] = mapOf(
                        "timestamp" to timestamp
                    )
                }

                ref.updateChildren(updates)
                    .addOnSuccessListener {
                        participantDrawingPermissions.remove(uid)
                        closePeer(uid)

                        Log.d(
                            TAG,
                            "Participant removed: $uid, action: $action"
                        )

                        onComplete?.invoke(true, null)
                    }
                    .addOnFailureListener { error ->
                        Log.e(TAG, "Participant moderation failed", error)

                        onComplete?.invoke(
                            false,
                            error.message ?: "Could not moderate participant"
                        )
                    }
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "Participant lookup failed", error)

                onComplete?.invoke(
                    false,
                    error.message ?: "Firebase error"
                )
            }
    }

    private fun listenForSessionState() {
        val ref = roomRef?.child("active") ?: return

        activeSessionListener?.let {
            ref.removeEventListener(it)
        }

        val valueListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (isClosed || isHost) return

                val active = snapshot.getValue(Boolean::class.java) ?: false

                if (!active) {
                    Log.d(TAG, "Host closed the session")

                    isClosed = true
                    cleanupLocalSession()

                    listener.onStatus("Host closed the session")
                    listener.onSessionClosed()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Session state listener cancelled", error.toException())
            }
        }

        activeSessionListener = valueListener
        ref.addValueEventListener(valueListener)
    }

    private fun listenForMembers() {
        val ref = roomRef ?: return

        val childListener = object : ChildEventListener {
            override fun onChildAdded(
                snapshot: DataSnapshot,
                previousChildName: String?
            ) {
                val uid = snapshot.key ?: return

                if (uid == localUid || isClosed) return

                val name = snapshot.child("name")
                    .getValue(String::class.java) ?: "Player"

                Log.d(TAG, "Participant joined: $uid ($name)")
                listener.onParticipantJoined(name)

                createOfferForParticipant(uid)
            }

            override fun onChildChanged(
                snapshot: DataSnapshot,
                previousChildName: String?
            ) {
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                val uid = snapshot.key ?: return

                Log.d(TAG, "Participant left: $uid")
                closePeer(uid)
                participantDrawingPermissions.remove(uid)
                listener.onParticipantLeft(uid)
            }

            override fun onChildMoved(
                snapshot: DataSnapshot,
                previousChildName: String?
            ) {
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Members listener cancelled", error.toException())
                listener.onError(error.message)
            }
        }

        membersListener = childListener
        ref.child("members").addChildEventListener(childListener)
    }

    private fun createPeer(
        uid: String,
        onRemoteDataChannel: (DataChannel) -> Unit
    ): PeerConnection? {
        val rtcFactory = factory ?: return null

        val iceServers = listOf(
            PeerConnection.IceServer.builder(
                "stun:stun.l.google.com:19302"
            ).createIceServer(),
            PeerConnection.IceServer.builder(
                "stun:stun1.l.google.com:19302"
            ).createIceServer()
        )

        val configuration =
            PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }

        return try {
            val peer = rtcFactory.createPeerConnection(
                configuration,
                object : PeerConnection.Observer {
                    override fun onSignalingChange(
                        state: PeerConnection.SignalingState
                    ) {
                        Log.d(TAG, "[$uid] Signaling state: $state")
                    }

                    override fun onIceConnectionChange(
                        state: PeerConnection.IceConnectionState
                    ) {
                        Log.d(TAG, "[$uid] ICE connection state: $state")

                        when (state) {
                            PeerConnection.IceConnectionState.CONNECTED,
                            PeerConnection.IceConnectionState.COMPLETED -> {
                                listener.onStatus("Connected to multiplayer session")
                            }

                            PeerConnection.IceConnectionState.DISCONNECTED -> {
                                listener.onStatus("Connection interrupted")
                            }

                            PeerConnection.IceConnectionState.FAILED -> {
                                listener.onError(
                                    "WebRTC connection failed. Check ICE/TURN configuration."
                                )
                            }

                            PeerConnection.IceConnectionState.CLOSED -> {
                                Log.d(TAG, "[$uid] ICE connection closed")
                            }

                            else -> {
                            }
                        }
                    }

                    override fun onIceConnectionReceivingChange(receiving: Boolean) {
                        Log.d(TAG, "[$uid] ICE receiving: $receiving")
                    }

                    override fun onIceGatheringChange(
                        state: PeerConnection.IceGatheringState
                    ) {
                        Log.d(TAG, "[$uid] ICE gathering state: $state")
                    }

                    override fun onIceCandidate(candidate: IceCandidate) {
                        Log.d(TAG, "[$uid] Local ICE candidate: ${candidate.sdp}")
                        sendIceCandidate(uid, candidate)
                    }

                    override fun onIceCandidatesRemoved(
                        candidates: Array<out IceCandidate>
                    ) {
                        Log.d(TAG, "[$uid] ICE candidates removed: ${candidates.size}")
                    }

                    override fun onAddStream(stream: org.webrtc.MediaStream) {
                    }

                    override fun onRemoveStream(stream: org.webrtc.MediaStream) {
                    }

                    override fun onDataChannel(channel: DataChannel) {
                        Log.d(TAG, "[$uid] Remote DataChannel received")
                        channels[uid] = channel
                        registerDataChannel(uid, channel)
                        onRemoteDataChannel(channel)
                    }

                    override fun onRenegotiationNeeded() {
                        Log.d(TAG, "[$uid] Renegotiation needed")
                    }

                    override fun onAddTrack(
                        receiver: org.webrtc.RtpReceiver,
                        mediaStreams: Array<out org.webrtc.MediaStream>
                    ) {
                    }
                }
            )

            if (peer == null) {
                Log.e(TAG, "[$uid] createPeerConnection returned null")
                return null
            }

            peers[uid] = peer
            Log.d(TAG, "[$uid] PeerConnection created")
            peer
        } catch (e: Exception) {
            Log.e(TAG, "[$uid] PeerConnection creation failed", e)
            null
        }
    }

    private fun createOfferForParticipant(uid: String) {
        if (isClosed || !isHost) return

        if (peers.containsKey(uid)) {
            Log.d(TAG, "[$uid] Peer already exists")
            return
        }

        val memberRef = roomRef?.child("members")?.child(uid) ?: return

        memberRef.get().addOnSuccessListener { member ->
            if (isClosed || !member.exists()) return@addOnSuccessListener

            if (member.child("host").getValue(Boolean::class.java) == true) {
                return@addOnSuccessListener
            }

            createOfferForConfirmedParticipant(uid)
        }.addOnFailureListener { error ->
            Log.e(TAG, "[$uid] Could not verify participant", error)
        }
    }

    private fun createOfferForConfirmedParticipant(uid: String) {
        if (isClosed || !isHost || peers.containsKey(uid)) return

        val peer = createPeer(uid) {}
            ?: run {
                listener.onError("Could not create WebRTC connection")
                return
            }

        val channel = peer.createDataChannel(
            "wt-map",
            DataChannel.Init().apply {
                ordered = true
            }
        )

        if (channel == null) {
            listener.onError("Could not create DataChannel")
            closePeer(uid)
            return
        }

        channels[uid] = channel
        registerDataChannel(uid, channel)

        Log.d(TAG, "[$uid] Creating offer")

        peer.createOffer(
            object : SdpObserverAdapter() {
                override fun onCreateSuccess(description: SessionDescription) {
                    if (isClosed) return

                    peer.setLocalDescription(
                        object : SdpObserverAdapter() {
                            override fun onSetSuccess() {
                                if (isClosed) return

                                val signal = roomRef
                                    ?.child("signals")
                                    ?.child(uid)

                                if (signal == null) {
                                    listener.onError("Could not access signaling database")
                                    return
                                }

                                listenForAnswer(uid, peer)
                                listenForRemoteCandidates(
                                    uid,
                                    "participantCandidates",
                                    peer
                                )

                                signal.child("offer").setValue(
                                    mapOf(
                                        "type" to description.type.canonicalForm(),
                                        "sdp" to description.description
                                    )
                                )
                                    .addOnSuccessListener {
                                        if (isClosed) return@addOnSuccessListener
                                        listener.onStatus("Offer sent. Waiting for answer...")
                                    }
                                    .addOnFailureListener { error ->
                                        Log.e(TAG, "[$uid] Offer send failed", error)
                                        listener.onError("Could not send offer: ${error.message}")
                                    }
                            }

                            override fun onSetFailure(error: String) {
                                Log.e(TAG, "[$uid] Setting local offer failed: $error")
                                listener.onError("Could not set local offer: $error")
                            }
                        },
                        description
                    )
                }

                override fun onCreateFailure(error: String) {
                    Log.e(TAG, "[$uid] Offer creation failed: $error")
                    listener.onError("Offer creation failed: $error")
                }
            },
            MediaConstraints()
        )
    }

    private fun listenForAnswer(
        uid: String,
        peer: PeerConnection
    ) {
        val ref = roomRef
            ?.child("signals")
            ?.child(uid)
            ?.child("answer") ?: return

        val valueListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (isClosed) return

                val sdp = snapshot.child("sdp")
                    .getValue(String::class.java) ?: return

                val type = snapshot.child("type")
                    .getValue(String::class.java) ?: "answer"

                if (peer.remoteDescription != null) return
                if (!remoteDescriptionPending.add(uid)) return

                peer.setRemoteDescription(
                    object : SdpObserverAdapter() {
                        override fun onSetSuccess() {
                            remoteDescriptionPending.remove(uid)
                            flushPendingIceCandidates(uid, peer)
                        }

                        override fun onSetFailure(error: String) {
                            remoteDescriptionPending.remove(uid)
                            Log.e(TAG, "[$uid] Setting remote answer failed: $error")
                            listener.onError("Remote answer failed: $error")
                        }
                    },
                    SessionDescription(
                        SessionDescription.Type.fromCanonicalForm(type),
                        sdp
                    )
                )
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "[$uid] Answer listener cancelled", error.toException())
                listener.onError("Answer listener error: ${error.message}")
            }
        }

        ref.addValueEventListener(valueListener)

        peerListeners.getOrPut(uid) {
            Collections.synchronizedList(mutableListOf())
        }.add(ValueListenerRegistration(ref, valueListener))
    }

    private fun listenForHostOffer() {
        val uid = localUid ?: return

        val ref = roomRef
            ?.child("signals")
            ?.child(uid) ?: return

        val offerRef = ref.child("offer")

        val offerListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (isClosed || isHost) return

                val sdp = snapshot.child("sdp")
                    .getValue(String::class.java) ?: return

                val type = snapshot.child("type")
                    .getValue(String::class.java) ?: "offer"

                if (peers.containsKey("host")) return
                if (!remoteDescriptionPending.add("host")) return

                listener.onStatus("Offer received. Creating connection...")

                val peer = createPeer("host") {}
                    ?: run {
                        remoteDescriptionPending.remove("host")
                        listener.onError("Could not create WebRTC connection")
                        return
                    }

                listenForRemoteCandidates("host", "hostCandidates", peer)

                peer.setRemoteDescription(
                    object : SdpObserverAdapter() {
                        override fun onSetSuccess() {
                            remoteDescriptionPending.remove("host")
                            flushPendingIceCandidates("host", peer)

                            peer.createAnswer(
                                object : SdpObserverAdapter() {
                                    override fun onCreateSuccess(
                                        description: SessionDescription
                                    ) {
                                        if (isClosed) return

                                        peer.setLocalDescription(
                                            object : SdpObserverAdapter() {
                                                override fun onSetSuccess() {
                                                    if (isClosed) return

                                                    ref.child("answer")
                                                        .setValue(
                                                            mapOf(
                                                                "type" to description.type.canonicalForm(),
                                                                "sdp" to description.description
                                                            )
                                                        )
                                                        .addOnSuccessListener {
                                                            if (!isClosed) {
                                                                listener.onStatus("Answer sent. Connecting...")
                                                            }
                                                        }
                                                        .addOnFailureListener { error ->
                                                            Log.e(
                                                                TAG,
                                                                "[host] Answer send failed",
                                                                error
                                                            )
                                                            listener.onError(
                                                                "Could not send answer: ${error.message}"
                                                            )
                                                        }
                                                }

                                                override fun onSetFailure(error: String) {
                                                    Log.e(
                                                        TAG,
                                                        "[host] Setting local answer failed: $error"
                                                    )
                                                    listener.onError("Could not set local answer: $error")
                                                }
                                            },
                                            description
                                        )
                                    }

                                    override fun onCreateFailure(error: String) {
                                        Log.e(TAG, "[host] Answer creation failed: $error")
                                        listener.onError("Answer creation failed: $error")
                                    }
                                },
                                MediaConstraints()
                            )
                        }

                        override fun onSetFailure(error: String) {
                            remoteDescriptionPending.remove("host")
                            Log.e(TAG, "[host] Setting remote offer failed: $error")
                            listener.onError("Could not set offer: $error")
                        }
                    },
                    SessionDescription(
                        SessionDescription.Type.fromCanonicalForm(type),
                        sdp
                    )
                )
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Offer listener cancelled", error.toException())
                listener.onError("Offer listener error: ${error.message}")
            }
        }

        offerRef.addValueEventListener(offerListener)

        peerListeners.getOrPut("host") {
            Collections.synchronizedList(mutableListOf())
        }.add(ValueListenerRegistration(offerRef, offerListener))
    }

    private fun sendIceCandidate(
        uid: String,
        candidate: IceCandidate
    ) {
        if (isClosed) return

        val code = sessionCode ?: return
        val local = localUid ?: return

        val path = if (isHost) {
            database.child("rooms")
                .child(code)
                .child("signals")
                .child(uid)
                .child("hostCandidates")
        } else {
            database.child("rooms")
                .child(code)
                .child("signals")
                .child(local)
                .child("participantCandidates")
        }

        path.push().setValue(
            mapOf(
                "sdpMid" to candidate.sdpMid,
                "sdpMLineIndex" to candidate.sdpMLineIndex,
                "sdp" to candidate.sdp
            )
        )
            .addOnFailureListener { error ->
                Log.e(TAG, "[$uid] ICE candidate send failed", error)

                if (!isClosed) {
                    listener.onError("Could not send ICE candidate: ${error.message}")
                }
            }
    }

    private fun addIceCandidateSafely(
        uid: String,
        peer: PeerConnection,
        candidate: IceCandidate
    ) {
        synchronized(pendingIceCandidates) {
            if (peer.remoteDescription != null) {
                val added = peer.addIceCandidate(candidate)
                Log.d(TAG, "[$uid] ICE candidate added immediately: $added")
            } else {
                val candidates = pendingIceCandidates.getOrPut(uid) {
                    Collections.synchronizedList(mutableListOf())
                }

                synchronized(candidates) {
                    if (candidates.size < 256) {
                        candidates.add(candidate)
                    } else {
                        Log.w(TAG, "[$uid] Pending ICE candidate limit reached")
                    }
                }
            }
        }
    }

    private fun flushPendingIceCandidates(
        uid: String,
        peer: PeerConnection
    ) {
        synchronized(pendingIceCandidates) {
            val candidates = pendingIceCandidates.remove(uid) ?: return

            synchronized(candidates) {
                candidates.forEach { candidate ->
                    peer.addIceCandidate(candidate)
                }

                candidates.clear()
            }
        }
    }

    private fun listenForRemoteCandidates(
        uid: String,
        candidatePath: String,
        peer: PeerConnection
    ) {
        val code = sessionCode ?: return

        val pathUid = if (isHost) {
            uid
        } else {
            localUid ?: return
        }

        val ref = database.child("rooms")
            .child(code)
            .child("signals")
            .child(pathUid)
            .child(candidatePath)

        val childListener = object : ChildEventListener {
            override fun onChildAdded(
                snapshot: DataSnapshot,
                previousChildName: String?
            ) {
                if (isClosed) return

                val sdp = snapshot.child("sdp")
                    .getValue(String::class.java) ?: return

                val mid = snapshot.child("sdpMid")
                    .getValue(String::class.java)

                val line = snapshot.child("sdpMLineIndex")
                    .getValue(Int::class.java) ?: 0

                addIceCandidateSafely(
                    uid,
                    peer,
                    IceCandidate(mid, line, sdp)
                )
            }

            override fun onChildChanged(
                snapshot: DataSnapshot,
                previousChildName: String?
            ) {
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
            }

            override fun onChildMoved(
                snapshot: DataSnapshot,
                previousChildName: String?
            ) {
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "[$uid] ICE candidate listener cancelled", error.toException())

                if (!isClosed) {
                    listener.onError("ICE listener error: ${error.message}")
                }
            }
        }

        ref.addChildEventListener(childListener)

        peerChildListeners.getOrPut(uid) {
            Collections.synchronizedList(mutableListOf())
        }.add(ChildListenerRegistration(ref, childListener))
    }

    private fun registerDataChannel(
        uid: String,
        channel: DataChannel
    ) {
        outgoingQueues.putIfAbsent(uid, ConcurrentLinkedQueue())
        outgoingQueueSizes.putIfAbsent(
            uid,
            java.util.concurrent.atomic.AtomicLong(0L)
        )

        channel.registerObserver(
            object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) {
                    drainOutgoingQueue(uid)
                }

                override fun onStateChange() {
                    val state = channel.state()
                    Log.d(TAG, "[$uid] DataChannel state: $state")

                    if (state == DataChannel.State.OPEN) {
                        drainOutgoingQueue(uid)
                        listener.onStatus("Data channel connected")
                        listener.onDrawingChannelReady(uid)

                        if (!isHost) {
                            sendDrawingMessage(
                                JSONObject().put("type", "drawingSnapshotRequest")
                            )
                        }
                    }

                    if (state == DataChannel.State.CLOSED) {
                        Log.d(TAG, "[$uid] DataChannel closed")
                    }
                }

                override fun onMessage(buffer: DataChannel.Buffer) {
                    if (buffer.binary) return

                    val size = buffer.data.remaining()

                    if (size <= 0 || size > MAX_MESSAGE_BYTES) {
                        Log.w(TAG, "[$uid] Incoming message rejected. Size: $size")
                        return
                    }

                    val bytes = ByteArray(size)
                    buffer.data.get(bytes)

                    handleIncomingMessage(
                        uid,
                        String(bytes, Charsets.UTF_8)
                    )
                }
            }
        )

        if (channel.state() == DataChannel.State.OPEN) {
            listener.onDrawingChannelReady(uid)
        }
    }

    private fun enqueueMessage(
        uid: String,
        message: String
    ) {
        if (isClosed) return

        val channel = channels[uid] ?: run {
            Log.w(TAG, "[$uid] Cannot queue message. DataChannel missing.")
            return
        }

        if (message.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES) {
            Log.w(TAG, "[$uid] Outgoing message is too large")
            return
        }

        val bytes = message.toByteArray(Charsets.UTF_8)
        val queue = outgoingQueues.getOrPut(uid) {
            ConcurrentLinkedQueue()
        }

        val sizeCounter = outgoingQueueSizes.getOrPut(uid) {
            java.util.concurrent.atomic.AtomicLong(0L)
        }

        val newSize = sizeCounter.addAndGet(bytes.size.toLong())

        if (newSize > MAX_QUEUED_BYTES) {
            sizeCounter.addAndGet(-bytes.size.toLong())
            Log.w(TAG, "[$uid] Outgoing queue limit reached")
            listener.onError("Data transfer queue is full")
            return
        }

        queue.add(bytes)
        drainOutgoingQueue(uid)
    }

    private fun drainOutgoingQueue(uid: String) {
        if (isClosed) return

        val channel = channels[uid] ?: return
        val queue = outgoingQueues[uid] ?: return
        val sizeCounter = outgoingQueueSizes[uid] ?: return

        synchronized(channel) {
            if (channel.state() != DataChannel.State.OPEN) return

            while (channel.bufferedAmount() < MAX_BUFFERED_AMOUNT) {
                val bytes = queue.peek() ?: break

                val sent = try {
                    channel.send(
                        DataChannel.Buffer(
                            ByteBuffer.wrap(bytes),
                            false
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "[$uid] DataChannel send exception", e)
                    false
                }

                if (!sent) {
                    Log.w(TAG, "[$uid] DataChannel buffer full. Waiting.")
                    break
                }

                queue.poll()
                sizeCounter.addAndGet(-bytes.size.toLong())
            }
        }
    }

    private fun handleIncomingMessage(
        senderUid: String,
        message: String
    ) {
        try {
            val json = JSONObject(message)
            val type = json.optString("type")

            if (!isHost && senderUid != "host") {
                Log.w(TAG, "Rejected message from non-host peer: $senderUid")
                return
            }

            if (isHost && senderUid == "host") {
                Log.w(TAG, "Rejected invalid host peer message")
                return
            }

            when (type) {
                "objects" -> {
                    if (!isHost) {
                        val data = json.optString("data", "{}")
                        if (data.toByteArray(Charsets.UTF_8).size > MAX_METADATA_BYTES) return

                        listener.onObjectsReceived(JSONObject(data))
                    }
                }

                "mapStart" -> {
                    if (isHost) return

                    val id = json.getString("id")
                    val infoString = json.getString("info")
                    val objectsString = json.getString("objects")
                    val total = json.getInt("total")

                    if (
                        total < 0 ||
                        total > MAX_MAP_CHUNKS ||
                        infoString.toByteArray(Charsets.UTF_8).size > MAX_METADATA_BYTES ||
                        objectsString.toByteArray(Charsets.UTF_8).size > MAX_METADATA_BYTES
                    ) {
                        Log.w(TAG, "Rejected invalid map transfer metadata")
                        return
                    }

                    if (incomingMaps.size >= 2 && !incomingMaps.containsKey(id)) {
                        incomingMaps.clear()
                    }

                    incomingMaps[id] = IncomingMap(
                        JSONObject(infoString),
                        JSONObject(objectsString),
                        ConcurrentHashMap<Int, ByteArray>(),
                        total
                    )

                    Log.d(TAG, "Incoming map started: $id, chunks: $total")
                }

                "mapChunk" -> {
                    if (isHost) return

                    val id = json.getString("id")
                    val map = incomingMaps[id] ?: return
                    val index = json.getInt("index")

                    if (index < 0 || index >= map.totalChunks) {
                        Log.w(TAG, "Invalid map chunk index: $index")
                        return
                    }

                    val encoded = json.getString("data")

                    if (encoded.length > CHUNK_SIZE) {
                        incomingMaps.remove(id)
                        Log.w(TAG, "Map chunk is too large")
                        return
                    }

                    val data = Base64.decode(encoded, Base64.NO_WRAP)

                    if (data.size > CHUNK_SIZE) {
                        incomingMaps.remove(id)
                        return
                    }

                    map.chunks[index] = data
                }

                "mapEnd" -> {
                    if (isHost) return

                    val id = json.getString("id")
                    val map = incomingMaps.remove(id) ?: return

                    val output = ByteArrayOutputStream()

                    for (index in 0 until map.totalChunks) {
                        val chunk = map.chunks[index]

                        if (chunk == null) {
                            Log.w(TAG, "Map transfer incomplete. Missing chunk $index")
                            listener.onError("Map transfer incomplete. Missing data.")
                            return
                        }

                        if (output.size() + chunk.size > MAX_MAP_BYTES) {
                            listener.onError("Map transfer exceeds the size limit")
                            return
                        }

                        output.write(chunk)
                    }

                    Log.d(TAG, "Map received successfully: $id, bytes: ${output.size()}")

                    listener.onMapReceived(
                        map.info,
                        map.objects,
                        output.toByteArray()
                    )
                }

                "drawingSnapshotRequest" -> {
                    if (isHost) {
                        listener.onDrawingMessage(senderUid, json)
                    }
                }

                "drawingSnapshot",
                "drawingStroke",
                "drawingUndo",
                "drawingRedo",
                "drawingLegend" -> {
                    if (isHost && participantDrawingPermissions[senderUid] != true) {
                        Log.w(TAG, "Drawing rejected from unauthorized participant: $senderUid")
                        return
                    }

                    listener.onDrawingMessage(senderUid, json)

                    if (isHost) {
                        broadcastExcept(message, senderUid)
                    }
                }

                "drawingClearUser" -> {
                    if (isHost) {
                        Log.w(TAG, "Rejected drawing clear message from participant: $senderUid")
                        return
                    }

                    val targetUid = json.optString("uid")

                    if (targetUid.isBlank()) {
                        Log.w(TAG, "Rejected drawing clear message with invalid UID")
                        return
                    }

                    listener.onDrawingMessage(senderUid, json)
                }

            }
        } catch (e: Exception) {
            Log.e(TAG, "Invalid DataChannel message", e)
        }
    }

    fun clearParticipantDrawings(
        uid: String,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        if (!isHost || isClosed) {
            onComplete?.invoke(false, "Only the host can clear participant drawings")
            return
        }

        if (uid.isBlank()) {
            onComplete?.invoke(false, "Invalid participant")
            return
        }

        val message = JSONObject()
            .put("type", "drawingClearUser")
            .put("uid", uid)

        listener.onDrawingMessage("host", message)
        broadcast(message.toString())

        Log.d(TAG, "Drawings cleared for participant: $uid")
        onComplete?.invoke(true, null)
    }

    fun sendDrawingMessage(
        message: JSONObject,
        targetUid: String? = null
    ) {
        if (isClosed) return

        val encoded = message.toString()

        if (isHost) {
            if (targetUid != null) {
                sendToPeer(targetUid, encoded)
            } else {
                broadcast(encoded)
            }
        } else {
            sendToPeer("host", encoded)
        }
    }

    private fun sendToPeer(
        uid: String,
        message: String
    ) {
        enqueueMessage(uid, message)
    }

    fun sendMap(
        info: JSONObject,
        objects: JSONObject,
        image: ByteArray?,
        targetUid: String? = null
    ) {
        if (!isHost || isClosed) return

        val imageBytes = image ?: ByteArray(0)

        if (imageBytes.size > MAX_MAP_BYTES) {
            listener.onError("Map image is too large to send")
            return
        }

        val infoString = info.toString()
        val objectsString = objects.toString()

        if (
            infoString.toByteArray(Charsets.UTF_8).size > MAX_METADATA_BYTES ||
            objectsString.toByteArray(Charsets.UTF_8).size > MAX_METADATA_BYTES
        ) {
            listener.onError("Map metadata is too large to send")
            return
        }

        val id = UUID.randomUUID().toString()

        val encoded = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val chunks = encoded.chunked(CHUNK_SIZE)

        if (chunks.size > MAX_MAP_CHUNKS) {
            listener.onError("Map contains too many data chunks")
            return
        }

        val start = JSONObject()
            .put("type", "mapStart")
            .put("id", id)
            .put("info", infoString)
            .put("objects", objectsString)
            .put("total", chunks.size)

        val destination = targetUid

        if (destination != null) {
            if (!channels.containsKey(destination)) {
                Log.w(TAG, "[$destination] Cannot send map. DataChannel missing.")
                return
            }

            enqueueMessage(destination, start.toString())
        } else {
            broadcast(start.toString())
        }

        chunks.forEachIndexed { index, chunk ->
            val message = JSONObject()
                .put("type", "mapChunk")
                .put("id", id)
                .put("index", index)
                .put("data", chunk)
                .toString()

            if (destination != null) {
                enqueueMessage(destination, message)
            } else {
                broadcast(message)
            }
        }

        val end = JSONObject()
            .put("type", "mapEnd")
            .put("id", id)
            .toString()

        if (destination != null) {
            enqueueMessage(destination, end)
        } else {
            broadcast(end)
        }

        Log.d(
            TAG,
            "Map queued. ID: $id, target: ${destination ?: "all"}, chunks: ${chunks.size}, bytes: ${imageBytes.size}"
        )
    }

    fun sendObjects(
        objects: JSONObject,
        targetUid: String? = null
    ) {
        if (!isHost || isClosed) return

        val message = JSONObject()
            .put("type", "objects")
            .put("data", objects.toString())
            .toString()

        if (targetUid != null) {
            enqueueMessage(targetUid, message)
        } else {
            broadcast(message)
        }
    }

    private fun broadcast(message: String) {
        if (isClosed) return

        channels.keys.toList().forEach { uid ->
            enqueueMessage(uid, message)
        }
    }

    private fun broadcastExcept(
        message: String,
        excludedUid: String
    ) {
        if (isClosed) return

        channels.keys.toList().forEach { uid ->
            if (uid != excludedUid) {
                enqueueMessage(uid, message)
            }
        }
    }

    private fun removePeerListeners(uid: String) {
        peerListeners.remove(uid)?.let { registrations ->
            synchronized(registrations) {
                registrations.forEach { registration ->
                    registration.ref.removeEventListener(registration.listener)
                }

                registrations.clear()
            }
        }

        peerChildListeners.remove(uid)?.let { registrations ->
            synchronized(registrations) {
                registrations.forEach { registration ->
                    registration.ref.removeEventListener(registration.listener)
                }

                registrations.clear()
            }
        }

        remoteDescriptionPending.remove(uid)
        pendingIceCandidates.remove(uid)
    }

    private fun closePeer(uid: String) {
        Log.d(TAG, "Closing peer: $uid")

        removePeerListeners(uid)

        outgoingQueues.remove(uid)?.clear()
        outgoingQueueSizes.remove(uid)

        channels.remove(uid)?.let { channel ->
            try {
                channel.unregisterObserver()
                channel.close()
                channel.dispose()
            } catch (e: Exception) {
                Log.e(TAG, "[$uid] Error closing DataChannel", e)
            }
        }

        peers.remove(uid)?.let { peer ->
            try {
                peer.close()
                peer.dispose()
            } catch (e: Exception) {
                Log.e(TAG, "[$uid] Error closing PeerConnection", e)
            }
        }
    }

    private fun cleanupLocalSession() {
        val ref = roomRef

        membersListener?.let {
            ref?.child("members")?.removeEventListener(it)
        }

        participantsListener?.let {
            ref?.child("members")?.removeEventListener(it)
        }

        activeSessionListener?.let {
            ref?.child("active")?.removeEventListener(it)
        }

        drawingPermissionListener?.let {
            localUid?.let { uid ->
                ref?.child("members")?.child(uid)?.child("canDraw")
                    ?.removeEventListener(it)
            }
        }

        moderationListener?.let {
            localUid?.let { uid ->
                ref?.child("moderation")?.child(uid)
                    ?.removeEventListener(it)
            }
        }

        membersListener = null
        participantsListener = null
        activeSessionListener = null
        drawingPermissionListener = null
        moderationListener = null

        (peerListeners.keys + peerChildListeners.keys)
            .toSet()
            .forEach { removePeerListeners(it) }

        peers.keys.toList().forEach { closePeer(it) }

        channels.keys.toList().forEach { uid ->
            channels.remove(uid)?.let { channel ->
                try {
                    channel.unregisterObserver()
                    channel.close()
                    channel.dispose()
                } catch (e: Exception) {
                    Log.e(TAG, "Error disposing channel $uid", e)
                }
            }
        }

        channels.clear()
        peers.clear()
        outgoingQueues.clear()
        outgoingQueueSizes.clear()

        pendingIceCandidates.clear()
        remoteDescriptionPending.clear()

        incomingMaps.clear()
        participantDrawingPermissions.clear()

        peerListeners.clear()
        peerChildListeners.clear()

        sessionCode = null
        roomRef = null
        isHost = false

        listener.onParticipantsChanged(emptyList())
        listener.onStatus("Disconnected")
    }

    fun leaveSession(
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        if (isClosed) {
            onComplete?.invoke(true, null)
            return
        }

        isClosed = true

        Log.d(TAG, "Leaving multiplayer session")

        val uid = localUid
        val ref = roomRef
        val wasHost = isHost

        cleanupLocalSession()

        if (ref == null) {
            onComplete?.invoke(true, null)
            return
        }

        if (wasHost) {
            ref.child("active").setValue(false)
                .addOnSuccessListener {
                    ref.child("members").removeValue()
                        .addOnSuccessListener {
                            ref.child("signals").removeValue()
                                .addOnSuccessListener {
                                    Log.d(TAG, "Host closed the session")
                                    onComplete?.invoke(true, null)
                                }
                                .addOnFailureListener { error ->
                                    Log.e(TAG, "Could not remove session signals", error)
                                    onComplete?.invoke(
                                        false,
                                        error.message ?: "Could not remove session signals"
                                    )
                                }
                        }
                        .addOnFailureListener { error ->
                            Log.e(TAG, "Could not remove session members", error)
                            onComplete?.invoke(
                                false,
                                error.message ?: "Could not remove session members"
                            )
                        }
                }
                .addOnFailureListener { error ->
                    Log.e(TAG, "Could not close session", error)
                    onComplete?.invoke(
                        false,
                        error.message ?: "Could not close session"
                    )
                }
        } else if (uid != null) {
            ref.child("members").child(uid).removeValue()
                .addOnSuccessListener {
                    ref.child("signals").child(uid).removeValue()
                        .addOnSuccessListener {
                            Log.d(TAG, "Participant left the session")
                            onComplete?.invoke(true, null)
                        }
                        .addOnFailureListener { error ->
                            Log.e(TAG, "Could not remove participant signals", error)
                            onComplete?.invoke(
                                false,
                                error.message ?: "Could not remove participant signals"
                            )
                        }
                }
                .addOnFailureListener { error ->
                    Log.e(TAG, "Could not remove participant from session", error)
                    onComplete?.invoke(
                        false,
                        error.message ?: "Could not leave session"
                    )
                }
        } else {
            onComplete?.invoke(false, "Firebase authentication is missing")
        }
    }

    fun shutdown() {
        leaveSession()

        factory?.dispose()
        factory = null

        Log.d(TAG, "Multiplayer manager shutdown")
    }

    open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) {
        }

        override fun onSetSuccess() {
        }

        override fun onCreateFailure(error: String) {
        }

        override fun onSetFailure(error: String) {
        }
    }
}