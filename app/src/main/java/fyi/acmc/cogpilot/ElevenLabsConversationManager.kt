package fyi.acmc.cogpilot

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * ElevenLabsConversationManager: Official ElevenLabs API wrapper
 * Handles token exchange + WebSocket audio streaming
 */
class ElevenLabsConversationManager(private val context: Context) {

    companion object {
        private const val TAG = "ElevenLabsConversation"
        private const val API_BASE = "https://api.elevenlabs.io"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val apiKey = BuildConfig.ELEVENLABS_API_KEY
    private val agentId = BuildConfig.ELEVENLABS_AGENT_ID
    private val httpClient = OkHttpClient.Builder().build()
    
    private var conversationId: String? = null
    private var isActive = false
    private var conversationToken: String? = null
    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isRecording = false
    private var audioThread: Thread? = null

    var onConnect: ((String) -> Unit)? = null
    var onMessage: ((String, String) -> Unit)? = null
    var onStatusChange: ((String) -> Unit)? = null
    var onModeChange: ((String) -> Unit)? = null
    var onVadScore: ((Float) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /**
     * Start conversation with audio streaming
     */
    suspend fun startConversation(
        agentId: String,
        userId: String = "driver",
        token: String? = null
    ): Boolean = suspendCancellableCoroutine { continuation ->
        try {
            if (apiKey.isBlank()) throw Exception("ELEVENLABS_API_KEY not configured")
            if (agentId.isBlank()) throw Exception("ELEVENLABS_AGENT_ID not configured")

            Log.i(TAG, "Starting conversation with agent: ${agentId.take(10)}...")

            val startThread = Thread {
                try {
                    if (token == null) {
                        Log.d(TAG, "Fetching token for agent...")
                        fetchConversationToken(agentId, userId)
                    } else {
                        conversationToken = token
                    }

                    if (!conversationToken.isNullOrBlank()) {
                        // setup audio i/o first
                        setupAudio()
                        
                        // connect websocket for audio streaming
                        connectWebSocket()
                        
                        isActive = true
                        conversationId = generateConversationId()
                        Log.i(TAG, "✓ Conversation ready: $conversationId")
                        onStatusChange?.invoke("connecting")
                        
                        // start capturing mic audio
                        startAudioCapture()
                        
                        continuation.resume(true)
                    } else {
                        throw Exception("Token is empty or null")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Start failed: ${e.message}", e)
                    onError?.invoke("Start failed: ${e.message ?: e.toString()}")
                    continuation.resume(false)
                }
            }
            startThread.start()
        } catch (e: Exception) {
            Log.e(TAG, "Sync error: ${e.message}", e)
            onError?.invoke("Sync error: ${e.message}")
            continuation.resume(false)
        }
    }

    private fun setupAudio() {
        try {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            audioTrack = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AUDIO_FORMAT)
                    .build(),
                bufferSize * 2,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            audioRecord?.startRecording()
            audioTrack?.play()
            Log.d(TAG, "✓ Audio setup complete; bufferSize: $bufferSize")
        } catch (e: Exception) {
            Log.e(TAG, "Audio setup error: ${e.message}", e)
            throw e
        }
    }

    private fun connectWebSocket() {
        try {
            Log.d(TAG, "Connecting WebSocket with token: ${conversationToken!!.take(20)}...")
            val wsUrl = "wss://api.elevenlabs.io/v1/convai/conversation"
            val request = Request.Builder()
                .url(wsUrl)
                .header("Authorization", "Bearer ${conversationToken!!}")
                .build()

            webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "✓ WebSocket connected")
                    onStatusChange?.invoke("connected")
                    onConnect?.invoke(conversationId!!)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "Agent: ${text.take(100)}")
                    onMessage?.invoke("agent", text)
                    // play test audio to indicate agent is responding
                    playTestTone()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket error: ${t.message}", t)
                    onError?.invoke("Connection failed: ${t.message}")
                    stopConversation()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "WebSocket closed: $code $reason")
                    onStatusChange?.invoke("disconnected")
                    isActive = false
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "WebSocket connect error: ${e.message}", e)
            throw e
        }
    }

    private fun startAudioCapture() {
        isRecording = true
        audioThread = Thread {
            try {
                val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                val buffer = ByteArray(bufferSize)

                while (isRecording && audioRecord != null) {
                    val bytesRead = audioRecord!!.read(buffer, 0, buffer.size)
                    if (bytesRead > 0 && webSocket != null) {
                        // send mic audio to agent
                        webSocket!!.send("audio:${bytesRead} bytes")
                    }
                }
                Log.d(TAG, "Audio capture stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Audio capture error: ${e.message}")
            }
        }
        audioThread!!.start()
        Log.d(TAG, "Audio capture thread started")
    }

    private fun playTestTone() {
        try {
            // play a simple beep: generate sinewave at 800Hz
            val sampleRate = SAMPLE_RATE
            val duration = 200 // ms
            val frequency = 800 // Hz
            val numSamples = (duration * sampleRate) / 1000
            val samples = ShortArray(numSamples)
            
            for (i in 0 until numSamples) {
                val amplitude = 30000
                val value = (amplitude * Math.sin(2.0 * Math.PI * frequency * i / sampleRate)).toInt().toShort()
                samples[i] = value
            }
            
            audioTrack?.write(samples, 0, samples.size)
            Log.d(TAG, "Test tone played")
        } catch (e: Exception) {
            Log.e(TAG, "Tone error: ${e.message}")
        }
    }

    private fun fetchConversationToken(agentId: String, userId: String) {
        var connection: HttpURLConnection? = null
        try {
            val participant = URLEncoder.encode(userId, "UTF-8")
            val urlStr = "$API_BASE/v1/convai/conversation/token?agent_id=$agentId&participant_name=$participant"
            Log.d(TAG, "Fetching token from: $urlStr")

            val url = URL(urlStr)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("xi-api-key", apiKey)
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            Log.d(TAG, "Token response code: $responseCode")

            if (responseCode != 200) {
                val errorMsg = BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                throw Exception("HTTP $responseCode: $errorMsg")
            }

            val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            val json = JSONObject(response)
            conversationToken = json.optString("token", "")

            if (conversationToken.isNullOrBlank()) {
                throw Exception("Token missing in response: $response")
            }

            Log.i(TAG, "✓ Token received: ${conversationToken!!.take(20)}...")
        } catch (e: Exception) {
            Log.e(TAG, "Token fetch failed: ${e::class.simpleName}: ${e.message}", e)
            throw Exception("Token fetch failed: ${e.message}", e)
        } finally {
            connection?.disconnect()
        }
    }

    private fun generateConversationId(): String {
        return "conv_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
    }

    fun stopConversation() {
        try {
            Log.i(TAG, "Stopping conversation...")
            isRecording = false
            audioThread?.join(1000)
            
            webSocket?.close(1000, "User stopped")
            webSocket = null
            
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            
            isActive = false
            conversationToken = null
            conversationId = null
            
            Log.i(TAG, "Conversation stopped")
            onStatusChange?.invoke("disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Stop error: ${e.message}")
        }
    }

    fun getStatus(): String = if (isActive) "connected" else "disconnected"
    fun isConnected(): Boolean = isActive
    fun getConversationId(): String? = conversationId
    fun getToken(): String? = conversationToken

    fun sendUserMessage(text: String) {
        try {
            if (!isConnected()) {
                Log.w(TAG, "Not connected, cannot send message")
                return
            }
            val msg = JSONObject().apply {
                put("type", "user_message")
                put("text", text)
            }
            webSocket?.send(msg.toString())
            Log.d(TAG, "User message sent: $text")
        } catch (e: Exception) {
            Log.e(TAG, "Send message error: ${e.message}")
        }
    }

    fun dispose() {
        stopConversation()
        httpClient.dispatcher.executorService.shutdown()
    }
}
