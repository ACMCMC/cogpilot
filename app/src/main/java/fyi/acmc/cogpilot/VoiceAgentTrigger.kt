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

    fun start(context: Context, driverId: String = "aldan_creo", source: String = "manual") {
        Log.i(TAG, "Starting voice agent for user=$driverId triggered by: $source")
        val intent = Intent(context, VoiceAgentService::class.java).apply {
            action = VoiceAgentService.ACTION_START
            putExtra("EXTRA_USER_ID", driverId)
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
