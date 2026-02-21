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
import kotlinx.coroutines.launch
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
    private var snowflakeManager: SnowflakeManager? = null

    fun startCapture(
        snowflakeManager: SnowflakeManager,
        callback: (speed: Float, heading: Float) -> Unit
    ) {
        this.snowflakeManager = snowflakeManager
        captureCallback = callback

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

        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val speed = location.speed * 2.237f  // Convert m/s to mph
                val heading = calculateHeading(lastLocation, location)
                lastLocation = location

                // Insert into Snowflake directly
                snowflakeManager?.let {
                    (context as? androidx.appcompat.app.AppCompatActivity)?.lifecycleScope?.launch {
                        it.insertTelemetry(
                            timestamp = System.currentTimeMillis(),
                            speed = speed,
                            heading = heading,
                            lat = location.latitude,
                            lon = location.longitude
                        )
                    }
                }

                // Update UI callback
                captureCallback?.invoke(speed, heading)

                android.util.Log.d(
                    "LocationCapture",
                    "Speed: $speed mph, Heading: $heading°, Lat: ${location.latitude}, Lon: ${location.longitude}"
                )
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {}
        }

        // Request location updates every 5 seconds (5000 ms) or 10 meters
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                5000L,  // 5 seconds
                10f,    // 10 meters minimum distance
                locationListener!!,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            android.util.Log.e("LocationCapture", "Error requesting location updates", e)
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
