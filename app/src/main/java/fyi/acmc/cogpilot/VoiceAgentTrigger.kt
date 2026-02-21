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

    fun start(context: Context, source: String = "manual") {
        Log.i(TAG, "Starting voice agent triggered by: $source")
        val intent = Intent(context, VoiceAgentService::class.java).apply {
            action = VoiceAgentService.ACTION_START
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start: ${e.message}")
        }
    }

    fun stop(context: Context) {
        Log.i(TAG, "Stopping voice agent")
        val intent = Intent(context, VoiceAgentService::class.java).apply {
            action = VoiceAgentService.ACTION_STOP
        }
        context.stopService(intent)
    }

    fun toggle(context: Context, isRunning: Boolean, source: String = "manual") {
        if (isRunning) {
            stop(context)
        } else {
            start(context, source)
        }
    }
}
