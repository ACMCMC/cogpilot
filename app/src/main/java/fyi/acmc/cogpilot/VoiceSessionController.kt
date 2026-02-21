package fyi.acmc.cogpilot

import android.util.Log

object VoiceSessionController {
    var onTrigger: (() -> Unit)? = null

    fun triggerVoice() {
        Log.i("CogPilot", "🎯 VoiceSessionController trigger")
        onTrigger?.invoke()
    }
}
