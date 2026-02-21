package fyi.acmc.cogpilot

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.webrtc.IceCandidate
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ElevenLabsManager(private val context: Context? = null) {

    private val apiKey = BuildConfig.ELEVENLABS_API_KEY
    private val agentId = BuildConfig.ELEVENLABS_AGENT_ID
    private val baseUrl = "https://api.elevenlabs.io"

    private var currentSession: ElevenLabsWebRtcSession? = null

    var onSessionStateChange: ((String) -> Unit)? = null
    var onSessionError: ((String) -> Unit)? = null

    suspend fun getWebrtcToken(participantName: String? = null): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || agentId.isBlank()) {
            Log.e("ElevenLabs", "Missing ELEVENLABS_API_KEY or ELEVENLABS_AGENT_ID")
            return@withContext null
        }

        try {
            val participant = participantName?.let {
                "&participant_name=" + URLEncoder.encode(it, "UTF-8")
            } ?: ""

            val url = URL(
                "$baseUrl/v1/convai/conversation/token?agent_id=$agentId$participant"
            )
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("xi-api-key", apiKey)
            connection.setRequestProperty("Accept", "application/json")

            val code = connection.responseCode
            if (code != 200) {
                Log.e("ElevenLabs", "Token request failed: $code")
                return@withContext null
            }

            val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            val json = JSONObject(response)
            val token = json.optString("token", "")

            if (token.isBlank()) {
                Log.e("ElevenLabs", "Token missing in response")
                return@withContext null
            }

            Log.i("ElevenLabs", "WebRTC token received")
            token
        } catch (e: Exception) {
            Log.e("ElevenLabs", "Token request error: ${e.message}")
            null
        }
    }

    suspend fun startSession(token: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (context == null) {
                Log.e("ElevenLabs", "Context required for WebRTC session")
                onSessionError?.invoke("Context not available")
                return@withContext false
            }

            if (currentSession != null) {
                Log.w("ElevenLabs", "Session already active, closing previous")
                stopSession()
            }

            val session = ElevenLabsWebRtcSession(context, token)
            session.onStateChange = { state -> onSessionStateChange?.invoke(state) }
            session.onError = { error -> onSessionError?.invoke(error) }

            // Init WebRTC
            if (!session.initialize()) {
                Log.e("ElevenLabs", "Failed to initialize WebRTC")
                onSessionError?.invoke("WebRTC init failed")
                return@withContext false
            }

            // Create peer conn
            if (!session.createPeerConnection()) {
                Log.e("ElevenLabs", "Failed to create peer connection")
                onSessionError?.invoke("Peer connection failed")
                return@withContext false
            }

            // Create and send offer
            val offer = session.createOffer()
            if (offer.isNullOrBlank()) {
                Log.e("ElevenLabs", "Failed to create SDP offer")
                onSessionError?.invoke("SDP offer failed")
                return@withContext false
            }

            Log.i("ElevenLabs", "Sending offer to agent...")
            val answer = sendOfferGetAnswer(offer)
            if (answer.isNullOrBlank()) {
                Log.e("ElevenLabs", "Failed to get answer from agent")
                onSessionError?.invoke("No answer from agent")
                session.close()
                return@withContext false
            }

            // Set remote answer
            if (!session.setRemoteAnswer(answer)) {
                Log.e("ElevenLabs", "Failed to set remote answer")
                onSessionError?.invoke("Remote answer failed")
                session.close()
                return@withContext false
            }

            currentSession = session
            Log.i("ElevenLabs", "✓ WebRTC session established")
            onSessionStateChange?.invoke("session_established")
            true
        } catch (e: Exception) {
            Log.e("ElevenLabs", "Session start error: ${e.message}")
            onSessionError?.invoke("Start error: ${e.message}")
            false
        }
    }

    suspend fun stopSession() = withContext(Dispatchers.IO) {
        try {
            currentSession?.close()
            currentSession = null
            Log.i("ElevenLabs", "WebRTC session stopped")
            onSessionStateChange?.invoke("session_stopped")
        } catch (e: Exception) {
            Log.e("ElevenLabs", "Stop session error: ${e.message}")
        }
    }

    fun isSessionActive(): Boolean = currentSession?.isActive() ?: false

    private suspend fun sendOfferGetAnswer(offerSdp: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/v1/convai/conversation")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("xi-api-key", apiKey)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            // simple json payload with agent_id and offer
            val payload = JSONObject().apply {
                put("agent_id", agentId)
                put("offer", offerSdp)
            }

            connection.outputStream.write(payload.toString().toByteArray())
            connection.outputStream.close()

            val code = connection.responseCode
            if (code != 200) {
                Log.e("ElevenLabs", "Offer/answer exchange failed: $code")
                return@withContext null
            }

            val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            val json = JSONObject(response)
            val answer = json.optString("answer", "")

            if (answer.isBlank()) {
                Log.e("ElevenLabs", "Answer missing in response")
                return@withContext null
            }

            Log.i("ElevenLabs", "Received answer from agent")
            answer
        } catch (e: Exception) {
            Log.e("ElevenLabs", "Offer/answer error: ${e.message}")
            null
        }
    }

    fun getSession(): ElevenLabsWebRtcSession? = currentSession
}
