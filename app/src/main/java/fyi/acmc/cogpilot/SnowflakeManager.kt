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
                road_place_id VARCHAR,
                road_types VARCHAR,
                road_type VARCHAR,
                speed_limit FLOAT,
                speed_unit VARCHAR,
                traffic_ratio FLOAT,
                speed_over_limit FLOAT,
                user_text VARCHAR,
                sentiment_score FLOAT,
                risk_score FLOAT,
                created_at TIMESTAMP_NTZ DEFAULT CURRENT_TIMESTAMP(),
                PRIMARY KEY (id)
            )
        """.trimIndent())

        // add new columns if table already existed
        sqlApi.execute("ALTER TABLE DRIVER_LOGS ADD COLUMN IF NOT EXISTS road_place_id VARCHAR")
        sqlApi.execute("ALTER TABLE DRIVER_LOGS ADD COLUMN IF NOT EXISTS road_types VARCHAR")
        sqlApi.execute("ALTER TABLE DRIVER_LOGS ADD COLUMN IF NOT EXISTS road_type VARCHAR")
        sqlApi.execute("ALTER TABLE DRIVER_LOGS ADD COLUMN IF NOT EXISTS speed_limit FLOAT")
        sqlApi.execute("ALTER TABLE DRIVER_LOGS ADD COLUMN IF NOT EXISTS speed_unit VARCHAR")
        sqlApi.execute("ALTER TABLE DRIVER_LOGS ADD COLUMN IF NOT EXISTS traffic_ratio FLOAT")
        sqlApi.execute("ALTER TABLE DRIVER_LOGS ADD COLUMN IF NOT EXISTS speed_over_limit FLOAT")

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
            CREATE TABLE IF NOT EXISTS CONVERSATION_TOPICS (
                id INTEGER AUTOINCREMENT,
                driver_id INTEGER,
                interest_topics VARCHAR,
                prompt VARCHAR,
                response TEXT,
                generated_at TIMESTAMP_NTZ DEFAULT CURRENT_TIMESTAMP(),
                PRIMARY KEY (id)
            )
        """.trimIndent())

        sqlApi.execute("""
            CREATE TABLE IF NOT EXISTS CALENDAR_EVENTS (
                id INTEGER AUTOINCREMENT,
                driver_id INTEGER,
                title VARCHAR,
                start_ms BIGINT,
                end_ms BIGINT,
                location VARCHAR,
                created_at TIMESTAMP_NTZ DEFAULT CURRENT_TIMESTAMP(),
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
                INSERT INTO DRIVER_LOGS (
                    timestamp, speed, heading, latitude, longitude,
                    road_place_id, road_types, road_type,
                    speed_limit, speed_unit, traffic_ratio, speed_over_limit
                )
                VALUES (
                    $timestamp, $speed, $heading, $lat, $lon,
                    ${sqlStr(roadPlaceId)}, ${sqlStr(roadTypes)}, ${sqlStr(roadType)},
                    ${sqlNum(speedLimit)}, ${sqlStr(speedUnit)}, ${sqlNum(trafficRatio)}, ${sqlNum(speedOverLimit)}
                )
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
            INSERT INTO CONVERSATION_TOPICS (driver_id, interest_topics, prompt, response)
            VALUES ($driverId, '${interests.replace("'", "''")}', '${prompt.replace("'", "''").take(250)}', '${response.replace("'", "''")}')
        """.trimIndent()
        sqlApi.execute(insert)

        response
    }

    suspend fun insertCalendarEvents(driverId: Int, events: List<CalendarEvent>) = withContext(Dispatchers.IO) {
        if (events.isEmpty()) return@withContext
        val values = events.joinToString(",") { ev ->
            val title = ev.title.replace("'", "''")
            val loc = ev.location?.replace("'", "''")
            "($driverId, '$title', ${ev.startMs}, ${ev.endMs}, ${if (loc == null) "NULL" else "'$loc'"})"
        }
        val sql = """
            INSERT INTO CALENDAR_EVENTS (driver_id, title, start_ms, end_ms, location)
            VALUES $values
        """.trimIndent()
        sqlApi.execute(sql)
    }

    private fun getInterestTopics(driverId: Int): String {
        val sql = "SELECT interest_topics FROM USER_PROFILE WHERE driver_id = $driverId"
        val result = sqlApi.execute(sql)
        val data = result.optJSONArray("data")
        val interests = data?.optJSONArray(0)?.optString(0, "")
        return if (interests.isNullOrBlank()) "Cognitive Science, Music, Travel" else interests
    }

    fun close() {
        Log.i("SnowflakeManager", "Session closed")
    }
}
