package fyi.acmc.cogpilot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * VoiceTriggerReceiver: Handles voice commands and broadcast intents
 * Supports:
 * - Broadcast intents for testing (adb broadcast)
 * - Google Assistant voice commands
 * - Custom voice actions
 */
class VoiceTriggerReceiver : BroadcastReceiver() {
    
    companion object {
        const val ACTION_TRIGGER_VOICE = "fyi.acmc.cogpilot.action.TRIGGER_VOICE"
        const val ACTION_VOICE_COMMAND = "com.google.android.gms.actions.VOICE_COMMAND"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        
        Log.d("VoiceTriggerReceiver", "Intent received: ${intent.action}")
        
        when (intent.action) {
            ACTION_TRIGGER_VOICE -> {
                Log.i("VoiceTriggerReceiver", "🎙️ Broadcast trigger received")
                VoiceAgentTrigger.start(context, source = "broadcast_intent")
            }
            
            ACTION_VOICE_COMMAND -> {
                // Handle Google Assistant voice commands
                val voiceText = intent.getStringExtra("query") ?: intent.getStringExtra("text") ?: "voice command"
                Log.i("VoiceTriggerReceiver", "🎤 Google Assistant command: '$voiceText'")
                handleVoiceCommand(context, voiceText)
            }
            
            "android.intent.action.VOICE_COMMAND" -> {
                // Alternative voice command action
                val voiceText = intent.getStringExtra("android.intent.extra.VOICE_TEXT") ?: "voice command"
                Log.i("VoiceTriggerReceiver", "🎤 Voice command: '$voiceText'")
                handleVoiceCommand(context, voiceText)
            }
            
            "android.speech.action.RECOGNIZE_SPEECH" -> {
                // Generic speech recognition intent
                val voiceResults = intent.getStringArrayListExtra("android.speech.extra.RESULTS")
                val voiceText = voiceResults?.firstOrNull() ?: "speech recognized"
                Log.i("VoiceTriggerReceiver", "🎤 Speech recognized: '$voiceText'")
                handleVoiceCommand(context, voiceText)
            }
            
            else -> Log.d("VoiceTriggerReceiver", "Unknown action: ${intent.action}")
        }
    }

    private fun handleVoiceCommand(context: Context, voiceText: String) {
        val lowerText = voiceText.lowercase()
        
        when {
            // Trigger phrases
            lowerText.contains("hey copilot") || 
            lowerText.contains("talk to copilot") ||
            lowerText.contains("open copilot") ||
            lowerText.contains("startcopilot") ||
            lowerText.contains("start copilot") ||
            lowerText.contains("activate cogpilot") ||
            lowerText.contains("activate voice") ||
            lowerText.contains("engage voice") ->  {
                Log.i("VoiceTriggerReceiver", "✓ Voice agent triggered by: '$voiceText'")
                VoiceAgentTrigger.start(context, source = "voice_command")
            }
            
            // Stop phrases
            lowerText.contains("stop copilot") ||
            lowerText.contains("deactivate voice") ||
            lowerText.contains("end voice") || 
            lowerText.contains("close copilot") -> {
                Log.i("VoiceTriggerReceiver", "✓ Voice agent stopped by: '$voiceText'")
                VoiceAgentTrigger.stop(context)
            }
            
            else -> {
                Log.d("VoiceTriggerReceiver", "Unrecognized voice command: '$voiceText'")
                // Optionally log unrecognized commands for training
            }
        }
    }
}
