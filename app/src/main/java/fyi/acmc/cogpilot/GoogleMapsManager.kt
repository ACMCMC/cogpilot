package fyi.acmc.cogpilot

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manager for interacting with Google Maps Places API (New).
 * Provides real-time search for nearby rest areas, cafes, etc.
 */
class GoogleMapsManager(private val apiKey: String) {
    private val client = OkHttpClient()
    private val TAG = "GoogleMapsManager"

    /**
     * Searches for places based on a text query, biasing results to the driver's current location.
     */
    suspend fun searchPlaces(query: String, lat: Double, lng: Double): JSONArray = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔍 Real Places Search: '$query' at ($lat, $lng)")
        
        val url = "https://places.googleapis.com/v1/places:searchText"
        
        // Construct request body for Places API (New)
        val jsonBody = JSONObject().apply {
            put("textQuery", query)
            put("locationBias", JSONObject().apply {
                put("circle", JSONObject().apply {
                    put("center", JSONObject().apply {
                        put("latitude", lat)
                        put("longitude", lng)
                    })
                    put("radius", 10000.0) // 10km bias for "nearby"
                })
            })
            put("maxResultCount", 5)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = jsonBody.toString().toRequestBody(mediaType)

        // Fields to include in response
        val fieldMask = "places.displayName,places.formattedAddress,places.rating,places.location"

        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("X-Goog-Api-Key", apiKey)
            .addHeader("X-Goog-FieldMask", fieldMask)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorMsg = response.body?.string() ?: response.message
                    Log.e(TAG, "❌ Places API error: ${response.code} $errorMsg")
                    return@withContext JSONArray()
                }

                val responseBody = response.body?.string() ?: return@withContext JSONArray()
                val jsonResponse = JSONObject(responseBody)
                val placesArr = jsonResponse.optJSONArray("places") ?: JSONArray()
                
                Log.i(TAG, "✅ Found ${placesArr.length()} results from Google Maps")
                
                val results = JSONArray()
                for (i in 0 until placesArr.length()) {
                    val place = placesArr.getJSONObject(i)
                    val result = JSONObject().apply {
                        put("name", place.optJSONObject("displayName")?.optString("text") ?: "Place ${i+1}")
                        put("address", place.optString("formattedAddress", "Unknown Address"))
                        put("rating", if (place.has("rating")) place.getDouble("rating") else 0.0)
                        
                        // Calculate distance
                        val destination = place.optJSONObject("location")
                        if (destination != null) {
                            val destLat = destination.getDouble("latitude")
                            val destLng = destination.getDouble("longitude")
                            val distanceResults = FloatArray(1)
                            Location.distanceBetween(lat, lng, destLat, destLng, distanceResults)
                            val miles = distanceResults[0] / 1609.34 // meters to miles
                            put("distance", String.format("%.1f miles", miles))
                        } else {
                            put("distance", "Unknown")
                        }
                    }
                    results.put(result)
                }
                results
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception in searchPlaces: ${e.message}", e)
            JSONArray()
        }
    }
}
