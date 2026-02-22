package fyi.acmc.cogpilot

import android.os.Bundle
import android.service.media.MediaBrowserService
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.MediaDescription

/**
 * MediaBrowserService for CogPilot.
 * This provides the "headless" entry point required by Android Auto
 * and enables correct audio focus management.
 */
class CogPilotMediaService : MediaBrowserService() {

    private var mediaSession: MediaSession? = null
    private var currentScore: Float = 0f
    private var currentState: RiskState = RiskState.STABLE
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()

        // Create a MediaSession
        mediaSession = MediaSession(this, "CogPilotMediaService").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            isActive = true
        }
        sessionToken = mediaSession?.sessionToken

        // Register for risk updates
        setupRiskListener()
    }

    private fun setupRiskListener() {
        val engine = VoiceAgentService.riskEngine
        if (engine != null) {
            engine.onRiskScoreUpdated = { score, state ->
                currentScore = score
                currentState = state
                // Update Android Auto UI
                notifyChildrenChanged("cogpilot_root")
            }
            android.util.Log.i("CogPilotMediaService", "✓ Risk listener registered")
        } else {
            // Retry in 2 seconds if engine not yet ready (headless startup)
            handler.postDelayed({ setupRiskListener() }, 2000)
        }
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        // Handle "user not signed in" scenario by checking credentials
        if (BuildConfig.ELEVENLABS_API_KEY.isEmpty() || BuildConfig.ELEVENLABS_AGENT_ID.isEmpty()) {
            android.util.Log.e("CogPilotMediaService", "❌ STARTUP ERROR: User not signed in (missing API keys). Returning null root.")
            return null // Reject connection if not "signed in"
        }

        // Allow all clients to browse our media content (which is empty/dummy for now)
        return BrowserRoot("cogpilot_root", null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        // Return a single status item to prevent "No items" and confirm CogPilot is active
        val mediaItems = mutableListOf<MediaBrowser.MediaItem>()
        
        // Convert risk score to attention percentage (Inverse of risk)
        val attentionPercent = ((1.1f - currentScore) / 1.1f * 100).toInt().coerceIn(0, 100)
        val stateLabel = when(currentState) {
            RiskState.STABLE -> "All Good"
            RiskState.EMERGING -> "Drift Detected"
            RiskState.WINDOW -> "Check-in Required"
            RiskState.CRITICAL -> "CAUTION"
        }

        val description = MediaDescription.Builder()
            .setMediaId("cogpilot_status")
            .setTitle("Attention: $attentionPercent% — $stateLabel")
            .setSubtitle("CogPilot Monitoring Active")
            .build()
            
        mediaItems.add(MediaBrowser.MediaItem(description, MediaBrowser.MediaItem.FLAG_PLAYABLE))
        
        result.sendResult(mediaItems)
    }

    override fun onDestroy() {
        mediaSession?.release()
        super.onDestroy()
    }
}
