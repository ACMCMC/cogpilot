package fyi.acmc.cogpilot

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * SnowflakeManager: Direct connection to Snowflake from Android.
 * Credentials from BuildConfig (environment variables).
 */
class SnowflakeManager {

    private val sqlApi = SnowflakeSqlApiClient()

    suspend fun initConnection(): Boolean = withContext(Dispatchers.IO) {
        val res = sqlApi.execute("SELECT 1")
        val ok = res.optString("code", "") == "0000" || res.has("data")
        if (ok) {
            Log.i("SnowflakeManager", "✓ Connected to Snowflake")
            initSchema()
        } else {
            Log.e("SnowflakeManager", "✗ Connection failed: ${res.optString("message", "unknown")}")
        }
        ok
    }

    private fun initSchema() {
        sqlApi.execute("""
            CREATE TABLE IF NOT EXISTS DRIVER_LOGS (
                id INTEGER AUTOINCREMENT,
                timestamp BIGINT NOT NULL,
                speed FLOAT NOT NULL,
                heading FLOAT NOT NULL,
                latitude FLOAT NOT NULL,
                longitude FLOAT NOT NULL,
                user_text VARCHAR,
                sentiment_score FLOAT,
                risk_score FLOAT,
                created_at TIMESTAMP_NTZ DEFAULT CURRENT_TIMESTAMP(),
                PRIMARY KEY (id)
            )
        """.trimIndent())

        sqlApi.execute("""
            CREATE TABLE IF NOT EXISTS USER_PROFILE (
                driver_id INTEGER PRIMARY KEY,
                interest_topics VARCHAR,
                complexity_level VARCHAR,
                created_at TIMESTAMP_NTZ DEFAULT CURRENT_TIMESTAMP()
            )
        """.trimIndent())

        sqlApi.execute("""
            CREATE TABLE IF NOT EXISTS COGNITIVE_STIMULI (
                id INTEGER AUTOINCREMENT,
                stimulus_type VARCHAR,
                prompt VARCHAR,
                response TEXT,
                generated_at TIMESTAMP_NTZ DEFAULT CURRENT_TIMESTAMP(),
                PRIMARY KEY (id)
            )
        """.trimIndent())

        sqlApi.execute("""
            INSERT INTO USER_PROFILE (driver_id, interest_topics, complexity_level)
            SELECT 1, 'Quantum Physics,90s Rock,Philosophy', 'advanced'
            WHERE NOT EXISTS (SELECT 1 FROM USER_PROFILE WHERE driver_id = 1)
        """.trimIndent())

        Log.i("SnowflakeManager", "✓ Schema initialized")
    }

    suspend fun insertTelemetry(timestamp: Long, speed: Float, heading: Float, lat: Double, lon: Double) {
        withContext(Dispatchers.IO) {
            val sql = """
                INSERT INTO DRIVER_LOGS (timestamp, speed, heading, latitude, longitude)
                VALUES ($timestamp, $speed, $heading, $lat, $lon)
            """.trimIndent()
            sqlApi.execute(sql)
            Log.d("SnowflakeManager", "Telemetry inserted: speed=$speed, heading=$heading")
        }
    }

    suspend fun getLastNLogs(n: Int = 24): List<FloatArray> = withContext(Dispatchers.IO) {
        return@withContext sqlApi.querySpeedHeading(n)
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
            INSERT INTO COGNITIVE_STIMULI (stimulus_type, prompt, response)
            VALUES ('debate', '${prompt.replace("'", "''").take(200)}', '${response.replace("'", "''")}')
        """.trimIndent()
        sqlApi.execute(insert)

        response
    }

    fun close() {
        Log.i("SnowflakeManager", "Session closed")
    }
}
