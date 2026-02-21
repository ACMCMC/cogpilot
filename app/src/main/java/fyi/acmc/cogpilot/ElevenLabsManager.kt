package fyi.acmc.cogpilot

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ElevenLabsManager {

    private val apiKey = BuildConfig.ELEVENLABS_API_KEY
    private val agentId = BuildConfig.ELEVENLABS_AGENT_ID
    private val baseUrl = "https://api.elevenlabs.io"

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
}
