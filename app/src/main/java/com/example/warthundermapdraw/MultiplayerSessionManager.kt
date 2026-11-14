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
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MultiplayerSessionManager(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onStatus(message: String)
        fun onSessionCreated(code: String)
        fun onParticipantJoined(name: String)
        fun onParticipantLeft(uid: String)
        fun onMapReceived(info: JSONObject, objects: JSONObject, image: ByteArray?)
        fun onObjectsReceived(objects: JSONObject)
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "WTMultiplayer"
        private const val DATABASE_URL =
            "https://wt-tactical-map-5f0fb-default-rtdb.europe-west1.firebasedatabase.app/"
        private const val MAX_PARTICIPANTS = 8
        private const val CHUNK_SIZE = 12000
    }

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance(DATABASE_URL).reference

    private var factory: PeerConnectionFactory? = null
    private var roomRef: DatabaseReference? = null
    private var membersListener: ChildEventListener? = null

    private val peers = ConcurrentHashMap<String, PeerConnection>()
    private val channels = ConcurrentHashMap<String, DataChannel>()
    private val peerListeners =
        ConcurrentHashMap<String, MutableList<ValueEventListener>>()

    private var sessionCode: String? = null
    private var localUid: String? = null
    private var isHost = false
    private var isClosed = false

    private data class IncomingMap(
        val info: JSONObject,
        val objects: JSONObject,
        val chunks: MutableMap<Int, ByteArray>,
        val totalChunks: Int
    )

    private val incomingMaps = ConcurrentHashMap<String, IncomingMap>()

    fun initialize(onReady: (Boolean) -> Unit) {
        isClosed = false

        val currentUser = auth.currentUser

        if (currentUser != null) {
            localUid = currentUser.uid
            initializeWebRtc()
            onReady(true)
            return
        }

        auth.signInAnonymously()
            .addOnSuccessListener {
                localUid = it.user?.uid
                initializeWebRtc()
                onReady(localUid != null)
            }
            .addOnFailureListener {
                Log.e(TAG, "Firebase authentication failed", it)
                listener.onError("Firebase authentication failed: ${it.message}")
                onReady(false)
            }
    }

    private fun initializeWebRtc() {
        if (factory != null) return

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(context)
                .createInitializationOptions()
        )

        factory = PeerConnectionFactory.builder()
            .createPeerConnectionFactory()
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

    private fun createUniqueCode(attempt: Int, username: String) {
        if (attempt >= 10) {
            listener.onError("Could not create a unique session code")
            return
        }

        val code = "WT-" + UUID.randomUUID()
            .toString()
            .replace("-", "")
            .take(6)
            .uppercase()

        val ref = database.child("rooms").child(code)

        ref.runTransaction(object : com.google.firebase.database.Transaction.Handler {

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

                return com.google.firebase.database.Transaction.success(currentData)
            }

            override fun onComplete(
                error: DatabaseError?,
                committed: Boolean,
                snapshot: DataSnapshot?
            ) {
                if (error != null) {
                    listener.onError("Firebase error: ${error.message}")
                    return
                }

                if (!committed) {
                    createUniqueCode(attempt + 1, username)
                    return
                }

                sessionCode = code
                roomRef = ref

                listenForMembers()

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

        val code = codeInput.trim().uppercase()

        if (!code.matches(Regex("WT-[A-Z0-9]{6}"))) {
            listener.onError("Invalid session code")
            return
        }

        isHost = false
        isClosed = false

        val ref = database.child("rooms").child(code)

        ref.get()
            .addOnSuccessListener { snapshot ->

                if (
                    !snapshot.exists() ||
                    snapshot.child("active").getValue(Boolean::class.java) != true
                ) {
                    listener.onError("Session not found or already closed")
                    return@addOnSuccessListener
                }

                val members = snapshot.child("members").childrenCount

                if (members >= MAX_PARTICIPANTS) {
                    listener.onError("Session is full")
                    return@addOnSuccessListener
                }

                sessionCode = code
                roomRef = ref

                val uid = localUid ?: return@addOnSuccessListener

                ref.child("members").child(uid).setValue(
                    mapOf(
                        "name" to username,
                        "host" to false
                    )
                ).addOnSuccessListener {
                    listener.onStatus("Joining session...")
                    listenForHostOffer()
                }.addOnFailureListener {
                    listener.onError("Could not join session: ${it.message}")
                }

            }
            .addOnFailureListener {
                listener.onError("Could not find session: ${it.message}")
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

                val name = snapshot.child("name")
                    .getValue(String::class.java) ?: "Player"

                this@MultiplayerSessionManager.listener
                    .onParticipantJoined(name)

                createOfferForParticipant(uid)
            }

            override fun onChildChanged(
                snapshot: DataSnapshot,
                previousChildName: String?
            ) {
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                val uid = snapshot.key ?: return

                closePeer(uid)

                this@MultiplayerSessionManager.listener
                    .onParticipantLeft(uid)
            }

            override fun onChildMoved(
                snapshot: DataSnapshot,
                previousChildName: String?
            ) {
            }

            override fun onCancelled(error: DatabaseError) {
                this@MultiplayerSessionManager.listener
                    .onError(error.message)
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

        val configuration = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val peer = rtcFactory.createPeerConnection(
            configuration,
            object : PeerConnection.Observer {

                override fun onSignalingChange(
                    state: PeerConnection.SignalingState
                ) {
                }

                override fun onIceConnectionChange(
                    state: PeerConnection.IceConnectionState
                ) {
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            listener.onStatus("Connected to multiplayer session")
                        }

                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            listener.onStatus("Connection interrupted")
                        }

                        PeerConnection.IceConnectionState.FAILED -> {
                            listener.onError("WebRTC connection failed")
                        }

                        else -> {
                        }
                    }
                }

                override fun onIceConnectionReceivingChange(
                    receiving: Boolean
                ) {
                }

                override fun onIceGatheringChange(
                    state: PeerConnection.IceGatheringState
                ) {
                }

                override fun onIceCandidate(candidate: IceCandidate) {
                    sendIceCandidate(uid, candidate)
                }

                override fun onIceCandidatesRemoved(
                    candidates: Array<out IceCandidate>
                ) {
                }

                override fun onAddStream(stream: org.webrtc.MediaStream) {
                }

                override fun onRemoveStream(stream: org.webrtc.MediaStream) {
                }

                override fun onDataChannel(channel: DataChannel) {
                    channels[uid] = channel
                    registerDataChannel(uid, channel)
                    onRemoteDataChannel(channel)
                }

                override fun onRenegotiationNeeded() {
                }

                override fun onAddTrack(
                    receiver: org.webrtc.RtpReceiver,
                    mediaStreams: Array<out org.webrtc.MediaStream>
                ) {
                }
            }
        )

        if (peer != null) {
            peers[uid] = peer
        }

        return peer
    }

    private fun createOfferForParticipant(uid: String) {
        val peer = createPeer(uid) {} ?: run {
            listener.onError("Could not create WebRTC connection")
            return
        }

        val channel = peer.createDataChannel(
            "wt-map",
            DataChannel.Init().apply {
                ordered = true
            }
        )

        channels[uid] = channel
        registerDataChannel(uid, channel)

        peer.createOffer(
            object : SdpObserverAdapter() {

                override fun onCreateSuccess(description: SessionDescription) {
                    peer.setLocalDescription(
                        SdpObserverAdapter(),
                        description
                    )

                    val signal = roomRef
                        ?.child("signals")
                        ?.child(uid)
                        ?: return

                    signal.child("offer").setValue(
                        mapOf(
                            "type" to description.type.canonicalForm(),
                            "sdp" to description.description
                        )
                    )

                    listenForAnswer(uid, peer)
                    listenForRemoteCandidates(
                        uid,
                        "participantCandidates",
                        peer
                    )
                }

                override fun onCreateFailure(error: String) {
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
            ?.child("answer")
            ?: return

        val valueListener = object : ValueEventListener {

            override fun onDataChange(snapshot: DataSnapshot) {
                val sdp = snapshot.child("sdp")
                    .getValue(String::class.java) ?: return

                val type = snapshot.child("type")
                    .getValue(String::class.java) ?: "answer"

                if (peer.remoteDescription != null) return

                peer.setRemoteDescription(
                    SdpObserverAdapter(),
                    SessionDescription(
                        SessionDescription.Type.fromCanonicalForm(type),
                        sdp
                    )
                )
            }

            override fun onCancelled(error: DatabaseError) {
                listener.onError(error.message)
            }
        }

        ref.addValueEventListener(valueListener)

        peerListeners.getOrPut(uid) {
            mutableListOf()
        }.add(valueListener)
    }


    private fun listenForHostOffer() {
        val uid = localUid ?: return

        val ref = roomRef
            ?.child("signals")
            ?.child(uid)
            ?: return

        val offerRef = ref.child("offer")

        val offerListener = object : ValueEventListener {

            override fun onDataChange(snapshot: DataSnapshot) {
                val sdp = snapshot.child("sdp")
                    .getValue(String::class.java) ?: return

                val type = snapshot.child("type")
                    .getValue(String::class.java) ?: "offer"

                if (peers.containsKey("host")) return

                listener.onStatus("Offer received. Creating connection...")

                val peer = createPeer("host") {} ?: run {
                    listener.onError("Could not create WebRTC connection")
                    return
                }

                peer.setRemoteDescription(
                    object : SdpObserverAdapter() {

                        override fun onSetSuccess() {
                            listener.onStatus("Offer received. Creating answer...")

                            peer.createAnswer(
                                object : SdpObserverAdapter() {

                                    override fun onCreateSuccess(
                                        description: SessionDescription
                                    ) {
                                        peer.setLocalDescription(
                                            object : SdpObserverAdapter() {
                                                override fun onSetSuccess() {
                                                    ref.child("answer").setValue(
                                                        mapOf(
                                                            "type" to description.type.canonicalForm(),
                                                            "sdp" to description.description
                                                        )
                                                    ).addOnSuccessListener {
                                                        listener.onStatus("Answer sent. Connecting...")
                                                    }.addOnFailureListener {
                                                        listener.onError(
                                                            "Could not send answer: ${it.message}"
                                                        )
                                                    }

                                                    listenForRemoteCandidates(
                                                        "host",
                                                        "hostCandidates",
                                                        peer
                                                    )
                                                }

                                                override fun onSetFailure(error: String) {
                                                    listener.onError(
                                                        "Could not set local answer: $error"
                                                    )
                                                }
                                            },
                                            description
                                        )
                                    }

                                    override fun onCreateFailure(error: String) {
                                        listener.onError(
                                            "Answer creation failed: $error"
                                        )
                                    }
                                },
                                MediaConstraints()
                            )
                        }

                        override fun onSetFailure(error: String) {
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
                listener.onError("Offer listener error: ${error.message}")
            }
        }

        offerRef.addValueEventListener(offerListener)

        peerListeners.getOrPut("host") {
            mutableListOf()
        }.add(offerListener)
    }

    private fun sendIceCandidate(
        uid: String,
        candidate: IceCandidate
    ) {
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
                val sdp = snapshot.child("sdp")
                    .getValue(String::class.java) ?: return

                val mid = snapshot.child("sdpMid")
                    .getValue(String::class.java)

                val line = snapshot.child("sdpMLineIndex")
                    .getValue(Int::class.java) ?: 0

                peer.addIceCandidate(
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
                listener.onError(error.message)
            }
        }

        ref.addChildEventListener(childListener)
    }

    private fun registerDataChannel(
        uid: String,
        channel: DataChannel
    ) {
        channel.registerObserver(object : DataChannel.Observer {

            override fun onBufferedAmountChange(previousAmount: Long) {
            }

            override fun onStateChange() {
                if (channel.state() == DataChannel.State.OPEN) {
                    listener.onStatus("Data channel connected")
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return

                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)

                handleIncomingMessage(
                    String(bytes, Charsets.UTF_8)
                )
            }
        })
    }

    private fun handleIncomingMessage(message: String) {
        try {
            val json = JSONObject(message)

            when (json.optString("type")) {

                "objects" -> {
                    listener.onObjectsReceived(
                        JSONObject(json.optString("data", "{}"))
                    )
                }

                "mapStart" -> {
                    val id = json.getString("id")
                    val info = JSONObject(json.getString("info"))
                    val objects = JSONObject(json.getString("objects"))
                    val total = json.getInt("total")

                    incomingMaps[id] = IncomingMap(
                        info,
                        objects,
                        ConcurrentHashMap<Int, ByteArray>(),
                        total
                    )
                }

                "mapChunk" -> {
                    val id = json.getString("id")
                    val map = incomingMaps[id] ?: return

                    val index = json.getInt("index")

                    val data = Base64.decode(
                        json.getString("data"),
                        Base64.NO_WRAP
                    )

                    map.chunks[index] = data
                }

                "mapEnd" -> {
                    val id = json.getString("id")
                    val map = incomingMaps.remove(id) ?: return

                    val output = java.io.ByteArrayOutputStream()

                    for (index in 0 until map.totalChunks) {
                        val chunk = map.chunks[index] ?: return
                        output.write(chunk)
                    }

                    listener.onMapReceived(
                        map.info,
                        map.objects,
                        output.toByteArray()
                    )
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Invalid DataChannel message", e)
        }
    }

    fun sendMap(
        info: JSONObject,
        objects: JSONObject,
        image: ByteArray?
    ) {
        if (!isHost) return

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
    }

    fun sendObjects(objects: JSONObject) {
        if (!isHost) return

        broadcast(
            JSONObject()
                .put("type", "objects")
                .put("data", objects.toString())
                .toString()
        )
    }

    private fun broadcast(message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)

        channels.values.forEach { channel ->
            if (channel.state() == DataChannel.State.OPEN) {
                channel.send(
                    DataChannel.Buffer(
                        ByteBuffer.wrap(bytes),
                        false
                    )
                )
            }
        }
    }

    private fun closePeer(uid: String) {
        channels.remove(uid)?.dispose()

        peers.remove(uid)?.let {
            it.close()
            it.dispose()
        }

        peerListeners.remove(uid)?.forEach {
            roomRef?.child("signals")
                ?.child(uid)
                ?.removeEventListener(it)
        }
    }

    fun leaveSession() {
        isClosed = true

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

        membersListener?.let {
            ref?.child("members")?.removeEventListener(it)
        }

        peers.keys.toList().forEach { peerUid ->
            channels.remove(peerUid)?.dispose()

            peers.remove(peerUid)?.let { peer ->
                peer.close()
                peer.dispose()
            }
        }

        channels.clear()
        incomingMaps.clear()
        peerListeners.clear()

        sessionCode = null
        roomRef = null
        membersListener = null
    }

    fun shutdown() {
        leaveSession()
        factory?.dispose()
        factory = null
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