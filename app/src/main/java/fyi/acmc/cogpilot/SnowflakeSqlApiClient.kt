package fyi.acmc.cogpilot

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class SnowflakeSqlApiClient {

    private val http = OkHttpClient()
    private val jsonMediaType = "application/json".toMediaType()

    private val host = BuildConfig.SNOWFLAKE_ACCOUNT
    private val warehouse = BuildConfig.SNOWFLAKE_WAREHOUSE
    private val database = BuildConfig.SNOWFLAKE_DATABASE
    private val schema = BuildConfig.SNOWFLAKE_SCHEMA
    private val role = BuildConfig.SNOWFLAKE_ROLE
    private val patToken = BuildConfig.SNOWFLAKE_PAT_TOKEN

    fun execute(statement: String): JSONObject {
        return try {
            val url = "https://$host/api/v2/statements"
            Log.d("SnowflakeSqlApi", "Connecting to: host=$host, db=$database, schema=$schema, warehouse=$warehouse")

            val body = JSONObject()
            body.put("statement", statement)
            body.put("warehouse", warehouse)
            body.put("database", database)
            body.put("schema", schema)
            body.put("role", role)

            Log.d("SnowflakeSqlApi", "Execute SQL: ${statement.take(80)}... with db=$database schema=$schema")

            val request = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody(jsonMediaType))
                .addHeader("Authorization", "Bearer $patToken")
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "CogPilot/0.1")
                .build()

            val response = http.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"
            Log.d("SnowflakeSqlApi", "execute(): status=${response.code}")
            JSONObject(responseBody)
        } catch (e: Exception) {
            Log.e("SnowflakeSqlApi", "execute() error: ${e.message}", e)
            val err = JSONObject()
            err.put("error", e.message ?: "unknown error")
            err
        }
    }

    fun querySpeedHeading(limit: Int): List<FloatArray> {
        val sql = "SELECT speed, heading FROM DRIVER_LOGS ORDER BY timestamp DESC LIMIT $limit"
        val result = execute(sql)
        val data = result.optJSONArray("data") ?: JSONArray()

        val out = mutableListOf<FloatArray>()
        for (i in 0 until data.length()) {
            val row = data.getJSONArray(i)
            val speed = row.optDouble(0, 0.0).toFloat()
            val heading = row.optDouble(1, 0.0).toFloat()
            out.add(floatArrayOf(speed, heading))
        }

        return out.reversed()
    }


}
