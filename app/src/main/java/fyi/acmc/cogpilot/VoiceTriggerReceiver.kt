package fyi.acmc.cogpilot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class VoiceTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("CogPilot", "📡 Voice trigger broadcast received")
        if (intent.action == MainActivity.ACTION_TRIGGER_VOICE) {
            VoiceSessionController.triggerVoice()
        }
    }
}
