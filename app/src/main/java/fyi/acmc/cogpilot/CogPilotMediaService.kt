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
    private var currentBreakdown: String = "Monitoring for your safety"
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
            engine.onRiskScoreUpdated = { score, state, breakdown ->
                currentScore = score
                currentState = state
                currentBreakdown = breakdown
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
        val mediaItems = mutableListOf<MediaBrowser.MediaItem>()
        
        val engine = VoiceAgentService.riskEngine
        if (engine == null || !engine.isDriving) {
            val description = MediaDescription.Builder()
                .setMediaId("cogpilot_status")
                .setTitle("Not driving")
                .setSubtitle("CogPilot is standing by")
                .build()
            mediaItems.add(MediaBrowser.MediaItem(description, MediaBrowser.MediaItem.FLAG_PLAYABLE))
            result.sendResult(mediaItems)
            return
        }
        
        // Convert risk score to attention percentage (Inverse of risk)
        val attentionPercent = ((1.1f - currentScore) / 1.1f * 100).toInt().coerceIn(0, 100)
        
        // Android Auto limits colors, so we use descriptive labels
        val stateLabel = when {
            attentionPercent >= 90 -> "Optimal Focus"
            attentionPercent >= 75 -> "Stable"
            attentionPercent >= 55 -> "Drift Detected"
            attentionPercent >= 35 -> "Check-in Required"
            else -> "CAUTION: FATIGUED"
        }

        // Add the primary header item showing total attention
        val headerDesc = MediaDescription.Builder()
            .setMediaId("cogpilot_status_header")
            .setTitle("Attention: $attentionPercent% — $stateLabel")
            .setSubtitle("Score: ${String.format("%.2f", currentScore)} / 1.10")
            .build()
        mediaItems.add(MediaBrowser.MediaItem(headerDesc, MediaBrowser.MediaItem.FLAG_PLAYABLE))

        // Split the detailed mathematical breakdown into multiple list items
        // so it doesn't get truncated by Android Auto's 2-line limit
        val breakdownLines = currentBreakdown.split("\n")
        breakdownLines.forEachIndexed { index, line ->
            if (line.isNotBlank()) {
                val lineDesc = MediaDescription.Builder()
                    .setMediaId("cogpilot_status_line_$index")
                    .setTitle(line)
                    .build()
                mediaItems.add(MediaBrowser.MediaItem(lineDesc, MediaBrowser.MediaItem.FLAG_PLAYABLE))
            }
        }
        
        result.sendResult(mediaItems)
    }

    override fun onDestroy() {
        mediaSession?.release()
        super.onDestroy()
    }
}
