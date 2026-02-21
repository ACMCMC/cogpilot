package fyi.acmc.cogpilot

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.snowflake.client.jdbc.SnowflakeDriver
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/**
 * SnowflakeManager: Direct connection to Snowflake from Android.
 * Credentials from BuildConfig (environment variables).
 */
class SnowflakeManager {

    private var connection: Connection? = null
    
    private val connectionUrl: String
        get() {
            val account = BuildConfig.SNOWFLAKE_ACCOUNT
            val user = BuildConfig.SNOWFLAKE_USER
            val password = BuildConfig.SNOWFLAKE_PASSWORD
            val warehouse = BuildConfig.SNOWFLAKE_WAREHOUSE
            val db = BuildConfig.SNOWFLAKE_DATABASE
            val schema = BuildConfig.SNOWFLAKE_SCHEMA
            val role = BuildConfig.SNOWFLAKE_ROLE
            
            return "jdbc:snowflake://$account/?user=$user&password=$password&role=$role&warehouse=$warehouse&db=$db&schema=$schema"
        }

    suspend fun initConnection(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            DriverManager.registerDriver(SnowflakeDriver())
            connection = DriverManager.getConnection(connectionUrl)
            Log.i("SnowflakeManager", "✓ Connected to Snowflake")
            initSchema()
            true
        } catch (e: Exception) {
            Log.e("SnowflakeManager", "✗ Connection failed: ${e.message}")
            false
        }
    }

    private fun initSchema() {
        try {
            val stmt = connection!!.createStatement()
            
            stmt.executeUpdate("""
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
            """)
            
            stmt.executeUpdate("""
                INSERT INTO USER_PROFILE (driver_id, interest_topics, complexity_level)
                SELECT 1, 'Quantum Physics,90s Rock,Philosophy', 'advanced'
                WHERE NOT EXISTS (SELECT 1 FROM USER_PROFILE WHERE driver_id = 1)
            """)
            
            Log.i("SnowflakeManager", "✓ Schema initialized")
            stmt.close()
        } catch (e: Exception) {
            Log.d("SnowflakeManager", "Schema already exists or error: ${e.message}")
        }
    }

    suspend fun insertTelemetry(timestamp: Long, speed: Float, heading: Float, lat: Double, lon: Double) {
        withContext(Dispatchers.IO) {
            try {
                val stmt = connection!!.prepareStatement("""
                    INSERT INTO DRIVER_LOGS (timestamp, speed, heading, latitude, longitude)
                    VALUES (?, ?, ?, ?, ?)
                """)
                stmt.setLong(1, timestamp)
                stmt.setFloat(2, speed)
                stmt.setFloat(3, heading)
                stmt.setDouble(4, lat)
                stmt.setDouble(5, lon)
                stmt.executeUpdate()
                stmt.close()
                
                Log.d("SnowflakeManager", "Telemetry inserted: speed=$speed, heading=$heading")
            } catch (e: Exception) {
                Log.e("SnowflakeManager", "Insert failed: ${e.message}")
            }
        }
    }

    suspend fun getLastNLogs(n: Int = 24): List<FloatArray> = withContext(Dispatchers.IO) {
        return@withContext try {
            val stmt = connection!!.createStatement()
            val rs = stmt.executeQuery("""
                SELECT speed, heading FROM DRIVER_LOGS
                ORDER BY timestamp DESC
                LIMIT $n
            """)
            
            val logs = mutableListOf<FloatArray>()
            while (rs.next()) {
                logs.add(floatArrayOf(rs.getFloat(1), rs.getFloat(2)))
            }
            
            rs.close()
            stmt.close()
            logs.reversed()  // oldest to newest
        } catch (e: Exception) {
            Log.e("SnowflakeManager", "Query failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun getSentiment(text: String): Float = withContext(Dispatchers.IO) {
        return@withContext try {
            val stmt = connection!!.createStatement()
            val rs = stmt.executeQuery("""
                SELECT SNOWFLAKE.CORTEX.SENTIMENT('$text') as sent
            """)
            
            val sentiment = if (rs.next()) rs.getFloat(1) else 0.5f
            rs.close()
            stmt.close()
            
            Log.i("SnowflakeManager", "Sentiment: $sentiment for text: ${text.take(50)}")
            sentiment
        } catch (e: Exception) {
            Log.e("SnowflakeManager", "Cortex.Sentiment failed: ${e.message}")
            0.5f
        }
    }

    suspend fun generateStimulus(): String = withContext(Dispatchers.IO) {
        return@withContext try {
            val interests = "Quantum Physics, 90s Rock, Philosophy"
            val prompt = """You are a cognitive stimulation specialist. The driver is becoming drowsy.
User Interests: $interests
Generate a SHORT, PROVOCATIVE question under 20 words."""
            
            val stmt = connection!!.createStatement()
            val rs = stmt.executeQuery("""
                SELECT SNOWFLAKE.CORTEX.COMPLETE(
                    'snowflake-arctic',
                    '[{"role": "user", "content": "$prompt"}]'
                ) as response
            """)
            
            val response = if (rs.next()) rs.getString(1) else "Stay focused!"
            rs.close()
            stmt.close()
            
            Log.i("SnowflakeManager", "✓ Arctic generated: ${response.take(80)}")
            
            // Save to DB
            try {
                val insertStmt = connection!!.prepareStatement("""
                    INSERT INTO COGNITIVE_STIMULI (stimulus_type, prompt, response)
                    VALUES (?, ?, ?)
                """)
                insertStmt.setString(1, "debate")
                insertStmt.setString(2, prompt.take(200))
                insertStmt.setString(3, response)
                insertStmt.executeUpdate()
                insertStmt.close()
            } catch (e: Exception) {
                Log.d("SnowflakeManager", "Could not save stimulus: ${e.message}")
            }
            
            response
        } catch (e: Exception) {
            Log.e("SnowflakeManager", "Cortex.Complete failed: ${e.message}")
            "Can you tell me about your favorite topic?"
        }
    }

    fun close() {
        try {
            connection?.close()
            Log.i("SnowflakeManager", "Connection closed")
        } catch (e: Exception) {
            Log.e("SnowflakeManager", "Close failed: ${e.message}")
        }
    }
}
