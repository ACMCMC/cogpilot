package fyi.acmc.cogpilot

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * ElevenLabsWebRtcSession: Lightweight WebRTC connection manager
 * Handles peer connection, audio streams, and session lifecycle with Stream WebRTC
 */
class ElevenLabsWebRtcSession(
    private val context: Context,
    private val token: String
) : PeerConnection.Observer {

    companion object {
        private const val TAG = "ElevenLabsWebRTC"
    }

    private var peerConnection: PeerConnection? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var isConnected = false

    private val pendingIceMutex = Mutex()
    private val pendingIceCandidates = mutableListOf<IceCandidate>()

    var onStateChange: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            // init WebRTC stack
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )

            // audio with echo cancellation
            val audioDeviceModule = JavaAudioDeviceModule.builder(context)
                .createAudioDeviceModule()

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioDeviceModule)
                .createPeerConnectionFactory()

            // create audio track
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            }
            audioSource = peerConnectionFactory!!.createAudioSource(audioConstraints)
            audioTrack = peerConnectionFactory!!.createAudioTrack("audio_track", audioSource)

            Log.i(TAG, "WebRTC initialized")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Init failed: ${e.message}")
            onError?.invoke("Init: ${e.message}")
            false
        }
    }

    suspend fun createPeerConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            peerConnectionFactory ?: throw Exception("Factory not initialized")

            val config = PeerConnection.RTCConfiguration(emptyList()).apply {
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            }

            peerConnection = peerConnectionFactory!!.createPeerConnection(config, this@ElevenLabsWebRtcSession)
                ?: throw Exception("Failed to create PC")

            // add audio track
            peerConnection!!.let { pc ->
                audioTrack?.let { pc.addTrack(it, listOf("stream0")) }
            }

            Log.i(TAG, "Peer connection created")
            onStateChange?.invoke("peer_created")
            true
        } catch (e: Exception) {
            Log.e(TAG, "PC creation failed: ${e.message}")
            onError?.invoke("PC: ${e.message}")
            false
        }
    }

    suspend fun createOffer(): String? = withContext(Dispatchers.IO) {
        try {
            peerConnection ?: throw Exception("PC not initialized")

            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }

            var result: String? = null
            var done = false

            peerConnection!!.let { pc ->
                pc.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(sd: SessionDescription) {
                        result = sd.description
                        pc.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(sd: SessionDescription) {}
                            override fun onCreateFailure(err: String) {
                                Log.e(TAG, "Set local failed: $err")
                                done = true
                            }
                            override fun onSetSuccess() { done = true }
                            override fun onSetFailure(err: String) { done = true }
                        }, sd)
                    }
                    override fun onCreateFailure(err: String) {
                        Log.e(TAG, "Create offer failed: $err")
                        done = true
                    }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(err: String) {}
                }, constraints)
            }

            var attempts = 0
            while (!done && attempts < 50) {
                Thread.sleep(100)
                attempts++
            }

            if (result != null) {
                Log.i(TAG, "Offer created")
                onStateChange?.invoke("offer_created")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Offer error: ${e.message}")
            onError?.invoke("Offer: ${e.message}")
            null
        }
    }

    suspend fun setRemoteAnswer(answerSdp: String): Boolean = withContext(Dispatchers.IO) {
        try {
            peerConnection ?: throw Exception("PC not initialized")

            var success = false
            var done = false

            peerConnection!!.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(sd: SessionDescription) {}
                override fun onCreateFailure(err: String) {}
                override fun onSetSuccess() {
                    success = true
                    done = true
                    Log.i(TAG, "Remote answer set")
                    onStateChange?.invoke("answer_set")
                }
                override fun onSetFailure(err: String) {
                    Log.e(TAG, "Set remote failed: $err")
                    done = true
                }
            }, SessionDescription(SessionDescription.Type.ANSWER, answerSdp))

            var attempts = 0
            while (!done && attempts < 50) {
                Thread.sleep(100)
                attempts++
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Remote answer error: ${e.message}")
            onError?.invoke("Remote: ${e.message}")
            false
        }
    }

    suspend fun addIceCandidate(candidate: IceCandidate): Boolean = withContext(Dispatchers.IO) {
        try {
            val pc = peerConnection
            if (pc == null) {
                // queue for later if PC not ready
                pendingIceMutex.withLock {
                    pendingIceCandidates.add(candidate)
                }
                return@withContext true
            }

            pc.addIceCandidate(candidate)
            true
        } catch (e: Exception) {
            Log.e(TAG, "ICE error: ${e.message}")
            false
        }
    }

    suspend fun flushPendingCandidates() = withContext(Dispatchers.IO) {
        try {
            pendingIceMutex.withLock {
                pendingIceCandidates.forEach {
                    peerConnection?.addIceCandidate(it)
                }
                pendingIceCandidates.clear()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Flush candidates error: ${e.message}")
        }
    }

    fun close() {
        try {
            audioTrack?.dispose()
            audioSource?.dispose()
            peerConnection?.close()
            peerConnection = null
            isConnected = false
            Log.i(TAG, "Session closed")
            onStateChange?.invoke("closed")
        } catch (e: Exception) {
            Log.e(TAG, "Close error: ${e.message}")
        }
    }

    fun isActive(): Boolean = peerConnection != null && isConnected

    // PeerConnection.Observer impl
    override fun onSignalingChange(state: PeerConnection.SignalingState) {
        Log.d(TAG, "Signaling: $state")
    }

    override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
        Log.i(TAG, "Connection: $state")
        isConnected = state == PeerConnection.PeerConnectionState.CONNECTED
        onStateChange?.invoke("conn_$state")
    }

    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
        Log.d(TAG, "ICE: $state")
    }

    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
        Log.d(TAG, "ICE gathering: $state")
    }

    override fun onIceCandidate(candidate: IceCandidate) {
        Log.d(TAG, "ICE candidate: ${candidate.sdpMLineIndex}")
    }

    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
    override fun onAddStream(stream: MediaStream) {}
    override fun onRemoveStream(stream: MediaStream) {}
    override fun onDataChannel(channel: DataChannel) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
    override fun onTrack(transceiver: RtpTransceiver) {}
    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
}
