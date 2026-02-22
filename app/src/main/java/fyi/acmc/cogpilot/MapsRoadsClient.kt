package fyi.acmc.cogpilot

import android.util.Log

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.round

/**
 * MapsRoadsClient: lightweight Google Maps Roads + Distance Matrix client
 * Caches per grid cell to keep costs low; no reverse geocode here
 */
class MapsRoadsClient(private val context: Context) {

    private val apiKey = BuildConfig.GOOGLE_MAPS_API_KEY
    private val http = OkHttpClient()

    private val cellCache = mutableMapOf<String, RoadContext>()
    private val placeTypeCache = mutableMapOf<String, PlaceTypeCache>()

    fun getRoadContext(lat: Double, lon: Double): RoadContext {
        val now = System.currentTimeMillis()
        val cellKey = gridKey(lat, lon)
        val cached = cellCache[cellKey]
        if (cached != null && now - cached.fetchedAtMs < 60_000) {
            return cached
        }

        val placeId = fetchNearestRoadPlaceId(lat, lon)
        val speedInfo = if (placeId != null) fetchSpeedLimit(placeId) else null
        val trafficRatio = fetchTrafficRatio(lat, lon)
        val types = if (placeId != null) fetchPlaceTypes(placeId, now) else emptyList()

        val ctx = RoadContext(
            placeId = placeId,
            types = types,
            speedLimitMph = speedInfo?.mph,
            speedUnit = speedInfo?.unit,
            trafficRatio = trafficRatio,
            fetchedAtMs = now
        )

        cellCache[cellKey] = ctx
        return ctx
    }

    private fun fetchNearestRoadPlaceId(lat: Double, lon: Double): String? {
        val url = "https://roads.googleapis.com/v1/nearestRoads?points=$lat,$lon&key=$apiKey"
        Log.d("MapsRoadsClient","nearestRoads request: $url")
        val body = http.newCall(Request.Builder().url(url).build()).execute().body?.string() ?: return null
        Log.d("MapsRoadsClient","nearestRoads response: ${body.take(200)}")
        val json = JSONObject(body)
        val points = json.optJSONArray("snappedPoints") ?: return null
        val point0 = points.optJSONObject(0) ?: return null
        val pid = point0.optString("placeId", "").takeIf { it.isNotEmpty() }
        Log.d("MapsRoadsClient","nearestRoads placeId= $pid")
        return pid
    }

    /**
     * The Roads API speedLimits endpoint requires a snapped placeId, NOT raw lat/lon.
     * Correct URL: /v1/speedLimits?placeId=<placeId>&key=<key>
     */
    private fun fetchSpeedLimit(placeId: String): SpeedLimitInfo? {
        val url = "https://roads.googleapis.com/v1/speedLimits?placeId=$placeId&key=$apiKey"
        Log.d("MapsRoadsClient","speedLimits request: $url")
        val body = http.newCall(Request.Builder().url(url).build()).execute().body?.string() ?: return null
        Log.d("MapsRoadsClient","speedLimits response: ${body.take(200)}")
        val json = JSONObject(body)
        val speedLimits = json.optJSONArray("speedLimits") ?: return null
        val limit0 = speedLimits.optJSONObject(0) ?: return null
        val raw = limit0.optDouble("speedLimit", Double.NaN)
        val unit = limit0.optString("unit", "KPH")
        if (raw.isNaN()) return null
        val mph = if (unit == "KPH") raw * 0.621371 else raw
        return SpeedLimitInfo(mph = mph.toFloat(), unit = unit)
    }

    private fun fetchTrafficRatio(lat: Double, lon: Double): Float? {
        val dLat = 0.0045
        val dLon = 0.0045
        val destLat = lat + dLat
        val destLon = lon + dLon
        val url = "https://maps.googleapis.com/maps/api/distancematrix/json?origins=$lat,$lon&destinations=$destLat,$destLon&departure_time=now&traffic_model=best_guess&key=$apiKey"
        Log.d("MapsRoadsClient","distanceMatrix request: $url")
        val body = http.newCall(Request.Builder().url(url).build()).execute().body?.string() ?: return null
        Log.d("MapsRoadsClient","distanceMatrix response: ${body.take(200)}")
        val json = JSONObject(body)
        val rows = json.optJSONArray("rows") ?: return null
        val elements = rows.optJSONObject(0)?.optJSONArray("elements") ?: return null
        val el0 = elements.optJSONObject(0) ?: return null
        val dur = el0.optJSONObject("duration")?.optInt("value", 0) ?: 0
        val durTraffic = el0.optJSONObject("duration_in_traffic")?.optInt("value", 0) ?: 0
        if (dur <= 0 || durTraffic <= 0) return null
        return (durTraffic.toFloat() / dur.toFloat())
    }

    private fun fetchPlaceTypes(placeId: String, now: Long): List<String> {
        val cached = placeTypeCache[placeId]
        if (cached != null && now - cached.fetchedAtMs < 600_000) {
            return cached.types
        }

        val url = "https://maps.googleapis.com/maps/api/place/details/json?place_id=$placeId&fields=types&key=$apiKey"
        Log.d("MapsRoadsClient","placeDetails request: $url")
        val body = http.newCall(Request.Builder().url(url).build()).execute().body?.string() ?: return emptyList()
        Log.d("MapsRoadsClient","placeDetails response: ${body.take(200)}")
        val json = JSONObject(body)
        val result = json.optJSONObject("result") ?: return emptyList()
        val types = result.optJSONArray("types")
        val list = mutableListOf<String>()
        if (types != null) {
            for (i in 0 until types.length()) {
                list.add(types.optString(i))
            }
        }

        placeTypeCache[placeId] = PlaceTypeCache(list, now)
        return list
    }

    private fun gridKey(lat: Double, lon: Double): String {
        // coarse grid cell; cheap cacheing, ok for 15s loop
        val rLat = round(lat * 1000.0) / 1000.0
        val rLon = round(lon * 1000.0) / 1000.0
        return "$rLat,$rLon"
    }
}

// small structs for caches

data class RoadContext(
    val placeId: String?,
    val types: List<String>,
    val speedLimitMph: Float?,
    val speedUnit: String?,
    val trafficRatio: Float?,
    val fetchedAtMs: Long
)

data class SpeedLimitInfo(
    val mph: Float,
    val unit: String
)

data class PlaceTypeCache(
    val types: List<String>,
    val fetchedAtMs: Long
)
