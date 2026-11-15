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

class MultiplayerSessionManager(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onStatus(message: String)
        fun onSessionCreated(code: String)
        fun onParticipantJoined(name: String)
        fun onParticipantLeft(uid: String)

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
        ) {}

        fun onDrawingChannelReady(uid: String) {}
    }

    companion object {
        private const val TAG = "WTMultiplayer"
        private const val DATABASE_URL =
            "https://wt-tactical-map-5f0fb-default-rtdb.europe-west1.firebasedatabase.app/"

        private const val MAX_PARTICIPANTS = 8
        private const val CHUNK_SIZE = 12000
        private const val MAX_BUFFERED_AMOUNT = 256L * 1024L
    }

    private val auth = FirebaseAuth.getInstance()

    private val database =
        FirebaseDatabase.getInstance(DATABASE_URL).reference

    private var factory: PeerConnectionFactory? = null
    private var roomRef: DatabaseReference? = null
    private var membersListener: ChildEventListener? = null

    private val peers =
        ConcurrentHashMap<String, PeerConnection>()

    private val channels =
        ConcurrentHashMap<String, DataChannel>()

    private val outgoingQueues =
        ConcurrentHashMap<String, ConcurrentLinkedQueue<ByteArray>>()

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
                listener.onError(
                    "WebRTC initialization failed: ${e.message}"
                )
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

                    listener.onError(
                        "WebRTC initialization failed: ${e.message}"
                    )

                    onReady(false)
                }
            }
            .addOnFailureListener { error ->

                Log.e(
                    TAG,
                    "Firebase authentication failed",
                    error
                )

                listener.onError(
                    "Firebase authentication failed: ${error.message}"
                )

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

        isHost = true
        isClosed = false

        createUniqueCode(0, username)
    }

    private fun createUniqueCode(
        attempt: Int,
        username: String
    ) {
        if (attempt >= 10) {
            listener.onError(
                "Could not create a unique session code"
            )
            return
        }

        val code = "WT-" + UUID.randomUUID()
            .toString()
            .replace("-", "")
            .take(6)
            .uppercase()

        val ref = database
            .child("rooms")
            .child(code)

        ref.runTransaction(
            object : com.google.firebase.database.Transaction.Handler {

                override fun doTransaction(
                    currentData: com.google.firebase.database.MutableData
                ): com.google.firebase.database.Transaction.Result {

                    if (currentData.value != null) {
                        return com.google.firebase.database.Transaction.abort()
                    }

                    currentData.value = mapOf(
                        "host" to localUid,
                        "hostName" to username,
                        "createdAt" to System.currentTimeMillis(),
                        "active" to true,
                        "members" to mapOf(
                            localUid!! to mapOf(
                                "name" to username,
                                "host" to true
                            )
                        )
                    )

                    return com.google.firebase.database.Transaction.success(
                        currentData
                    )
                }

                override fun onComplete(
                    error: DatabaseError?,
                    committed: Boolean,
                    snapshot: DataSnapshot?
                ) {
                    if (error != null) {
                        Log.e(
                            TAG,
                            "Session creation Firebase error",
                            error.toException()
                        )

                        listener.onError(
                            "Firebase error: ${error.message}"
                        )
                        return
                    }

                    if (!committed) {
                        createUniqueCode(attempt + 1, username)
                        return
                    }

                    sessionCode = code
                    roomRef = ref

                    Log.d(TAG, "Session created: $code")

                    listenForMembers()

                    listener.onSessionCreated(code)

                    listener.onStatus(
                        "Waiting for participants..."
                    )
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

        val code = codeInput.trim().uppercase()

        if (!code.matches(Regex("WT-[A-Z0-9]{6}"))) {
            listener.onError("Invalid session code")
            return
        }

        isHost = false
        isClosed = false

        val ref = database
            .child("rooms")
            .child(code)

        ref.get()
            .addOnSuccessListener { snapshot ->

                if (
                    !snapshot.exists() ||
                    snapshot.child("active")
                        .getValue(Boolean::class.java) != true
                ) {
                    listener.onError(
                        "Session not found or already closed"
                    )
                    return@addOnSuccessListener
                }

                val members =
                    snapshot.child("members").childrenCount

                if (members >= MAX_PARTICIPANTS) {
                    listener.onError("Session is full")
                    return@addOnSuccessListener
                }

                sessionCode = code
                roomRef = ref

                val uid = localUid
                    ?: return@addOnSuccessListener

                ref.child("members")
                    .child(uid)
                    .setValue(
                        mapOf(
                            "name" to username,
                            "host" to false
                        )
                    )
                    .addOnSuccessListener {

                        Log.d(
                            TAG,
                            "Joined session $code as $uid"
                        )

                        listener.onStatus("Joining session...")

                        listenForHostOffer()
                    }
                    .addOnFailureListener { error ->

                        Log.e(TAG, "Could not join session", error)

                        listener.onError(
                            "Could not join session: ${error.message}"
                        )
                    }
            }
            .addOnFailureListener { error ->

                Log.e(TAG, "Could not find session", error)

                listener.onError(
                    "Could not find session: ${error.message}"
                )
            }
    }

    private fun listenForMembers() {
        val ref = roomRef ?: return

        val childListener = object : ChildEventListener {

            override fun onChildAdded(
                snapshot: DataSnapshot,
                previousChildName: String?
            ) {
                val uid = snapshot.key ?: return

                if (uid == localUid) return
                if (isClosed) return

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

            override fun onChildRemoved(
                snapshot: DataSnapshot
            ) {
                val uid = snapshot.key ?: return

                Log.d(TAG, "Participant left: $uid")

                closePeer(uid)

                listener.onParticipantLeft(uid)
            }

            override fun onChildMoved(
                snapshot: DataSnapshot,
                previousChildName: String?
            ) {
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(
                    TAG,
                    "Members listener cancelled",
                    error.toException()
                )

                listener.onError(error.message)
            }
        }

        membersListener = childListener

        ref.child("members")
            .addChildEventListener(childListener)
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
                sdpSemantics =
                    PeerConnection.SdpSemantics.UNIFIED_PLAN
            }

        return try {

            val peer = rtcFactory.createPeerConnection(
                configuration,
                object : PeerConnection.Observer {

                    override fun onSignalingChange(
                        state: PeerConnection.SignalingState
                    ) {
                        Log.d(
                            TAG,
                            "[$uid] Signaling state: $state"
                        )
                    }

                    override fun onIceConnectionChange(
                        state: PeerConnection.IceConnectionState
                    ) {
                        Log.d(
                            TAG,
                            "[$uid] ICE connection state: $state"
                        )

                        when (state) {
                            PeerConnection.IceConnectionState.CONNECTED,
                            PeerConnection.IceConnectionState.COMPLETED -> {
                                listener.onStatus(
                                    "Connected to multiplayer session"
                                )
                            }

                            PeerConnection.IceConnectionState.DISCONNECTED -> {
                                listener.onStatus(
                                    "Connection interrupted"
                                )
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

                    override fun onIceConnectionReceivingChange(
                        receiving: Boolean
                    ) {
                        Log.d(
                            TAG,
                            "[$uid] ICE receiving: $receiving"
                        )
                    }

                    override fun onIceGatheringChange(
                        state: PeerConnection.IceGatheringState
                    ) {
                        Log.d(
                            TAG,
                            "[$uid] ICE gathering state: $state"
                        )
                    }

                    override fun onIceCandidate(
                        candidate: IceCandidate
                    ) {
                        Log.d(
                            TAG,
                            "[$uid] Local ICE candidate: ${candidate.sdp}"
                        )

                        sendIceCandidate(uid, candidate)
                    }

                    override fun onIceCandidatesRemoved(
                        candidates: Array<out IceCandidate>
                    ) {
                        Log.d(
                            TAG,
                            "[$uid] ICE candidates removed: ${candidates.size}"
                        )
                    }

                    override fun onAddStream(
                        stream: org.webrtc.MediaStream
                    ) {
                    }

                    override fun onRemoveStream(
                        stream: org.webrtc.MediaStream
                    ) {
                    }

                    override fun onDataChannel(
                        channel: DataChannel
                    ) {
                        Log.d(
                            TAG,
                            "[$uid] Remote DataChannel received"
                        )

                        channels[uid] = channel

                        registerDataChannel(uid, channel)

                        onRemoteDataChannel(channel)
                    }

                    override fun onRenegotiationNeeded() {
                        Log.d(
                            TAG,
                            "[$uid] Renegotiation needed"
                        )
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
        if (isClosed) return

        if (peers.containsKey(uid)) {
            Log.d(TAG, "[$uid] Peer already exists")
            return
        }

        val peer = createPeer(uid) {}
            ?: run {
                listener.onError(
                    "Could not create WebRTC connection"
                )
                return
            }

        val channel = peer.createDataChannel(
            "wt-map",
            DataChannel.Init().apply {
                ordered = true
            }
        )

        if (channel == null) {
            listener.onError(
                "Could not create DataChannel"
            )
            closePeer(uid)
            return
        }

        channels[uid] = channel

        registerDataChannel(uid, channel)

        Log.d(TAG, "[$uid] Creating offer")

        peer.createOffer(
            object : SdpObserverAdapter() {

                override fun onCreateSuccess(
                    description: SessionDescription
                ) {
                    Log.d(TAG, "[$uid] Offer created")

                    peer.setLocalDescription(
                        object : SdpObserverAdapter() {

                            override fun onSetSuccess() {
                                Log.d(
                                    TAG,
                                    "[$uid] Local offer successfully set"
                                )

                                val signal = roomRef
                                    ?.child("signals")
                                    ?.child(uid)

                                if (signal == null) {
                                    listener.onError(
                                        "Could not access signaling database"
                                    )
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
                                        Log.d(
                                            TAG,
                                            "[$uid] Offer sent to Firebase"
                                        )

                                        listener.onStatus(
                                            "Offer sent. Waiting for answer..."
                                        )
                                    }
                                    .addOnFailureListener { error ->

                                        Log.e(
                                            TAG,
                                            "[$uid] Offer send failed",
                                            error
                                        )

                                        listener.onError(
                                            "Could not send offer: ${error.message}"
                                        )
                                    }
                            }

                            override fun onSetFailure(error: String) {
                                Log.e(
                                    TAG,
                                    "[$uid] Setting local offer failed: $error"
                                )

                                listener.onError(
                                    "Could not set local offer: $error"
                                )
                            }
                        },
                        description
                    )
                }

                override fun onCreateFailure(error: String) {
                    Log.e(
                        TAG,
                        "[$uid] Offer creation failed: $error"
                    )

                    listener.onError(
                        "Offer creation failed: $error"
                    )
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
            ?.child("answer")
            ?: return

        val valueListener = object : ValueEventListener {

            override fun onDataChange(
                snapshot: DataSnapshot
            ) {
                if (isClosed) return

                val sdp = snapshot.child("sdp")
                    .getValue(String::class.java) ?: return

                val type = snapshot.child("type")
                    .getValue(String::class.java) ?: "answer"

                if (peer.remoteDescription != null) return

                if (!remoteDescriptionPending.add(uid)) {
                    return
                }

                Log.d(TAG, "[$uid] Answer received from Firebase")

                peer.setRemoteDescription(
                    object : SdpObserverAdapter() {

                        override fun onSetSuccess() {
                            remoteDescriptionPending.remove(uid)

                            Log.d(
                                TAG,
                                "[$uid] Remote answer successfully set"
                            )

                            flushPendingIceCandidates(uid, peer)
                        }

                        override fun onSetFailure(error: String) {
                            remoteDescriptionPending.remove(uid)

                            Log.e(
                                TAG,
                                "[$uid] Setting remote answer failed: $error"
                            )

                            listener.onError(
                                "Remote answer failed: $error"
                            )
                        }
                    },
                    SessionDescription(
                        SessionDescription.Type.fromCanonicalForm(type),
                        sdp
                    )
                )
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(
                    TAG,
                    "[$uid] Answer listener cancelled",
                    error.toException()
                )

                listener.onError(
                    "Answer listener error: ${error.message}"
                )
            }
        }

        ref.addValueEventListener(valueListener)

        peerListeners.getOrPut(uid) {
            Collections.synchronizedList(mutableListOf())
        }.add(
            ValueListenerRegistration(ref, valueListener)
        )
    }

    private fun listenForHostOffer() {
        val uid = localUid ?: return

        val ref = roomRef
            ?.child("signals")
            ?.child(uid)
            ?: return

        val offerRef = ref.child("offer")

        val offerListener = object : ValueEventListener {

            override fun onDataChange(
                snapshot: DataSnapshot
            ) {
                if (isClosed) return

                val sdp = snapshot.child("sdp")
                    .getValue(String::class.java) ?: return

                val type = snapshot.child("type")
                    .getValue(String::class.java) ?: "offer"

                if (peers.containsKey("host")) return

                if (!remoteDescriptionPending.add("host")) {
                    return
                }

                listener.onStatus(
                    "Offer received. Creating connection..."
                )

                Log.d(TAG, "[host] Offer received")

                val peer = createPeer("host") {}
                    ?: run {
                        remoteDescriptionPending.remove("host")

                        listener.onError(
                            "Could not create WebRTC connection"
                        )
                        return
                    }

                listenForRemoteCandidates(
                    "host",
                    "hostCandidates",
                    peer
                )

                peer.setRemoteDescription(
                    object : SdpObserverAdapter() {

                        override fun onSetSuccess() {
                            remoteDescriptionPending.remove("host")

                            Log.d(
                                TAG,
                                "[host] Remote offer successfully set"
                            )

                            flushPendingIceCandidates("host", peer)

                            listener.onStatus(
                                "Offer received. Creating answer..."
                            )

                            peer.createAnswer(
                                object : SdpObserverAdapter() {

                                    override fun onCreateSuccess(
                                        description: SessionDescription
                                    ) {
                                        Log.d(
                                            TAG,
                                            "[host] Answer created"
                                        )

                                        peer.setLocalDescription(
                                            object : SdpObserverAdapter() {

                                                override fun onSetSuccess() {
                                                    Log.d(
                                                        TAG,
                                                        "[host] Local answer successfully set"
                                                    )

                                                    ref.child("answer")
                                                        .setValue(
                                                            mapOf(
                                                                "type" to description.type.canonicalForm(),
                                                                "sdp" to description.description
                                                            )
                                                        )
                                                        .addOnSuccessListener {

                                                            Log.d(
                                                                TAG,
                                                                "[host] Answer sent to Firebase"
                                                            )

                                                            listener.onStatus(
                                                                "Answer sent. Connecting..."
                                                            )
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

                                                override fun onSetFailure(
                                                    error: String
                                                ) {
                                                    Log.e(
                                                        TAG,
                                                        "[host] Setting local answer failed: $error"
                                                    )

                                                    listener.onError(
                                                        "Could not set local answer: $error"
                                                    )
                                                }
                                            },
                                            description
                                        )
                                    }

                                    override fun onCreateFailure(
                                        error: String
                                    ) {
                                        Log.e(
                                            TAG,
                                            "[host] Answer creation failed: $error"
                                        )

                                        listener.onError(
                                            "Answer creation failed: $error"
                                        )
                                    }
                                },
                                MediaConstraints()
                            )
                        }

                        override fun onSetFailure(error: String) {
                            remoteDescriptionPending.remove("host")

                            Log.e(
                                TAG,
                                "[host] Setting remote offer failed: $error"
                            )

                            listener.onError(
                                "Could not set offer: $error"
                            )
                        }
                    },
                    SessionDescription(
                        SessionDescription.Type.fromCanonicalForm(type),
                        sdp
                    )
                )
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(
                    TAG,
                    "Offer listener cancelled",
                    error.toException()
                )

                listener.onError(
                    "Offer listener error: ${error.message}"
                )
            }
        }

        offerRef.addValueEventListener(offerListener)

        peerListeners.getOrPut("host") {
            Collections.synchronizedList(mutableListOf())
        }.add(
            ValueListenerRegistration(
                offerRef,
                offerListener
            )
        )
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
            .addOnSuccessListener {
                Log.d(
                    TAG,
                    "[$uid] ICE candidate sent"
                )
            }
            .addOnFailureListener { error ->

                Log.e(
                    TAG,
                    "[$uid] ICE candidate send failed",
                    error
                )

                listener.onError(
                    "Could not send ICE candidate: ${error.message}"
                )
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

                Log.d(
                    TAG,
                    "[$uid] ICE candidate added immediately: $added"
                )

            } else {

                val candidates = pendingIceCandidates.getOrPut(uid) {
                    Collections.synchronizedList(mutableListOf())
                }

                candidates.add(candidate)

                Log.d(
                    TAG,
                    "[$uid] ICE candidate buffered. Count: ${candidates.size}"
                )
            }
        }
    }

    private fun flushPendingIceCandidates(
        uid: String,
        peer: PeerConnection
    ) {
        synchronized(pendingIceCandidates) {

            val candidates =
                pendingIceCandidates.remove(uid) ?: return

            synchronized(candidates) {

                Log.d(
                    TAG,
                    "[$uid] Applying ${candidates.size} buffered ICE candidates"
                )

                candidates.forEach { candidate ->

                    val added = peer.addIceCandidate(candidate)

                    Log.d(
                        TAG,
                        "[$uid] Buffered ICE candidate added: $added"
                    )
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

                val candidate = IceCandidate(
                    mid,
                    line,
                    sdp
                )

                Log.d(
                    TAG,
                    "[$uid] Remote ICE candidate received"
                )

                addIceCandidateSafely(
                    uid,
                    peer,
                    candidate
                )
            }

            override fun onChildChanged(
                snapshot: DataSnapshot,
                previousChildName: String?
            ) {
            }

            override fun onChildRemoved(
                snapshot: DataSnapshot
            ) {
            }

            override fun onChildMoved(
                snapshot: DataSnapshot,
                previousChildName: String?
            ) {
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(
                    TAG,
                    "[$uid] ICE candidate listener cancelled",
                    error.toException()
                )

                listener.onError(
                    "ICE listener error: ${error.message}"
                )
            }
        }

        ref.addChildEventListener(childListener)

        peerChildListeners.getOrPut(uid) {
            Collections.synchronizedList(mutableListOf())
        }.add(
            ChildListenerRegistration(
                ref,
                childListener
            )
        )

        Log.d(
            TAG,
            "[$uid] Listening for remote ICE candidates: $pathUid/$candidatePath"
        )
    }

    private fun registerDataChannel(
        uid: String,
        channel: DataChannel
    ) {
        outgoingQueues.putIfAbsent(
            uid,
            ConcurrentLinkedQueue()
        )

        channel.registerObserver(
            object : DataChannel.Observer {

                override fun onBufferedAmountChange(
                    previousAmount: Long
                ) {
                    drainOutgoingQueue(uid)
                }

                override fun onStateChange() {
                    val state = channel.state()

                    Log.d(
                        TAG,
                        "[$uid] DataChannel state: $state"
                    )

                    if (state == DataChannel.State.OPEN) {
                        drainOutgoingQueue(uid)

                        listener.onStatus(
                            "Data channel connected"
                        )

                        listener.onDrawingChannelReady(uid)

                        if (!isHost) {
                            sendDrawingMessage(
                                JSONObject()
                                    .put(
                                        "type",
                                        "drawingSnapshotRequest"
                                    )
                            )
                        }
                    }

                    if (state == DataChannel.State.CLOSED) {
                        Log.d(
                            TAG,
                            "[$uid] DataChannel closed"
                        )
                    }
                }

                override fun onMessage(
                    buffer: DataChannel.Buffer
                ) {
                    if (buffer.binary) return

                    val bytes = ByteArray(
                        buffer.data.remaining()
                    )

                    buffer.data.get(bytes)

                    handleIncomingMessage(
                        uid,
                        String(bytes, Charsets.UTF_8)
                    )
                }
            }
        )
    }

    private fun enqueueMessage(
        uid: String,
        message: String
    ) {
        if (isClosed) return

        val channel = channels[uid] ?: run {
            Log.w(
                TAG,
                "[$uid] Cannot queue message. DataChannel missing."
            )
            return
        }

        val queue = outgoingQueues.getOrPut(uid) {
            ConcurrentLinkedQueue()
        }

        queue.add(
            message.toByteArray(Charsets.UTF_8)
        )

        drainOutgoingQueue(uid)
    }

    private fun drainOutgoingQueue(uid: String) {
        if (isClosed) return

        val channel = channels[uid] ?: return
        val queue = outgoingQueues[uid] ?: return

        synchronized(channel) {
            if (channel.state() != DataChannel.State.OPEN) {
                return
            }

            while (
                channel.bufferedAmount() < MAX_BUFFERED_AMOUNT
            ) {
                val bytes = queue.peek() ?: break

                val sent = try {
                    channel.send(
                        DataChannel.Buffer(
                            ByteBuffer.wrap(bytes),
                            false
                        )
                    )
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "[$uid] DataChannel send exception",
                        e
                    )
                    false
                }

                if (!sent) {
                    Log.w(
                        TAG,
                        "[$uid] DataChannel buffer full. Waiting."
                    )
                    break
                }

                queue.poll()
            }

            Log.d(
                TAG,
                "[$uid] Outgoing queue: ${queue.size}, buffered: ${channel.bufferedAmount()}"
            )
        }
    }

    private fun handleIncomingMessage(
        senderUid: String,
        message: String
    ) {
        try {
            val json = JSONObject(message)

            when (json.optString("type")) {

                "objects" -> {
                    listener.onObjectsReceived(
                        JSONObject(
                            json.optString("data", "{}")
                        )
                    )
                }

                "mapStart" -> {
                    val id = json.getString("id")

                    val info = JSONObject(
                        json.getString("info")
                    )

                    val objects = JSONObject(
                        json.getString("objects")
                    )

                    val total = json.getInt("total")

                    if (total < 0) {
                        Log.e(TAG, "Invalid map chunk count: $total")
                        return
                    }

                    incomingMaps[id] = IncomingMap(
                        info,
                        objects,
                        ConcurrentHashMap<Int, ByteArray>(),
                        total
                    )

                    Log.d(
                        TAG,
                        "Incoming map started: $id, chunks: $total"
                    )
                }

                "mapChunk" -> {
                    val id = json.getString("id")

                    val map = incomingMaps[id] ?: return

                    val index = json.getInt("index")

                    if (index < 0 || index >= map.totalChunks) {
                        Log.e(
                            TAG,
                            "Invalid map chunk index: $index"
                        )
                        return
                    }

                    val data = Base64.decode(
                        json.getString("data"),
                        Base64.NO_WRAP
                    )

                    map.chunks[index] = data
                }

                "mapEnd" -> {
                    val id = json.getString("id")

                    val map = incomingMaps.remove(id) ?: return

                    val output = ByteArrayOutputStream()

                    for (index in 0 until map.totalChunks) {
                        val chunk = map.chunks[index]

                        if (chunk == null) {
                            Log.e(
                                TAG,
                                "Map transfer incomplete. Missing chunk $index"
                            )

                            listener.onError(
                                "Map transfer incomplete. Missing data."
                            )

                            return
                        }

                        output.write(chunk)
                    }

                    Log.d(
                        TAG,
                        "Map received successfully: $id, bytes: ${output.size()}"
                    )

                    listener.onMapReceived(
                        map.info,
                        map.objects,
                        output.toByteArray()
                    )
                }

                "drawingSnapshotRequest" -> {
                    if (isHost) {
                        listener.onDrawingMessage(
                            senderUid,
                            json
                        )
                    }
                }

                "drawingSnapshot",
                "drawingStroke",
                "drawingUndo",
                "drawingRedo",
                "drawingLegend" -> {

                    listener.onDrawingMessage(
                        senderUid,
                        json
                    )

                    if (isHost) {
                        broadcastExcept(
                            message,
                            senderUid
                        )
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(
                TAG,
                "Invalid DataChannel message",
                e
            )
        }
    }

    fun sendDrawingMessage(
        message: JSONObject,
        targetUid: String? = null
    ) {
        if (isClosed) return

        val encoded = message.toString()

        if (isHost) {

            if (targetUid != null) {
                sendToPeer(
                    targetUid,
                    encoded
                )
            } else {
                broadcast(encoded)
            }

        } else {

            sendToPeer(
                "host",
                encoded
            )
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
        image: ByteArray?
    ) {
        if (!isHost || isClosed) return

        val imageBytes = image ?: ByteArray(0)

        val id = UUID.randomUUID().toString()

        val encoded = Base64.encodeToString(
            imageBytes,
            Base64.NO_WRAP
        )

        val chunks = encoded.chunked(CHUNK_SIZE)

        val start = JSONObject()
            .put("type", "mapStart")
            .put("id", id)
            .put("info", info.toString())
            .put("objects", objects.toString())
            .put("total", chunks.size)

        broadcast(start.toString())

        chunks.forEachIndexed { index, chunk ->

            val message = JSONObject()
                .put("type", "mapChunk")
                .put("id", id)
                .put("index", index)
                .put("data", chunk)

            broadcast(message.toString())
        }

        broadcast(
            JSONObject()
                .put("type", "mapEnd")
                .put("id", id)
                .toString()
        )

        Log.d(
            TAG,
            "Map queued. ID: $id, chunks: ${chunks.size}, bytes: ${imageBytes.size}"
        )
    }

    fun sendObjects(objects: JSONObject) {
        if (!isHost || isClosed) return

        broadcast(
            JSONObject()
                .put("type", "objects")
                .put("data", objects.toString())
                .toString()
        )
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
                    registration.ref.removeEventListener(
                        registration.listener
                    )
                }

                registrations.clear()
            }
        }

        peerChildListeners.remove(uid)?.let { registrations ->

            synchronized(registrations) {
                registrations.forEach { registration ->
                    registration.ref.removeEventListener(
                        registration.listener
                    )
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

        channels.remove(uid)?.let { channel ->
            try {
                channel.unregisterObserver()
                channel.close()
                channel.dispose()
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "[$uid] Error closing DataChannel",
                    e
                )
            }
        }

        peers.remove(uid)?.let { peer ->
            try {
                peer.close()
                peer.dispose()
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "[$uid] Error closing PeerConnection",
                    e
                )
            }
        }
    }

    fun leaveSession() {
        if (isClosed) return

        isClosed = true

        Log.d(TAG, "Leaving multiplayer session")

        val uid = localUid
        val ref = roomRef

        if (isHost) {

            ref?.child("active")?.setValue(false)

            ref?.child("members")?.removeValue()

        } else if (uid != null) {

            ref?.child("members")
                ?.child(uid)
                ?.removeValue()
        }

        membersListener?.let { memberListener ->
            ref?.child("members")
                ?.removeEventListener(memberListener)
        }

        membersListener = null

        peerListeners.keys.toList().forEach {
            removePeerListeners(it)
        }

        peerChildListeners.keys.toList().forEach {
            removePeerListeners(it)
        }

        peers.keys.toList().forEach { peerUid ->

            outgoingQueues.remove(peerUid)?.clear()

            channels.remove(peerUid)?.let { channel ->
                try {
                    channel.unregisterObserver()
                    channel.close()
                    channel.dispose()
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "Error disposing channel $peerUid",
                        e
                    )
                }
            }

            peers.remove(peerUid)?.let { peer ->
                try {
                    peer.close()
                    peer.dispose()
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "Error disposing peer $peerUid",
                        e
                    )
                }
            }
        }

        channels.clear()
        peers.clear()
        outgoingQueues.clear()

        pendingIceCandidates.clear()
        remoteDescriptionPending.clear()

        incomingMaps.clear()

        peerListeners.clear()
        peerChildListeners.clear()

        sessionCode = null
        roomRef = null
        membersListener = null

        isHost = false

        listener.onStatus("Disconnected")
    }

    fun shutdown() {
        leaveSession()

        factory?.dispose()
        factory = null

        Log.d(TAG, "Multiplayer manager shutdown")
    }

    open class SdpObserverAdapter : SdpObserver {

        override fun onCreateSuccess(
            description: SessionDescription
        ) {
        }

        override fun onSetSuccess() {
        }

        override fun onCreateFailure(error: String) {
        }

        override fun onSetFailure(error: String) {
        }
    }
}