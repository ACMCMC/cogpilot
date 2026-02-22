package fyi.acmc.cogpilot

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.PermissionChecker
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * LocationCapture: Handles GPS telemetry collection from the device.
 * Captures speed, heading, latitude, longitude every 5 seconds.
 * Inserts directly into Snowflake via SnowflakeManager.
 */
class LocationCapture(private val context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var lastLocation: Location? = null
    private var locationListener: LocationListener? = null
    private var captureCallback: ((speed: Float, heading: Float) -> Unit)? = null
    private var debugCallback: ((speed: Float, heading: Float, roadCtx: RoadContext, lat: Double, lon: Double) -> Unit)? = null
    private var snowflakeManager: SnowflakeManager? = null
    private val mapsClient = MapsRoadsClient(context)

    fun startCapture(
        snowflakeManager: SnowflakeManager,
        callback: (speed: Float, heading: Float) -> Unit,
        debug: ((speed: Float, heading: Float, roadCtx: RoadContext, lat: Double, lon: Double) -> Unit)? = null
    ) {
        this.snowflakeManager = snowflakeManager
        captureCallback = callback
        debugCallback = debug

        // Check for location permissions
        if (PermissionChecker.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PermissionChecker.PERMISSION_GRANTED &&
            PermissionChecker.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PermissionChecker.PERMISSION_GRANTED
        ) {
            android.util.Log.e("LocationCapture", "Location permissions not granted")
            return
        }

        android.util.Log.i("LocationCapture", "✓ Permissions granted, starting GPS listener")

        // check available providers
        val allProviders = locationManager.allProviders
        android.util.Log.i("LocationCapture", "Available location providers: $allProviders")
        for (provider in allProviders) {
            val enabled = locationManager.isProviderEnabled(provider)
            android.util.Log.d("LocationCapture", "  - $provider: enabled=$enabled")
        }

        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                try {
                    android.util.Log.d("LocationCapture", "✓ Location update: ${location.latitude}, ${location.longitude}")
                    val speed = location.speed * 2.237f  // Convert m/s to mph
                    val heading = calculateHeading(lastLocation, location)
                    lastLocation = location

                    // Insert into Snowflake directly
                    snowflakeManager?.let {
                        (context as? androidx.appcompat.app.AppCompatActivity)?.lifecycleScope?.launch {
                            try {
                                android.util.Log.d("LocationCapture", "Fetching road context for ${location.latitude}, ${location.longitude}")
                                val roadCtx = withContext(Dispatchers.IO) {
                                    mapsClient.getRoadContext(location.latitude, location.longitude)
                                }
                                android.util.Log.d("LocationCapture", "Road context: placeId=${roadCtx.placeId}, types=${roadCtx.types}")
                                val roadType = roadCtx.types.firstOrNull()
                                val roadTypesStr = if (roadCtx.types.isNotEmpty()) roadCtx.types.joinToString(",") else null
                                val speedOver = roadCtx.speedLimitMph?.let { limit -> speed - limit }

                                it.insertTelemetry(
                                    timestamp = System.currentTimeMillis(),
                                    speed = speed,
                                    heading = heading,
                                    lat = location.latitude,
                                    lon = location.longitude,
                                    roadPlaceId = roadCtx.placeId,
                                    roadTypes = roadTypesStr,
                                    roadType = roadType,
                                    speedLimit = roadCtx.speedLimitMph,
                                    speedUnit = roadCtx.speedUnit,
                                    trafficRatio = roadCtx.trafficRatio,
                                    speedOverLimit = speedOver
                                )

                                debugCallback?.invoke(speed, heading, roadCtx, location.latitude, location.longitude)
                            } catch (e: Exception) {
                                android.util.Log.e("LocationCapture", "Error in location processing: ${e.message}", e)
                            }
                        }
                    }

                    // Update UI callback
                    captureCallback?.invoke(speed, heading)
                } catch (e: Exception) {
                    android.util.Log.e("LocationCapture", "Exception in onLocationChanged: ${e.message}", e)
                }
            }

            override fun onProviderEnabled(provider: String) {
                android.util.Log.d("LocationCapture", "Provider enabled: $provider")
            }
            override fun onProviderDisabled(provider: String) {
                android.util.Log.w("LocationCapture", "Provider disabled: $provider")
            }

            override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {}
        }

        // Request location updates every 2 seconds (2000 ms) or 10 meters
        try {
            // Try GPS first, then fall back to NETWORK
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            var attached = false
            
            for (provider in providers) {
                try {
                    if (locationManager.isProviderEnabled(provider)) {
                        locationManager.requestLocationUpdates(
                            provider,
                            2000L,
                            10f,
                            locationListener!!,
                            Looper.getMainLooper()
                        )
                        android.util.Log.i("LocationCapture", "✓ Location updates requested on $provider")
                        attached = true
                    }
                } catch (e: Exception) {
                    android.util.Log.w("LocationCapture", "Could not attach to $provider: ${e.message}")
                }
            }
            
            if (!attached) {
                android.util.Log.e("LocationCapture", "✗ No location providers could be attached!")
            }
        } catch (e: Exception) {
            android.util.Log.e("LocationCapture", "Error requesting location updates: ${e.message}", e)
        }
    }

    fun stopCapture() {
        locationListener?.let {
            try {
                locationManager.removeUpdates(it)
            } catch (e: Exception) {
                android.util.Log.e("LocationCapture", "Error stopping location updates", e)
            }
        }
    }

    private fun calculateHeading(from: Location?, to: Location): Float {
        if (from == null) return 0f

        val bearing = atan2(
            sin(Math.toRadians(to.longitude - from.longitude)) * cos(Math.toRadians(to.latitude)),
            cos(Math.toRadians(from.latitude)) * sin(Math.toRadians(to.latitude)) -
                    sin(Math.toRadians(from.latitude)) * cos(Math.toRadians(to.latitude)) * cos(Math.toRadians(to.longitude - from.longitude))
        )

        return (Math.toDegrees(bearing).toFloat() + 360) % 360
    }
}

data class TelemetryData(
    val timestamp: Long,
    val speed: Float,
    val heading: Float,
    val latitude: Double,
    val longitude: Double
)
