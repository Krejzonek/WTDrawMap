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
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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
    private val peerCloseExecutor = Executors.newSingleThreadExecutor()
    private val peerCloseLock = Any()
    private val closingPeers = ConcurrentHashMap.newKeySet<String>()
    private var connectionListener: ValueEventListener? = null
    @Volatile
    private var hasSeenFirebaseConnection = false

    interface Listener {
        fun onStatus(message: String)
        fun onSessionCreated(code: String)
        fun onParticipantJoined(name: String)
        fun onParticipantLeft(uid: String)
        fun onParticipantsChanged(participants: List<SessionParticipant>) {}
        fun onSessionClosed() {}
        fun onMapReceived(info: JSONObject, objects: JSONObject, image: ByteArray?)
        fun onObjectsReceived(objects: JSONObject)
        fun onError(message: String)
        fun onDrawingMessage(senderUid: String, message: JSONObject) {}
        fun onDrawingChannelReady(uid: String) {}
        fun onDrawingPermissionChanged(allowed: Boolean) {}
        fun onRemovedFromSession(reason: String) {}
        fun onConnectionLost() {}
    }

    companion object {
        private const val TAG = "WTMultiplayer"
        private const val DATABASE_URL = "https://wt-tactical-map-5f0fb-default-rtdb.europe-west1.firebasedatabase.app/"

        private const val MAX_PARTICIPANTS = 8
        private const val CHUNK_SIZE = 12000
        private const val MAX_BUFFERED_AMOUNT = 256L * 1024L
        private const val MAX_MAP_BYTES = 8 * 1024 * 1024
        private const val MAX_MAP_CHUNKS = 700
        private const val MAX_METADATA_BYTES = 512 * 1024
        private const val MAX_MESSAGE_BYTES = 2 * 1024 * 1024
        private const val MAX_QUEUED_BYTES = 12 * 1024 * 1024
        private const val MAX_PENDING_ICE_CANDIDATES = 256

        private val STUN_SERVERS = listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "stun:stun.cloudflare.com:3478"
        )
    }

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance(DATABASE_URL).reference

    private var factory: PeerConnectionFactory? = null
    private var roomRef: DatabaseReference? = null
    private var membersListener: ChildEventListener? = null
    private var participantsListener: ValueEventListener? = null
    private var activeSessionListener: ValueEventListener? = null
    private var drawingPermissionListener: ValueEventListener? = null
    private var moderationListener: ValueEventListener? = null

    private val participantDrawingPermissions = ConcurrentHashMap<String, Boolean>()
    private val peers = ConcurrentHashMap<String, PeerConnection>()
    private val channels = ConcurrentHashMap<String, DataChannel>()
    private val outgoingQueues = ConcurrentHashMap<String, ConcurrentLinkedQueue<ByteArray>>()
    private val outgoingQueueSizes = ConcurrentHashMap<String, AtomicLong>()
    private val negotiationIds = ConcurrentHashMap<String, String>()

    private data class ValueListenerRegistration(
        val ref: DatabaseReference,
        val listener: ValueEventListener
    )

    private data class ChildListenerRegistration(
        val ref: DatabaseReference,
        val listener: ChildEventListener
    )

    private val peerListeners = ConcurrentHashMap<String, MutableList<ValueListenerRegistration>>()
    private val peerChildListeners = ConcurrentHashMap<String, MutableList<ChildListenerRegistration>>()
    private val pendingIceCandidates = ConcurrentHashMap<String, MutableList<IceCandidate>>()
    private val remoteDescriptionPending = ConcurrentHashMap.newKeySet<String>()

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

    private val incomingMaps = ConcurrentHashMap<String, IncomingMap>()

    fun getLocalUid() = localUid ?: "local"
    fun isHost() = isHost
    fun getSessionCode() = sessionCode

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

        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
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
        val cleanUsername = username.trim().take(20).ifEmpty { "User" }
        createUniqueCode(0, cleanUsername)
    }

    private fun cancelParticipantDisconnectCleanup(
        ref: DatabaseReference,
        uid: String,
        onComplete: () -> Unit
    ) {
        ref.child("members").child(uid).onDisconnect().cancel()
            .addOnCompleteListener {
                ref.child("signals").child(uid).onDisconnect().cancel()
                    .addOnCompleteListener {
                        onComplete()
                    }
            }
    }

    private fun registerHostDisconnectCleanup(ref: DatabaseReference) {
        ref.onDisconnect().removeValue()
            .addOnSuccessListener {
                Log.d(TAG, "Automatic host disconnect cleanup registered")
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "Could not register host disconnect cleanup", error)
                listener.onError("Could not register automatic session cleanup: ${error.message}")
            }
    }

    private fun registerParticipantDisconnectCleanup(
        ref: DatabaseReference,
        uid: String
    ) {
        val memberDisconnect = ref.child("members").child(uid).onDisconnect()

        memberDisconnect.removeValue()
            .addOnSuccessListener {
                ref.child("signals").child(uid).onDisconnect().removeValue()
                    .addOnFailureListener { error ->
                        Log.e(TAG, "[$uid] Could not register signals cleanup", error)
                    }
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "[$uid] Could not register members cleanup", error)
                listener.onError("Could not register automatic participant cleanup")
            }
    }

    private fun listenForFirebaseConnection() {
        val ref = database.child(".info").child("connected")

        connectionListener?.let { ref.removeEventListener(it) }
        hasSeenFirebaseConnection = false

        val valueListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false

                if (connected) {
                    hasSeenFirebaseConnection = true
                    return
                }

                if (!hasSeenFirebaseConnection || isClosed || sessionCode == null) return

                Log.w(TAG, "Firebase connection lost")
                isClosed = true
                cleanupLocalSession()
                listener.onConnectionLost()
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase connection listener cancelled", error.toException())
            }
        }

        connectionListener = valueListener
        ref.addValueEventListener(valueListener)
    }

    private fun cancelDisconnectCleanup(
        ref: DatabaseReference,
        uid: String?,
        wasHost: Boolean,
        onComplete: () -> Unit
    ) {
        if (wasHost) {
            ref.onDisconnect().cancel()
                .addOnCompleteListener {
                    onComplete()
                }
        } else if (uid != null) {
            ref.child("signals").child(uid).onDisconnect().cancel()
                .addOnCompleteListener {
                    ref.child("members").child(uid).onDisconnect().cancel()
                        .addOnCompleteListener {
                            onComplete()
                        }
                }
        } else {
            onComplete()
        }
    }

    private fun createUniqueCode(attempt: Int, username: String) {
        if (attempt >= 10) {
            listener.onError("Could not create a unique session code")
            return
        }

        val uid = localUid ?: return
        val code = "WT-" + UUID.randomUUID().toString().replace("-", "").take(6).uppercase()
        val ref = database.child("rooms").child(code)

        ref.runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                if (currentData.child("active").getValue(Boolean::class.java) == true) {
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
                    ref.removeValue()
                    return
                }

                sessionCode = code
                roomRef = ref
                isHost = true

                listenForFirebaseConnection()
                registerHostDisconnectCleanup(ref)

                listenForMembers()
                listenForParticipants()

                listener.onSessionCreated(code)
                listener.onStatus("Waiting for participants...")
            }
        })
    }

    fun joinSession(codeInput: String, username: String) {
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
        val memberRef = ref.child("members").child(uid)

        listener.onStatus("Checking session...")

        var verifiedHostUid: String? = null

        fun joinAfterChecks() {
            if (isClosed) return

            memberRef.setValue(
                mapOf(
                    "name" to username.trim().take(20).ifEmpty { "User" },
                    "host" to false,
                    "canDraw" to false
                )
            )
                .addOnSuccessListener {
                    if (isClosed) {
                        memberRef.removeValue()
                        return@addOnSuccessListener
                    }

                    registerParticipantDisconnectCleanup(ref, uid)

                    ref.child("members").get()
                        .addOnSuccessListener { membersSnapshot ->
                            if (isClosed) {
                                memberRef.removeValue()
                                return@addOnSuccessListener
                            }

                            if (!membersSnapshot.hasChild(uid)) {
                                memberRef.removeValue()
                                listener.onError("Could not verify session membership")
                                return@addOnSuccessListener
                            }

                            if (membersSnapshot.childrenCount > MAX_PARTICIPANTS) {
                                cancelParticipantDisconnectCleanup(ref, uid) {
                                    memberRef.removeValue()
                                        .addOnSuccessListener {
                                            listener.onError("Session is full")
                                        }
                                        .addOnFailureListener { error ->
                                            Log.e(TAG, "Could not remove rejected participant", error)
                                            listener.onError("Session is full. Could not remove your membership.")
                                        }
                                }
                                return@addOnSuccessListener
                            }

                            val hostUid = verifiedHostUid

                            if (hostUid.isNullOrBlank() || !membersSnapshot.hasChild(hostUid)) {
                                cancelParticipantDisconnectCleanup(ref, uid) {
                                    memberRef.removeValue()
                                }
                                listener.onError("Invalid session: host is not a member")
                                return@addOnSuccessListener
                            }

                            ref.child("active").get()
                                .addOnSuccessListener { activeSnapshot ->
                                    if (isClosed) {
                                        cancelParticipantDisconnectCleanup(ref, uid) {
                                            memberRef.removeValue()
                                        }
                                        return@addOnSuccessListener
                                    }

                                    if (activeSnapshot.getValue(Boolean::class.java) != true) {
                                        cancelParticipantDisconnectCleanup(ref, uid) {
                                            memberRef.removeValue()
                                        }
                                        listener.onError("Session is closed")
                                        return@addOnSuccessListener
                                    }

                                    sessionCode = code
                                    roomRef = ref
                                    isHost = false

                                    listenForFirebaseConnection()

                                    listener.onStatus("Joining session...")
                                    listenForParticipants()
                                    listenForSessionState()
                                    listenForOwnPermissions()
                                    listenForModeration()
                                    listenForHostOffer()
                                }
                                .addOnFailureListener { error ->
                                    cancelParticipantDisconnectCleanup(ref, uid) {
                                        memberRef.removeValue()
                                    }
                                    Log.e(TAG, "Session status lookup failed", error)
                                    listener.onError("Could not verify session status: ${error.message}")
                                }
                        }
                        .addOnFailureListener { error ->
                            cancelParticipantDisconnectCleanup(ref, uid) {
                                memberRef.removeValue()
                            }
                            Log.e(TAG, "Members lookup failed", error)
                            listener.onError("Could not check session members: ${error.message}")
                        }
                }
                .addOnFailureListener { error ->
                    Log.e(TAG, "Session join failed", error)
                    listener.onError("Could not join session: ${error.message}")
                }
        }

        fun checkBan() {
            ref.child("bans").child(uid).get()
                .addOnSuccessListener { banSnapshot ->
                    if (isClosed) return@addOnSuccessListener

                    if (banSnapshot.exists()) {
                        listener.onError("You are banned from this session")
                        return@addOnSuccessListener
                    }

                    ref.child("moderation").child(uid).child("timestamp").get()
                        .addOnSuccessListener { moderationSnapshot ->
                            if (isClosed) return@addOnSuccessListener

                            lastModerationTimestamp =
                                moderationSnapshot.getValue(Long::class.java) ?: 0L

                            joinAfterChecks()
                        }
                        .addOnFailureListener { error ->
                            Log.e(TAG, "Moderation lookup failed", error)
                            listener.onError("Could not check moderation status: ${error.message}")
                        }
                }
                .addOnFailureListener { error ->
                    Log.e(TAG, "Ban lookup failed", error)
                    listener.onError("Could not check session access: ${error.message}")
                }
        }

        ref.child("host").get()
            .addOnSuccessListener { hostSnapshot ->
                if (isClosed) return@addOnSuccessListener

                val hostUid = hostSnapshot.getValue(String::class.java)

                if (hostUid.isNullOrBlank()) {
                    listener.onError("Session does not exist")
                    return@addOnSuccessListener
                }

                verifiedHostUid = hostUid

                ref.child("active").get()
                    .addOnSuccessListener { activeSnapshot ->
                        if (isClosed) return@addOnSuccessListener

                        if (activeSnapshot.getValue(Boolean::class.java) != true) {
                            listener.onError("Session is closed")
                            return@addOnSuccessListener
                        }

                        checkBan()
                    }
                    .addOnFailureListener { error ->
                        Log.e(TAG, "Session status lookup failed", error)
                        listener.onError("Could not check session status: ${error.message}")
                    }
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "Session host lookup failed", error)
                listener.onError("Could not check session: ${error.message}")
            }
    }

    private fun listenForParticipants() {
        val ref = roomRef?.child("members") ?: return

        participantsListener?.let { ref.removeEventListener(it) }

        val valueListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (isClosed) return

                val participants = snapshot.children.mapNotNull { child ->
                    val uid = child.key ?: return@mapNotNull null
                    val name = child.child("name").getValue(String::class.java) ?: "Player"
                    val host = child.child("host").getValue(Boolean::class.java) ?: false
                    val canDraw = child.child("canDraw").getValue(Boolean::class.java) ?: false

                    SessionParticipant(uid, name, host, host || canDraw)
                }.sortedWith(
                    compareByDescending<SessionParticipant> { it.isHost }
                        .thenBy { it.name.lowercase() }
                )

                if (isHost) {
                    participantDrawingPermissions.clear()
                    participants.forEach {
                        participantDrawingPermissions[it.uid] = it.isHost || it.canDraw
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

        drawingPermissionListener?.let { ref.removeEventListener(it) }

        val valueListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (isClosed || isHost) return
                listener.onDrawingPermissionChanged(
                    snapshot.getValue(Boolean::class.java) ?: false
                )
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

        moderationListener?.let { ref.removeEventListener(it) }

        val valueListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (isClosed || isHost || !snapshot.exists()) return

                val action = snapshot.child("action").getValue(String::class.java) ?: return
                val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: return

                if (timestamp <= lastModerationTimestamp) return

                lastModerationTimestamp = timestamp

                val reason = when (action) {
                    "ban" -> "You have been banned from this session"
                    "kick" -> "You have been kicked from this session"
                    else -> return
                }

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
                if (!snapshot.exists() || snapshot.child("host").getValue(Boolean::class.java) == true) {
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
                if (!snapshot.exists() || snapshot.child("host").getValue(Boolean::class.java) == true) {
                    onComplete?.invoke(false, "Participant is not available")
                    return@addOnSuccessListener
                }

                val timestamp = System.currentTimeMillis()
                val action = if (ban) "ban" else "kick"
                val updates = mutableMapOf<String, Any?>(
                    "moderation/$uid" to mapOf("action" to action, "timestamp" to timestamp),
                    "members/$uid" to null,
                    "signals/$uid" to null
                )

                if (ban) updates["bans/$uid"] = mapOf("timestamp" to timestamp)

                ref.updateChildren(updates)
                    .addOnSuccessListener {
                        participantDrawingPermissions.remove(uid)
                        closePeer(uid)
                        onComplete?.invoke(true, null)
                    }
                    .addOnFailureListener { error ->
                        Log.e(TAG, "Participant moderation failed", error)
                        onComplete?.invoke(false, error.message ?: "Could not moderate participant")
                    }
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "Participant lookup failed", error)
                onComplete?.invoke(false, error.message ?: "Firebase error")
            }
    }

    private fun listenForSessionState() {
        val ref = roomRef?.child("active") ?: return

        activeSessionListener?.let { ref.removeEventListener(it) }

        val valueListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (isClosed || isHost) return
                if (!snapshot.exists()) return

                val active = snapshot.getValue(Boolean::class.java) ?: return
                if (active) return

                isClosed = true
                cleanupLocalSession()
                listener.onStatus("Host closed the session")
                listener.onSessionClosed()
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
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val uid = snapshot.key ?: return
                if (uid == localUid || isClosed) return

                val name = snapshot.child("name").getValue(String::class.java) ?: "Player"
                listener.onParticipantJoined(name)
                createOfferForParticipant(uid)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}

            override fun onChildRemoved(snapshot: DataSnapshot) {
                val uid = snapshot.key ?: return
                if (uid == localUid) return

                participantDrawingPermissions.remove(uid)
                closePeer(uid)
                listener.onParticipantLeft(uid)
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Members listener cancelled", error.toException())
                listener.onError(error.message)
            }
        }

        membersListener = childListener
        ref.child("members").addChildEventListener(childListener)
    }

    private fun closeLateDataChannel(uid: String, channel: DataChannel) {
        try {
            peerCloseExecutor.execute {
                try {
                    synchronized(channel) {
                        channel.unregisterObserver()
                        channel.close()
                        channel.dispose()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[$uid] Error closing late DataChannel", e)
                }
            }
        } catch (e: RejectedExecutionException) {
            Log.e(TAG, "[$uid] Cannot schedule late DataChannel cleanup", e)
        }
    }

    private fun createPeer(
        uid: String,
        onRemoteDataChannel: (DataChannel) -> Unit
    ): PeerConnection? {
        if (isClosed || closingPeers.contains(uid)) return null

        val rtcFactory = factory ?: return null

        val iceServers = STUN_SERVERS.mapNotNull { server ->
            try {
                PeerConnection.IceServer.builder(server).createIceServer()
            } catch (e: Exception) {
                Log.e(TAG, "[$uid] Invalid STUN server: $server", e)
                null
            }
        }

        if (iceServers.isEmpty()) {
            Log.e(TAG, "[$uid] No valid STUN servers available")
            listener.onError("No STUN servers available")
            return null
        }

        val configuration = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }

        return try {
            val peerReference = AtomicReference<PeerConnection?>(null)

            val peer = rtcFactory.createPeerConnection(
                configuration,
                object : PeerConnection.Observer {
                    override fun onSignalingChange(state: PeerConnection.SignalingState) {}

                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                        if (isClosed || closingPeers.contains(uid)) return

                        when (state) {
                            PeerConnection.IceConnectionState.CONNECTED,
                            PeerConnection.IceConnectionState.COMPLETED -> {
                                listener.onStatus("Connected to multiplayer session")
                            }

                            PeerConnection.IceConnectionState.DISCONNECTED -> {
                                listener.onStatus("Connection interrupted")
                            }

                            PeerConnection.IceConnectionState.FAILED -> {
                                listener.onError("P2P connection failed. Direct connection could not be established on this network.")
                            }

                            else -> {}
                        }
                    }

                    override fun onIceConnectionReceivingChange(receiving: Boolean) {}

                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}

                    override fun onIceCandidate(candidate: IceCandidate) {
                        if (isClosed || closingPeers.contains(uid)) return
                        sendIceCandidate(uid, candidate)
                    }

                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}

                    override fun onAddStream(stream: org.webrtc.MediaStream) {}
                    override fun onRemoveStream(stream: org.webrtc.MediaStream) {}

                    override fun onDataChannel(channel: DataChannel) {
                        val accepted = synchronized(peerCloseLock) {
                            if (isClosed || closingPeers.contains(uid)) {
                                false
                            } else {
                                channels[uid] = channel
                                true
                            }
                        }

                        if (!accepted) {
                            closeLateDataChannel(uid, channel)
                            return
                        }

                        registerDataChannel(uid, channel)
                        onRemoteDataChannel(channel)
                    }

                    override fun onRenegotiationNeeded() {}

                    override fun onAddTrack(
                        receiver: org.webrtc.RtpReceiver,
                        mediaStreams: Array<out org.webrtc.MediaStream>
                    ) {}
                }
            )

            if (peer == null) {
                Log.e(TAG, "[$uid] createPeerConnection returned null")
                return null
            }

            val accepted = synchronized(peerCloseLock) {
                if (isClosed || closingPeers.contains(uid)) {
                    false
                } else {
                    peers[uid] = peer
                    true
                }
            }

            if (!accepted) {
                try {
                    peerCloseExecutor.execute {
                        try {
                            peer.close()
                            peer.dispose()
                        } catch (e: Exception) {
                            Log.e(TAG, "[$uid] Error closing rejected PeerConnection", e)
                        }
                    }
                } catch (e: RejectedExecutionException) {
                    Log.e(TAG, "[$uid] Could not schedule rejected PeerConnection cleanup", e)
                }
                return null
            }

            peerReference.set(peer)
            peer
        } catch (e: Exception) {
            Log.e(TAG, "[$uid] PeerConnection creation failed", e)
            null
        }
    }

    private fun createOfferForParticipant(uid: String) {
        if (isClosed || !isHost || peers.containsKey(uid) || closingPeers.contains(uid)) return

        val memberRef = roomRef?.child("members")?.child(uid) ?: return

        memberRef.get()
            .addOnSuccessListener { member ->
                if (isClosed || !isHost || !member.exists()) return@addOnSuccessListener
                if (member.child("host").getValue(Boolean::class.java) == true) return@addOnSuccessListener
                createOfferForConfirmedParticipant(uid)
            }
            .addOnFailureListener { error ->
                if (isClosed) return@addOnFailureListener
                Log.e(TAG, "[$uid] Could not verify participant", error)
            }
    }

    private fun createOfferForConfirmedParticipant(uid: String) {
        if (isClosed || !isHost || peers.containsKey(uid) || closingPeers.contains(uid)) return

        val signalRef = roomRef?.child("signals")?.child(uid) ?: run {
            if (!isClosed) listener.onError("Could not access signaling database")
            return
        }

        listener.onStatus("Preparing WebRTC connection...")

        signalRef.removeValue()
            .addOnSuccessListener {
                if (isClosed || !isHost || peers.containsKey(uid) || closingPeers.contains(uid)) return@addOnSuccessListener

                val negotiationId = UUID.randomUUID().toString()
                negotiationIds[uid] = negotiationId
                startOfferCreation(uid, signalRef, negotiationId)
            }
            .addOnFailureListener { error ->
                if (isClosed) return@addOnFailureListener
                Log.e(TAG, "[$uid] Could not clear old signaling data", error)
                listener.onError("Could not prepare WebRTC signaling: ${error.message}")
            }
    }

    private fun startOfferCreation(
        uid: String,
        signalRef: DatabaseReference,
        negotiationId: String
    ) {
        if (isClosed || closingPeers.contains(uid) || negotiationIds[uid] != negotiationId) return

        val peer = createPeer(uid) {} ?: run {
            if (!isClosed && !closingPeers.contains(uid)) {
                listener.onError("Could not create WebRTC connection")
            }
            return
        }

        val channel = peer.createDataChannel("wt-map", DataChannel.Init().apply {
            ordered = true
        })

        if (channel == null) {
            if (!isClosed) listener.onError("Could not create DataChannel")
            closePeer(uid)
            return
        }

        val accepted = synchronized(peerCloseLock) {
            if (isClosed || closingPeers.contains(uid)) {
                false
            } else {
                channels[uid] = channel
                true
            }
        }

        if (!accepted) {
            closeLateDataChannel(uid, channel)
            return
        }

        registerDataChannel(uid, channel)

        peer.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(description: SessionDescription) {
                if (isClosed || closingPeers.contains(uid) || negotiationIds[uid] != negotiationId) return

                peer.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        if (isClosed || closingPeers.contains(uid) || negotiationIds[uid] != negotiationId) return

                        listenForAnswer(uid, peer, negotiationId)
                        listenForRemoteCandidates(uid, "participantCandidates", peer, negotiationId)

                        signalRef.child("offer").setValue(
                            mapOf(
                                "type" to description.type.canonicalForm(),
                                "sdp" to description.description,
                                "negotiationId" to negotiationId
                            )
                        )
                            .addOnSuccessListener {
                                if (!isClosed && !closingPeers.contains(uid)) {
                                    listener.onStatus("Offer sent. Waiting for answer...")
                                }
                            }
                            .addOnFailureListener { error ->
                                if (isClosed || closingPeers.contains(uid)) return@addOnFailureListener
                                Log.e(TAG, "[$uid] Offer send failed", error)
                                listener.onError("Could not send offer: ${error.message}")
                            }
                    }

                    override fun onSetFailure(error: String) {
                        if (isClosed || closingPeers.contains(uid)) return
                        Log.e(TAG, "[$uid] Setting local offer failed: $error")
                        listener.onError("Could not set local offer: $error")
                    }
                }, description)
            }

            override fun onCreateFailure(error: String) {
                if (isClosed || closingPeers.contains(uid)) return
                Log.e(TAG, "[$uid] Offer creation failed: $error")
                listener.onError("Offer creation failed: $error")
            }
        }, MediaConstraints())
    }

    private fun listenForAnswer(
        uid: String,
        peer: PeerConnection,
        negotiationId: String
    ) {
        val ref = roomRef?.child("signals")?.child(uid)?.child("answer") ?: return

        val valueListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (isClosed || closingPeers.contains(uid) || negotiationIds[uid] != negotiationId) return

                val receivedNegotiationId = snapshot.child("negotiationId")
                    .getValue(String::class.java) ?: return

                if (receivedNegotiationId != negotiationId) return

                val sdp = snapshot.child("sdp").getValue(String::class.java) ?: return
                val type = snapshot.child("type").getValue(String::class.java) ?: "answer"

                if (peer.remoteDescription != null || !remoteDescriptionPending.add(uid)) return

                peer.setRemoteDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        remoteDescriptionPending.remove(uid)
                        if (isClosed || closingPeers.contains(uid)) return
                        flushPendingIceCandidates(uid, peer)
                    }

                    override fun onSetFailure(error: String) {
                        remoteDescriptionPending.remove(uid)
                        Log.e(TAG, "[$uid] Setting remote answer failed: $error")
                        listener.onError("Remote answer failed: $error")
                    }
                }, SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp))
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "[$uid] Answer listener cancelled", error.toException())
                listener.onError("Answer listener error: ${error.message}")
            }
        }

        ref.addValueEventListener(valueListener)
        peerListeners.getOrPut(uid) { Collections.synchronizedList(mutableListOf()) }
            .add(ValueListenerRegistration(ref, valueListener))
    }

    private fun listenForHostOffer() {
        val uid = localUid ?: return
        val ref = roomRef?.child("signals")?.child(uid) ?: return
        val offerRef = ref.child("offer")

        val offerListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (isClosed || isHost || closingPeers.contains("host")) return

                val sdp = snapshot.child("sdp").getValue(String::class.java) ?: return
                val type = snapshot.child("type").getValue(String::class.java) ?: "offer"
                val negotiationId = snapshot.child("negotiationId").getValue(String::class.java)

                if (negotiationId.isNullOrBlank()) return
                if (peers.containsKey("host") || !remoteDescriptionPending.add("host")) return

                negotiationIds["host"] = negotiationId

                listener.onStatus("Offer received. Creating connection...")

                val peer = createPeer("host") {} ?: run {
                    remoteDescriptionPending.remove("host")
                    if (!isClosed && !closingPeers.contains("host")) {
                        listener.onError("Could not create WebRTC connection")
                    }
                    return
                }

                listenForRemoteCandidates("host", "hostCandidates", peer, negotiationId)

                peer.setRemoteDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        remoteDescriptionPending.remove("host")
                        if (isClosed || closingPeers.contains("host")) return

                        flushPendingIceCandidates("host", peer)

                        peer.createAnswer(object : SdpObserverAdapter() {
                            override fun onCreateSuccess(description: SessionDescription) {
                                if (isClosed || closingPeers.contains("host") || negotiationIds["host"] != negotiationId) return

                                peer.setLocalDescription(object : SdpObserverAdapter() {
                                    override fun onSetSuccess() {
                                        if (isClosed || closingPeers.contains("host") || negotiationIds["host"] != negotiationId) return

                                        ref.child("answer").setValue(
                                            mapOf(
                                                "type" to description.type.canonicalForm(),
                                                "sdp" to description.description,
                                                "negotiationId" to negotiationId
                                            )
                                        )
                                            .addOnSuccessListener {
                                                if (!isClosed && !closingPeers.contains("host")) {
                                                    listener.onStatus("Answer sent. Connecting...")
                                                }
                                            }
                                            .addOnFailureListener { error ->
                                                Log.e(TAG, "[host] Answer send failed", error)
                                                if (!isClosed && !closingPeers.contains("host")) {
                                                    listener.onError("Could not send answer: ${error.message}")
                                                }
                                            }
                                    }

                                    override fun onSetFailure(error: String) {
                                        if (isClosed || closingPeers.contains("host")) return
                                        Log.e(TAG, "[host] Setting local answer failed: $error")
                                        listener.onError("Could not set local answer: $error")
                                    }
                                }, description)
                            }

                            override fun onCreateFailure(error: String) {
                                if (isClosed || closingPeers.contains("host")) return
                                Log.e(TAG, "[host] Answer creation failed: $error")
                                listener.onError("Answer creation failed: $error")
                            }
                        }, MediaConstraints())
                    }

                    override fun onSetFailure(error: String) {
                        remoteDescriptionPending.remove("host")
                        if (isClosed || closingPeers.contains("host")) return
                        Log.e(TAG, "[host] Setting remote offer failed: $error")
                        listener.onError("Could not set offer: $error")
                    }
                }, SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp))
            }

            override fun onCancelled(error: DatabaseError) {
                if (isClosed) return
                Log.e(TAG, "Offer listener cancelled", error.toException())
                listener.onError("Offer listener error: ${error.message}")
            }
        }

        offerRef.addValueEventListener(offerListener)
        peerListeners.getOrPut("host") { Collections.synchronizedList(mutableListOf()) }
            .add(ValueListenerRegistration(offerRef, offerListener))
    }

    private fun sendIceCandidate(uid: String, candidate: IceCandidate) {
        if (isClosed || closingPeers.contains(uid)) return

        val code = sessionCode ?: return
        val local = localUid ?: return
        val negotiationId = negotiationIds[uid] ?: return

        val path = if (isHost) {
            database.child("rooms").child(code).child("signals").child(uid).child("hostCandidates")
        } else {
            database.child("rooms").child(code).child("signals").child(local).child("participantCandidates")
        }

        path.push().setValue(
            mapOf(
                "sdpMid" to candidate.sdpMid,
                "sdpMLineIndex" to candidate.sdpMLineIndex,
                "sdp" to candidate.sdp,
                "negotiationId" to negotiationId
            )
        ).addOnFailureListener { error ->
            Log.e(TAG, "[$uid] ICE candidate send failed", error)
            if (!isClosed) listener.onError("Could not send ICE candidate: ${error.message}")
        }
    }

    private fun addIceCandidateSafely(
        uid: String,
        peer: PeerConnection,
        candidate: IceCandidate
    ) {
        if (isClosed || closingPeers.contains(uid)) return

        synchronized(pendingIceCandidates) {
            if (isClosed || closingPeers.contains(uid)) return

            if (peer.remoteDescription != null) {
                peer.addIceCandidate(candidate)
            } else {
                val candidates = pendingIceCandidates.getOrPut(uid) {
                    Collections.synchronizedList(mutableListOf())
                }

                synchronized(candidates) {
                    if (candidates.size < MAX_PENDING_ICE_CANDIDATES) {
                        candidates.add(candidate)
                    }
                }
            }
        }
    }

    private fun flushPendingIceCandidates(uid: String, peer: PeerConnection) {
        if (isClosed || closingPeers.contains(uid)) return

        synchronized(pendingIceCandidates) {
            if (isClosed || closingPeers.contains(uid)) return

            val candidates = pendingIceCandidates.remove(uid) ?: return

            synchronized(candidates) {
                candidates.forEach {
                    if (isClosed || closingPeers.contains(uid)) return@forEach
                    peer.addIceCandidate(it)
                }

                candidates.clear()
            }
        }
    }

    private fun listenForRemoteCandidates(
        uid: String,
        candidatePath: String,
        peer: PeerConnection,
        negotiationId: String
    ) {
        val code = sessionCode ?: return
        val pathUid = if (isHost) uid else localUid ?: return

        val ref = database.child("rooms")
            .child(code)
            .child("signals")
            .child(pathUid)
            .child(candidatePath)

        val childListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                if (isClosed || closingPeers.contains(uid) || negotiationIds[uid] != negotiationId) return

                val candidateNegotiationId = snapshot.child("negotiationId")
                    .getValue(String::class.java) ?: return

                if (candidateNegotiationId != negotiationId) return

                val sdp = snapshot.child("sdp").getValue(String::class.java) ?: return
                val mid = snapshot.child("sdpMid").getValue(String::class.java)
                val line = snapshot.child("sdpMLineIndex").getValue(Int::class.java) ?: 0

                addIceCandidateSafely(uid, peer, IceCandidate(mid, line, sdp))
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "[$uid] ICE candidate listener cancelled", error.toException())
                if (!isClosed) listener.onError("ICE listener error: ${error.message}")
            }
        }

        ref.addChildEventListener(childListener)
        peerChildListeners.getOrPut(uid) { Collections.synchronizedList(mutableListOf()) }
            .add(ChildListenerRegistration(ref, childListener))
    }

    private fun registerDataChannel(uid: String, channel: DataChannel) {
        if (isClosed || closingPeers.contains(uid)) return

        outgoingQueues.putIfAbsent(uid, ConcurrentLinkedQueue())
        outgoingQueueSizes.putIfAbsent(uid, AtomicLong(0L))

        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {
                if (!isClosed && !closingPeers.contains(uid)) drainOutgoingQueue(uid)
            }

            override fun onStateChange() {
                if (isClosed || closingPeers.contains(uid)) return

                val state = channel.state()

                if (state == DataChannel.State.OPEN) {
                    drainOutgoingQueue(uid)
                    listener.onStatus("Data channel connected")
                    listener.onDrawingChannelReady(uid)

                    if (!isHost) {
                        sendDrawingMessage(JSONObject().put("type", "drawingSnapshotRequest"))
                    }
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (isClosed || closingPeers.contains(uid) || buffer.binary) return

                val size = buffer.data.remaining()
                if (size <= 0 || size > MAX_MESSAGE_BYTES) return

                val bytes = ByteArray(size)
                buffer.data.get(bytes)
                handleIncomingMessage(uid, String(bytes, Charsets.UTF_8))
            }
        })

        if (channel.state() == DataChannel.State.OPEN && !isClosed && !closingPeers.contains(uid)) {
            listener.onDrawingChannelReady(uid)
        }
    }

    private fun enqueueMessage(uid: String, message: String) {
        if (isClosed || closingPeers.contains(uid)) return

        val channel = channels[uid] ?: return
        val bytes = message.toByteArray(Charsets.UTF_8)

        if (bytes.size > MAX_MESSAGE_BYTES) return

        val queue = outgoingQueues.getOrPut(uid) { ConcurrentLinkedQueue() }
        val sizeCounter = outgoingQueueSizes.getOrPut(uid) { AtomicLong(0L) }

        val newSize = sizeCounter.addAndGet(bytes.size.toLong())

        if (newSize > MAX_QUEUED_BYTES) {
            sizeCounter.addAndGet(-bytes.size.toLong())
            listener.onError("Data transfer queue is full")
            return
        }

        queue.add(bytes)
        drainOutgoingQueue(uid)
    }

    private fun drainOutgoingQueue(uid: String) {
        if (isClosed || closingPeers.contains(uid)) return

        val channel = channels[uid] ?: return
        val queue = outgoingQueues[uid] ?: return
        val sizeCounter = outgoingQueueSizes[uid] ?: return

        synchronized(channel) {
            if (isClosed || closingPeers.contains(uid)) return
            if (channel.state() != DataChannel.State.OPEN) return

            while (!isClosed && !closingPeers.contains(uid) &&
                channel.bufferedAmount() < MAX_BUFFERED_AMOUNT
            ) {
                val bytes = queue.peek() ?: break

                val sent = try {
                    channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
                } catch (e: Exception) {
                    Log.e(TAG, "[$uid] DataChannel send exception", e)
                    false
                }

                if (!sent) break

                queue.poll()
                sizeCounter.addAndGet(-bytes.size.toLong())
            }
        }
    }

    private fun handleIncomingMessage(senderUid: String, message: String) {
        if (isClosed || closingPeers.contains(senderUid)) return

        try {
            val json = JSONObject(message)
            val type = json.optString("type")

            if (!isHost && senderUid != "host") return
            if (isHost && senderUid == "host") return

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
                    ) return

                    if (incomingMaps.size >= 2 && !incomingMaps.containsKey(id)) {
                        incomingMaps.clear()
                    }

                    incomingMaps[id] = IncomingMap(
                        JSONObject(infoString),
                        JSONObject(objectsString),
                        ConcurrentHashMap<Int, ByteArray>(),
                        total
                    )
                }

                "mapChunk" -> {
                    if (isHost) return

                    val id = json.getString("id")
                    val map = incomingMaps[id] ?: return
                    val index = json.getInt("index")

                    if (index < 0 || index >= map.totalChunks) return

                    val encoded = json.getString("data")
                    if (encoded.length > CHUNK_SIZE) {
                        incomingMaps.remove(id)
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
                        val chunk = map.chunks[index] ?: run {
                            listener.onError("Map transfer incomplete. Missing data.")
                            return
                        }

                        if (output.size() + chunk.size > MAX_MAP_BYTES) {
                            listener.onError("Map transfer exceeds the size limit")
                            return
                        }

                        output.write(chunk)
                    }

                    listener.onMapReceived(map.info, map.objects, output.toByteArray())
                }

                "drawingSnapshotRequest" -> {
                    if (isHost) listener.onDrawingMessage(senderUid, json)
                }

                "drawingSnapshot" -> {
                    if (isHost) return
                    listener.onDrawingMessage(senderUid, json)
                }

                "drawingStroke", "drawingUndo", "drawingRedo", "drawingLegend" -> {
                    if (isHost) {
                        if (participantDrawingPermissions[senderUid] != true) return

                        val forwarded = JSONObject(json.toString())
                        forwarded.put("authorUid", senderUid)

                        listener.onDrawingMessage(senderUid, forwarded)
                        broadcastExcept(forwarded.toString(), senderUid)
                    } else {
                        listener.onDrawingMessage(senderUid, json)
                    }
                }

                "drawingClearUser" -> {
                    if (isHost) return

                    val targetUid = json.optString("uid")
                    if (targetUid.isBlank()) return

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

        onComplete?.invoke(true, null)
    }

    fun sendDrawingMessage(message: JSONObject, targetUid: String? = null) {
        if (isClosed) return

        val encoded = message.toString()

        if (isHost) {
            if (targetUid != null) sendToPeer(targetUid, encoded)
            else broadcast(encoded)
        } else {
            sendToPeer("host", encoded)
        }
    }

    private fun sendToPeer(uid: String, message: String) {
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
            if (!channels.containsKey(destination)) return
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

            if (destination != null) enqueueMessage(destination, message)
            else broadcast(message)
        }

        val end = JSONObject()
            .put("type", "mapEnd")
            .put("id", id)
            .toString()

        if (destination != null) enqueueMessage(destination, end)
        else broadcast(end)
    }

    fun sendObjects(objects: JSONObject, targetUid: String? = null) {
        if (!isHost || isClosed) return

        val message = JSONObject()
            .put("type", "objects")
            .put("data", objects.toString())
            .toString()

        if (targetUid != null) enqueueMessage(targetUid, message)
        else broadcast(message)
    }

    private fun broadcast(message: String) {
        if (isClosed) return
        channels.keys.toList().forEach { enqueueMessage(it, message) }
    }

    private fun broadcastExcept(message: String, excludedUid: String) {
        if (isClosed) return

        channels.keys.toList().forEach {
            if (it != excludedUid) enqueueMessage(it, message)
        }
    }

    private fun removePeerListeners(uid: String) {
        peerListeners.remove(uid)?.let { registrations ->
            synchronized(registrations) {
                registrations.forEach {
                    try {
                        it.ref.removeEventListener(it.listener)
                    } catch (e: Exception) {
                        Log.e(TAG, "[$uid] Error removing Firebase value listener", e)
                    }
                }
                registrations.clear()
            }
        }

        peerChildListeners.remove(uid)?.let { registrations ->
            synchronized(registrations) {
                registrations.forEach {
                    try {
                        it.ref.removeEventListener(it.listener)
                    } catch (e: Exception) {
                        Log.e(TAG, "[$uid] Error removing Firebase child listener", e)
                    }
                }
                registrations.clear()
            }
        }

        remoteDescriptionPending.remove(uid)

        synchronized(pendingIceCandidates) {
            pendingIceCandidates.remove(uid)?.clear()
        }

        negotiationIds.remove(uid)
    }

    private fun closePeer(uid: String) {
        val channel: DataChannel?
        val peer: PeerConnection?

        synchronized(peerCloseLock) {
            if (!closingPeers.add(uid)) return

            channel = channels.remove(uid)
            peer = peers.remove(uid)

            outgoingQueues.remove(uid)?.clear()
            outgoingQueueSizes.remove(uid)
        }

        removePeerListeners(uid)
        participantDrawingPermissions.remove(uid)

        try {
            peerCloseExecutor.execute {
                try {
                    channel?.let {
                        try {
                            synchronized(it) {
                                it.unregisterObserver()
                                it.close()
                                it.dispose()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "[$uid] Error closing DataChannel", e)
                        }
                    }

                    peer?.let {
                        try {
                            it.close()
                            it.dispose()
                        } catch (e: Exception) {
                            Log.e(TAG, "[$uid] Error closing PeerConnection", e)
                        }
                    }
                } finally {
                    closingPeers.remove(uid)
                }
            }
        } catch (e: RejectedExecutionException) {
            Log.e(TAG, "[$uid] Peer cleanup executor is unavailable", e)
            closingPeers.remove(uid)
        }
    }

    private fun cleanupLocalSession() {
        val ref = roomRef

        connectionListener?.let {
            database.child(".info").child("connected").removeEventListener(it)
        }
        connectionListener = null
        hasSeenFirebaseConnection = false

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
                ref?.child("members")?.child(uid)?.child("canDraw")?.removeEventListener(it)
            }
        }

        moderationListener?.let {
            localUid?.let { uid ->
                ref?.child("moderation")?.child(uid)?.removeEventListener(it)
            }
        }

        membersListener = null
        participantsListener = null
        activeSessionListener = null
        drawingPermissionListener = null
        moderationListener = null

        val peerUids = (
                peers.keys +
                        channels.keys +
                        peerListeners.keys +
                        peerChildListeners.keys
                ).toSet()

        peerUids.forEach { uid ->
            closePeer(uid)
        }

        outgoingQueues.clear()
        outgoingQueueSizes.clear()

        synchronized(pendingIceCandidates) {
            pendingIceCandidates.clear()
        }

        remoteDescriptionPending.clear()
        negotiationIds.clear()
        incomingMaps.clear()
        participantDrawingPermissions.clear()

        sessionCode = null
        roomRef = null
        isHost = false

        listener.onParticipantsChanged(emptyList())
        listener.onStatus("Disconnected")
    }

    fun leaveSession(onComplete: ((Boolean, String?) -> Unit)? = null) {
        if (isClosed) {
            if (roomRef == null) {
                onComplete?.invoke(true, null)
                return
            }
        }

        isClosed = true

        val uid = localUid
        val ref = roomRef
        val wasHost = isHost

        if (ref == null) {
            cleanupLocalSession()
            onComplete?.invoke(true, null)
            return
        }

        if (wasHost) {
            cancelDisconnectCleanup(ref, uid, true) {
                fun removeRoom() {
                    cleanupLocalSession()

                    ref.removeValue()
                        .addOnSuccessListener {
                            onComplete?.invoke(true, null)
                        }
                        .addOnFailureListener { error ->
                            Log.e(TAG, "Could not remove session", error)
                            onComplete?.invoke(false, error.message ?: "Could not remove session")
                        }
                }

                ref.child("active").setValue(false)
                    .addOnSuccessListener {
                        removeRoom()
                    }
                    .addOnFailureListener { error ->
                        Log.e(TAG, "Could not deactivate session", error)
                        removeRoom()
                    }
            }

            return
        }

        if (uid == null) {
            cleanupLocalSession()
            onComplete?.invoke(false, "Firebase authentication is missing")
            return
        }

        cancelDisconnectCleanup(ref, uid, false) {
            cleanupLocalSession()

            ref.child("signals").child(uid).removeValue()
                .addOnSuccessListener {
                    ref.child("members").child(uid).removeValue()
                        .addOnSuccessListener {
                            onComplete?.invoke(true, null)
                        }
                        .addOnFailureListener { error ->
                            Log.e(TAG, "Could not remove participant from session", error)
                            onComplete?.invoke(false, error.message ?: "Could not leave session")
                        }
                }
                .addOnFailureListener { error ->
                    Log.e(TAG, "Could not remove participant signals", error)
                    onComplete?.invoke(false, error.message ?: "Could not remove participant signals")
                }
        }
    }

    fun shutdown() {
        leaveSession { _, _ ->
            try {
                peerCloseExecutor.execute {
                    try {
                        factory?.dispose()
                        factory = null
                    } catch (e: Exception) {
                        Log.e(TAG, "WebRTC factory disposal failed", e)
                    } finally {
                        peerCloseExecutor.shutdown()
                    }
                }
            } catch (e: RejectedExecutionException) {
                Log.e(TAG, "Could not schedule WebRTC factory disposal", e)
            }
        }
    }

    open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) {}
        override fun onSetFailure(error: String) {}
    }
}