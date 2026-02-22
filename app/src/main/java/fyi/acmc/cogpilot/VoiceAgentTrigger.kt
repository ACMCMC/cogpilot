package fyi.acmc.cogpilot

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * VoiceAgentTrigger: Utility to start/stop voice agent from anywhere
 * Usage: VoiceAgentTrigger.start(context) or VoiceAgentTrigger.stop(context)
 */
object VoiceAgentTrigger {

    private const val TAG = "VoiceAgentTrigger"

    // Interaction type constants
    const val INTERACTION_TYPE_START_DRIVE = "start_drive"
    const val INTERACTION_TYPE_CHECK_IN = "check_in"
    const val INTERACTION_TYPE_PRE_TRIP = "pre_trip"
    
    // Extra key constants shared between caller and service
    const val EXTRA_SOURCE        = "extra_source"
    const val EXTRA_INTERACTION_TYPE = "extra_interaction_type"
    const val EXTRA_SPEED_MPH     = "extra_speed_mph"
    const val EXTRA_ROAD_TYPE     = "extra_road_type"
    const val EXTRA_ROAD_TYPES    = "extra_road_types"
    const val EXTRA_TRAFFIC_RATIO = "extra_traffic_ratio"
    const val EXTRA_TRIP_START_MS = "extra_trip_start_ms"
    const val EXTRA_LAT           = "extra_lat"
    const val EXTRA_LON           = "extra_lon"

    fun start(
        context: Context,
        driverId: String = "aldan_creo",
        source: String = "manual",
        interactionType: String = INTERACTION_TYPE_CHECK_IN,
        speedMph: Float? = null,
        roadTypes: String? = null,
        trafficRatio: Float? = null,
        tripStartMs: Long? = null,
        lat: Double? = null,
        lon: Double? = null
    ) {
        Log.i(TAG, "Starting voice agent for user=$driverId interaction=$interactionType triggered by: $source")
        val intent = Intent(context, VoiceAgentService::class.java).apply {
            action = VoiceAgentService.ACTION_START
            putExtra("EXTRA_USER_ID", driverId)
            putExtra(EXTRA_SOURCE, source)
            putExtra(EXTRA_INTERACTION_TYPE, interactionType)
            speedMph?.let      { putExtra(EXTRA_SPEED_MPH,     it) }
            roadTypes?.let     { putExtra(EXTRA_ROAD_TYPES,    it) }
            trafficRatio?.let  { putExtra(EXTRA_TRAFFIC_RATIO, it) }
            tripStartMs?.let   { putExtra(EXTRA_TRIP_START_MS, it) }
            lat?.let           { putExtra(EXTRA_LAT,           it) }
            lon?.let           { putExtra(EXTRA_LON,           it) }
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start: ${e.message}")
        }
    }

    fun stop(context: Context) {
        Log.i(TAG, "🛑 Stopping voice agent")
        val intent = Intent(context, VoiceAgentService::class.java).apply {
            action = VoiceAgentService.ACTION_STOP
        }
        try {
            // use plain startService so Android doesn't expect a foreground call
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send stop intent: ${e.message}")
            // if service is already running we can always call stopService directly
            try {
                context.stopService(intent)
            } catch (ex: Exception) {
                Log.e(TAG, "Fallback stopService also failed: ${ex.message}")
            }
        }
    }

    fun toggle(context: Context, isRunning: Boolean, source: String = "manual") {
        if (isRunning) {
            stop(context)
        } else {
            start(context, source)
        }
    }
}
