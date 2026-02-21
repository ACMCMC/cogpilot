package fyi.acmc.cogpilot

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * ElevenLabsConversationManager: Official ElevenLabs API wrapper
 * Uses REST API for token exchange and manages conversation state
 */
class ElevenLabsConversationManager(private val context: Context) {

    companion object {
        private const val TAG = "ElevenLabsConversation"
        private const val API_BASE = "https://api.elevenlabs.io"
    }

    private val apiKey = BuildConfig.ELEVENLABS_API_KEY
    private val agentId = BuildConfig.ELEVENLABS_AGENT_ID
    
    private var conversationId: String? = null
    private var isActive = false
    private var conversationToken: String? = null

    var onConnect: ((String) -> Unit)? = null
    var onMessage: ((String, String) -> Unit)? = null
    var onStatusChange: ((String) -> Unit)? = null
    var onModeChange: ((String) -> Unit)? = null
    var onVadScore: ((Float) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /**
     * Start conversation with agent
     * For public agents: pass agentId
     * For private agents: pass token (obtained from backend)
     */
    suspend fun startConversation(
        agentId: String,
        userId: String = "driver",
        token: String? = null
    ): Boolean = suspendCancellableCoroutine { continuation ->
        try {
            // validate credentials
            if (apiKey.isBlank()) {
                throw Exception("ELEVENLABS_API_KEY not configured in BuildConfig")
            }
            if (agentId.isBlank()) {
                throw Exception("ELEVENLABS_AGENT_ID not configured in BuildConfig")
            }

            Log.i(TAG, "Starting conversation with agent: ${agentId.take(10)}...")

            if (token == null) {
                // fetch new token for public agent
                try {
                    fetchConversationToken(agentId, userId)
                } catch (e: Exception) {
                    Log.e(TAG, "Token fetch error: ${e.message}", e)
                    throw Exception("Failed to fetch token: ${e.message}", e)
                }
            } else {
                conversationToken = token
            }

            // conversation token acts as session identifier
            if (!conversationToken.isNullOrBlank()) {
                isActive = true
                conversationId = generateConversationId()
                Log.i(TAG, "✓ Conversation ready: $conversationId")
                onStatusChange?.invoke("connected")
                onConnect?.invoke(conversationId!!)
                continuation.resume(true)
            } else {
                throw Exception("Conversation token is empty or null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start: ${e.message}", e)
            onError?.invoke("Start failed: ${e.localizedMessage ?: e.message ?: e.toString()}")
            continuation.resume(false)
        }
    }

    private fun fetchConversationToken(agentId: String, userId: String) {
        try {
            val participant = URLEncoder.encode(userId, "UTF-8")
            val urlStr = "$API_BASE/v1/convai/conversation/token?agent_id=$agentId&participant_name=$participant"
            Log.d(TAG, "Fetching token from: $urlStr")
            
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("xi-api-key", apiKey)
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            Log.d(TAG, "Token request response code: $responseCode")
            
            if (responseCode != 200) {
                val errorStream = connection.errorStream
                val errorMsg = if (errorStream != null) {
                    BufferedReader(InputStreamReader(errorStream)).use { it.readText() }
                } else {
                    "HTTP $responseCode"
                }
                throw Exception("Token request failed: $errorMsg")
            }

            val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            Log.d(TAG, "Token response: ${response.take(100)}...")
            
            val json = JSONObject(response)
            conversationToken = json.optString("token", "")
            
            if (conversationToken.isNullOrBlank()) {
                throw Exception("Token missing in response: $response")
            }

            Log.i(TAG, "✓ Token received: ${conversationToken!!.take(20)}...")
        } catch (e: Exception) {
            throw Exception("Token fetch failed: ${e.message}", e)
        }
    }

    /**
     * Generate unique conversation ID for tracking
     */
    private fun generateConversationId(): String {
        return "conv_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
    }

    fun stopConversation() {
        try {
            isActive = false
            conversationToken = null
            conversationId = null
            Log.i(TAG, "Conversation stopped")
            onStatusChange?.invoke("disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Stop error: ${e.message}")
        }
    }

    fun getStatus(): String {
        return if (isActive) "connected" else "disconnected"
    }

    fun isConnected(): Boolean = isActive

    fun getConversationId(): String? = conversationId

    fun getToken(): String? = conversationToken

    /**
     * Send user text message in conversation
     */
    fun sendUserMessage(text: String) {
        try {
            if (!isConnected()) {
                Log.w(TAG, "Not connected, cannot send message")
                return
            }
            Log.d(TAG, "User message: $text")
            onMessage?.invoke("user", text)
        } catch (e: Exception) {
            Log.e(TAG, "Send message error: ${e.message}")
        }
    }

    fun simulateModeChange(mode: String) {
        onModeChange?.invoke(mode)
    }

    fun simulateVadScore(score: Float) {
        onVadScore?.invoke(score.coerceIn(0f, 1f))
    }

    fun dispose() {
        stopConversation()
    }
}
