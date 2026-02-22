package fyi.acmc.cogpilot

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * SnowflakeManager: Direct connection to Snowflake from Android.
 * Credentials from BuildConfig (environment variables).
 */
class SnowflakeManager(
    internal val sqlApi: SnowflakeSqlApiClient = SnowflakeSqlApiClient()
) {

    suspend fun initConnection(): Boolean = withContext(Dispatchers.IO) {
        Log.i("SnowflakeManager", "Initializing Snowflake connection...")
        Log.d("SnowflakeManager", "BuildConfig: DB=${BuildConfig.SNOWFLAKE_DATABASE}, Schema=${BuildConfig.SNOWFLAKE_SCHEMA}, Account=${BuildConfig.SNOWFLAKE_ACCOUNT}")
        val res = sqlApi.execute("SELECT 1")
        val ok = res.optString("code", "") == "0000" || res.has("data")
        if (ok) {
            Log.i("SnowflakeManager", "✓ Connected to Snowflake")
        } else {
            Log.e("SnowflakeManager", "✗ Connection failed: ${res.optString("message", "unknown")}")
        }
        ok
    }

    suspend fun insertTelemetry(
        timestamp: Long,
        speed: Float,
        heading: Float,
        lat: Double,
        lon: Double,
        roadPlaceId: String? = null,
        roadTypes: String? = null,
        roadType: String? = null,
        speedLimit: Float? = null,
        speedUnit: String? = null,
        trafficRatio: Float? = null,
        speedOverLimit: Float? = null
    ) {
        withContext(Dispatchers.IO) {
            // tiny helper for safe SQL strings, keep it sloppy but valid
            fun sqlStr(value: String?): String = value?.replace("'", "''")?.let { "'$it'" } ?: "NULL"
            fun sqlNum(value: Float?): String = value?.let { it.toString() } ?: "NULL"

            val sql = """
                INSERT INTO COGPILOT.PUBLIC.TELEMETRY (
                    user_id, trip_id, timestamp_ms, speed_mph, heading_degrees, latitude, longitude
                )
                VALUES (
                    'driver', 'trip_1', $timestamp, $speed, $heading, $lat, $lon
                )
            """.trimIndent()
            sqlApi.execute(sql)
            Log.d("SnowflakeManager", "Telemetry inserted: speed=$speed, heading=$heading")
        }
    }

    suspend fun getLastNLogs(n: Int = 24): List<FloatArray> = withContext(Dispatchers.IO) {
        val sql = "SELECT speed_mph, heading_degrees FROM TELEMETRY ORDER BY timestamp_ms DESC LIMIT $n"
        val result = sqlApi.execute(sql)
        val data = result.optJSONArray("data") ?: return@withContext emptyList()
        val out = mutableListOf<FloatArray>()
        for (i in 0 until data.length()) {
            val row = data.optJSONArray(i) ?: continue
            val speed = row.optDouble(0, 0.0).toFloat()
            val heading = row.optDouble(1, 0.0).toFloat()
            out.add(floatArrayOf(speed, heading))
        }
        return@withContext out.reversed()
    }

    suspend fun getSentiment(text: String): Float = withContext(Dispatchers.IO) {
        val sql = "SELECT SNOWFLAKE.CORTEX.SENTIMENT('$text') as sent"
        val result = sqlApi.execute(sql)
        val data = result.optJSONArray("data")
        val sentiment = data?.optJSONArray(0)?.optDouble(0, 0.5)?.toFloat() ?: 0.5f
        Log.i("SnowflakeManager", "Sentiment: $sentiment for text: ${text.take(50)}")
        sentiment
    }

    suspend fun generateStimulus(): String = withContext(Dispatchers.IO) {
        val interests = "Quantum Physics, 90s Rock, Philosophy"
        val prompt = """You are a cognitive stimulation specialist. The driver is becoming drowsy.
User Interests: $interests
Generate a SHORT, PROVOCATIVE question under 20 words."""

        val sql = """
            SELECT SNOWFLAKE.CORTEX.COMPLETE(
                'snowflake-arctic',
                '[{"role": "user", "content": "$prompt"}]'
            ) as response
        """.trimIndent()

        val result = sqlApi.execute(sql)
        val data = result.optJSONArray("data")
        val response = data?.optJSONArray(0)?.optString(0, "Stay focused!") ?: "Stay focused!"

        Log.i("SnowflakeManager", "✓ Arctic generated: ${response.take(80)}")

        val insert = """
            INSERT INTO COGPILOT.PUBLIC.INTERACTIONS (user_id, interaction_type, content, response)
            VALUES ('driver', 'stimulus', '${prompt.replace("'", "''").take(200)}', '${response.replace("'", "''")}')
        """.trimIndent()
        sqlApi.execute(insert)

        response
    }

    suspend fun generateConversationTopics(driverId: Int = 1): String = withContext(Dispatchers.IO) {
        val interests = getInterestTopics(driverId)
        val prompt = """You are a friendly driving copilot. Brainstorm 5 short conversation topics
for a driver based on their interests. Keep each topic under 8 words.
Interests: $interests
Return as a numbered list.""".trimIndent()

        val sql = """
            SELECT SNOWFLAKE.CORTEX.COMPLETE(
                'snowflake-arctic',
                '[{"role": "user", "content": "${prompt.replace("\"", "\\\"").replace("\n", " ")}"}]'
            ) as response
        """.trimIndent()

        val result = sqlApi.execute(sql)
        val data = result.optJSONArray("data")
        val response = data?.optJSONArray(0)?.optString(0, "") ?: ""

        val insert = """
            INSERT INTO COGPILOT.PUBLIC.INTERACTIONS (user_id, interaction_type, content, response)
            VALUES ('driver', 'topic', '${prompt.replace("'", "''").take(250)}', '${response.replace("'", "''")}')
        """.trimIndent()
        sqlApi.execute(insert)

        response
    }

    suspend fun insertCalendarEvents(driverId: Int, events: List<CalendarEvent>) = withContext(Dispatchers.IO) {
        // Calendar events table not in schema; skip insert for now
        Log.d("SnowflakeManager", "Calendar events: ${events.size} events (not inserted to DB)")
    }

    private fun getInterestTopics(driverId: Int): String {
        val sql = "SELECT profile FROM USERS LIMIT 1"
        val result = sqlApi.execute(sql)
        val data = result.optJSONArray("data")
        val profileJson = data?.optJSONArray(0)?.optString(0, "{}")
        return try {
            val profile = JSONObject(profileJson)
            profile.optString("interests", "Cognitive Science, Music, Travel")
        } catch (e: Exception) {
            "Cognitive Science, Music, Travel"
        }
    }

    suspend fun getUserProfileById(userId: String): Map<String, String> = withContext(Dispatchers.IO) {
        val sql = "SELECT profile FROM USERS WHERE user_id = '$userId'"
        Log.d("SnowflakeManager", "getUserProfileById query: $sql")
        val result = sqlApi.execute(sql)
        Log.d("SnowflakeManager", "getUserProfileById response: ${result.toString().take(200)}")
        
        // check for table not found error
        val errorCode = result.optString("code", "")
        if (errorCode.contains("002003") || result.optString("message", "").contains("does not exist")) {
            Log.e("SnowflakeManager", "USERS table does not exist. Run populate_snowflake.py first.")
            return@withContext mapOf(
                "interests" to "Cognitive Science, Music, Travel",
                "complexity" to "intermediate"
            )
        }
        
        val data = result.optJSONArray("data")
        if (data == null || data.length() == 0) {
            Log.w("SnowflakeManager", "No profile found for user: $userId")
            return@withContext mapOf(
                "interests" to "Cognitive Science, Music, Travel",
                "complexity" to "intermediate"
            )
        }
        val profileJson = data.optJSONArray(0)?.optString(0, "") ?: ""
        val profile = try {
            JSONObject(profileJson)
        } catch (e: Exception) {
            Log.e("SnowflakeManager", "Failed to parse profile JSON: $profileJson")
            return@withContext mapOf(
                "interests" to "Cognitive Science, Music, Travel",
                "complexity" to "intermediate"
            )
        }
        val interests = profile.optString("interests", "Cognitive Science, Music, Travel")
        val complexity = profile.optString("complexity", "intermediate")
        Log.d("SnowflakeManager", "Profile found: interests=$interests, complexity=$complexity")
        return@withContext mapOf("interests" to interests, "complexity" to complexity)
    }

    suspend fun getDriverNum(userId: String): Int = withContext(Dispatchers.IO) {
        // for now just return 1; the actual ID mapping is handled by user_id in USERS table
        Log.d("SnowflakeManager", "getDriverNum for $userId = 1 (USERS table uses user_id directly)")
        return@withContext 1
    }

    suspend fun generateStartMessage(driverId: Int): String = withContext(Dispatchers.IO) {
        // simple greeting using their profile info - for now use static user based on caller context
        val profile = mapOf(
            "interests" to "Cognitive Science, Music, Travel",
            "complexity" to "intermediate"
        )
        val interests = profile["interests"] ?: ""
        Log.d("SnowflakeManager", "generateStartMessage: interests=$interests")
        val prompt = "You are an attentive driving copilot. Create a friendly opening message for a driver whose interests are: $interests. Keep it under 20 words."
        val sql = """
            SELECT SNOWFLAKE.CORTEX.COMPLETE(
                'snowflake-arctic',
                '[{"role": "user", "content": "${prompt.replace("\"", "\\\"")}"}]'
            ) as response
        """.trimIndent()
        val result = sqlApi.execute(sql)
        val data = result.optJSONArray("data")
        val message = data?.optJSONArray(0)?.optString(0, "Hello!") ?: "Hello!"
        message
    }

    fun close() {
        Log.i("SnowflakeManager", "Session closed")
    }
}
