package fyi.acmc.cogpilot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.elevenlabs.ConversationClient
import io.elevenlabs.ConversationConfig
import io.elevenlabs.ConversationSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import org.json.JSONObject
import io.elevenlabs.ClientTool
import io.elevenlabs.ClientToolResult

/**
 * VoiceAgentService: Background service managing ElevenLabs voice agent
 * Uses official ConversationClient SDK which handles all WebRTC + audio
 */
class VoiceAgentService : Service() {

    companion object {
        private const val TAG = "VoiceAgentService"
        private const val CHANNEL_ID = "voice_agent_channel"
        private const val NOTIFICATION_ID = 42
        const val ACTION_START = "fyi.acmc.cogpilot.voice.START"
        const val ACTION_STOP = "fyi.acmc.cogpilot.voice.STOP"
        const val ACTION_STATUS_CHANGE = "fyi.acmc.cogpilot.voice.STATUS_CHANGE"
        const val EXTRA_STATUS = "status"
        const val ACTION_AI_LOG = "fyi.acmc.cogpilot.voice.AI_LOG"
        const val EXTRA_AI_MSG = "ai_msg"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var session: ConversationSession? = null
    private var isRunning = false
    private var interactionEnded = false  // Guard against double cleanup
    private val snowflakeManager = SnowflakeManager()
    private val calendarContext = CalendarContextProvider(this)
    private val spotifyManager by lazy { SpotifyManager(this) }

    // hold context updates until connected
    private var pendingTopics: String? = null
    private var pendingEventSummary: String? = null
    private var pendingStartMessage: String? = null

    inner class VoiceAgentBinder : Binder() {
        fun getService(): VoiceAgentService = this@VoiceAgentService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "✓ Service created")
    }

    private var currentDriverId: String = "aldan_creo"
    private var currentInteractionType: String = VoiceAgentTrigger.INTERACTION_TYPE_CHECK_IN

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: ${intent?.action}")
        intent?.getStringExtra("EXTRA_USER_ID")?.let { currentDriverId = it }
        intent?.getStringExtra(VoiceAgentTrigger.EXTRA_INTERACTION_TYPE)?.let { currentInteractionType = it }
        
        when (intent?.action) {
            ACTION_START -> {
                createNotificationChannel()
                val notification = createNotification("Initializing voice agent...")
                startForeground(NOTIFICATION_ID, notification)
                startVoiceSession()
            }
            ACTION_STOP -> {
                Log.i(TAG, "Stop action received, ending session...")
                stopVoiceSession()
            }
            else -> {
                Log.d(TAG, "Unknown action: ${intent?.action}")
                // Don't start foreground for unknown actions
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun startVoiceSession() {
        if (isRunning) {
            Log.w(TAG, "Already running")
            return
        }

        Log.i(TAG, "🎙️ Starting voice agent with official SDK...")
        isRunning = true
        interactionEnded = false  // Reset for new interaction

        serviceScope.launch {
            try {
                // Fade out Spotify music before starting voice agent
                Log.i(TAG, "🎵 Fading out Spotify music...")
                spotifyManager.fadeOutAndPause(durationMs = 5000L)
                
                // Play intro chime after fade completes
                Log.i(TAG, "🔔 Playing intro chime...")
                playIntroChime()
                
                // gather context before starting the conversation
                val startMsg = snowflakeManager.generateStartMessage(1)
                val topics = snowflakeManager.generateConversationTopics(driverId = 1)
                val profile = snowflakeManager.getUserProfileById(currentDriverId)
                val events = calendarContext.getUpcomingEvents(limit = 5, windowMinutes = 240)
                if (events.isNotEmpty()) {
                    snowflakeManager.insertCalendarEvents(driverId = 1, events = events)
                }
                // prepare plain-text profile description for AI with explicit system context
                val interactionTypeContext = when (currentInteractionType) {
                    VoiceAgentTrigger.INTERACTION_TYPE_START_DRIVE -> {
                        "INTERACTION TYPE: Start of drive - Welcome the driver warmly, set a positive mood, ask open-ended questions like where they are headed or what their day looks like. Keep it conversational and friendly to build rapport."
                    }
                    else -> {
                        "INTERACTION TYPE: Mid-drive check-in - Brief check-in to monitor well-being. Be concise and focused. Ask how they are doing or if they need a break. Keep questions short and easy to answer while driving."
                    }
                }
                val systemContext = "DRIVER NAME: $currentDriverId\nDRIVER INTERESTS: ${profile["interests"]}\nDRIVER COMPLEXITY: ${profile["complexity"]}\n\n$interactionTypeContext"
                Log.d(TAG, "System context: $systemContext")
                sendUpdate(systemContext)
                // also log/start message
                Log.d(TAG, "Start message: $startMsg")
                pendingStartMessage = startMsg
                if(events.isNotEmpty()){
                    val eventSummary = events.joinToString("; ") { ev ->
                        val title = ev.title.ifBlank { "(untitled)" }
                        val loc = ev.location?.let { " @ $it" } ?: ""
                        "${title}${loc}"
                    }
                    Log.d(TAG, "Calendar summary: $eventSummary")
                    sendUpdate("Upcoming calendar events: $eventSummary")
                }

                // setup callbacks for the official SDK
                val config = ConversationConfig(
                    agentId = BuildConfig.ELEVENLABS_AGENT_ID,
                    userId = "driver",
                    onConnect = { conversationId ->
                        Log.i(TAG, "✓ Connected: $conversationId")
                        updateNotification("🎙️ Listening...")
                        broadcastStatus("connected")
                        
                        // ALWAYS send an initial message to kickstart the conversation
                        val initialMessage = pendingStartMessage ?: generateInitialGreeting()
                        sendUpdate(initialMessage)
                        Log.d(TAG, "✓ Sent initial message")
                        pendingStartMessage = null
                        
                        // now send any pending context
                        pendingTopics?.let {
                            sendUpdate("Suggested topics: $it")
                            Log.d(TAG, "✓ Sent pre-convo topics")
                            pendingTopics = null
                        }
                        pendingEventSummary?.let {
                            sendUpdate("Upcoming calendar events: $it")
                            Log.d(TAG, "✓ Sent calendar context")
                            pendingEventSummary = null
                        }
                    },
                    onStatusChange = { status ->
                        Log.d(TAG, "Status: $status")
                        val statusStr = status.toString().lowercase()
                        
                        val notifText = when {
                            statusStr.contains("connected") -> "🎙️ Listening..."
                            statusStr.contains("disconnected") -> "Voice ended"
                            else -> "Connecting..."
                        }
                        updateNotification(notifText)
                        broadcastStatus(statusStr)

                        if (statusStr.contains("disconnected")) {
                            Log.i(TAG, "🔌 Participant disconnected - triggering end of interaction")
                            isRunning = false
                            endInteraction()
                        }
                    },
                    onModeChange = { mode ->
                        Log.d(TAG, "Mode: $mode")
                        val notifText = when {
                            mode.toString().lowercase().contains("speaking") -> "🤖 Agent speaking..."
                            mode.toString().lowercase().contains("listening") -> "🎙️ Listening..."
                            else -> mode.toString()
                        }
                        updateNotification(notifText)
                    },
                    onVadScore = { score ->
                        // voice activity detection
                        if (score > 0.5f) {
                            Log.d(TAG, "Voice detected: $score")
                        }
                    },
                    onMessage = { source, messageJson ->
                        Log.d(TAG, "Message from $source: ${messageJson.take(500)}")
                        // broadcast agent responses to UI
                        if (source == "agent") {
                            try {
                                val msg = JSONObject(messageJson).optString("message", "")
                                if (msg.isNotEmpty()) {
                                    val intent = Intent(ACTION_AI_LOG).apply {
                                        putExtra(EXTRA_AI_MSG, "🤖 Agent: $msg")
                                    }
                                    sendBroadcast(intent)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to parse agent message: ${e.message}")
                            }
                        }
                    },
                    onUnhandledClientToolCall = { call ->
                        Log.w(TAG, "Unhandled tool call: $call")
                    },
                    // register device tools that agent can call
                    clientTools = mapOf(
                        // agent requests string name 'stop_conversation' to end session
                        "stop_conversation" to object : ClientTool {
                            override suspend fun execute(parameters: Map<String, Any>): ClientToolResult? {
                                Log.i(TAG, "📢 Agent requested stop_conversation tool")
                                session?.endSession()
                                stopVoiceSession()
                                return ClientToolResult.success("stopped")
                            }
                        },
                        "stopConversation" to object : ClientTool {
                            override suspend fun execute(parameters: Map<String, Any>): ClientToolResult? {
                                Log.i(TAG, "📢 Agent requested stopConversation tool")
                                session?.endSession()
                                stopVoiceSession()
                                return ClientToolResult.success("stopped")
                            }
                        },
                        "end_session" to object : ClientTool {
                            override suspend fun execute(parameters: Map<String, Any>): ClientToolResult? {
                                Log.i(TAG, "📢 Agent requested end_session tool")
                                session?.endSession()
                                stopVoiceSession()
                                return ClientToolResult.success("stopped")
                            }
                        },
                        "endSession" to object : ClientTool {
                            override suspend fun execute(parameters: Map<String, Any>): ClientToolResult? {
                                Log.i(TAG, "📢 Agent requested endSession tool")
                                session?.endSession()
                                stopVoiceSession()
                                return ClientToolResult.success("stopped")
                            }
                        }
                    )
                )

                // start the conversation - official SDK handles everything
                session = ConversationClient.startSession(config, this@VoiceAgentService)
                Log.i(TAG, "✓ Official SDK conversation started")

                // messages will be sent once connection established (see onConnect callback)
                pendingTopics = topics
                pendingEventSummary = if (events.isNotEmpty()) {
                    events.joinToString("; ") { ev ->
                        val title = ev.title.ifBlank { "(untitled)" }
                        val loc = ev.location?.let { " @ $it" } ?: ""
                        "${title}${loc}"
                    }
                } else null
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start: ${e.message}", e)
                updateNotification("❌ Error: ${e.message}")
                isRunning = false
                stopSelf()
            }
        }
    }

    private fun stopVoiceSession() {
        Log.i(TAG, "🛑 Stopping voice session...")
        isRunning = false
        
        try {
            // end the conversation immediately
            if (session != null) {
                Log.d(TAG, "Ending conversation session...")
                serviceScope.launch {
                    try {
                        session?.endSession()
                        Log.i(TAG, "✓ Session ended successfully")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error ending session: ${e.message}", e)
                    } finally {
                        session = null
                        // Trigger end of interaction cleanup (guard prevents double calls)
                        endInteraction()
                    }
                }
            } else {
                Log.d(TAG, "No active session to end")
                endInteraction()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stop error: ${e.message}", e)
            stopSelf()
        }
    }

    private fun endInteraction() {
        if (interactionEnded) {
            Log.d(TAG, "Interaction already ended, skipping duplicate cleanup")
            return
        }
        interactionEnded = true
        
        Log.i(TAG, "🎙️ End of interaction - resuming Spotify and cleaning up...")
        serviceScope.launch {
            try {
                // Resume Spotify playback if it was playing before
                spotifyManager.resumeIfNeeded()
                Log.i(TAG, "✓ Spotify resumed")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resume Spotify: ${e.message}")
            }
            
            try {
                // Clean up session
                session?.endSession()
                session = null
                Log.i(TAG, "✓ Session cleaned up")
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up session: ${e.message}")
            }
            
            // Broadcast interaction end event
            val intent = Intent(ACTION_AI_LOG).apply {
                putExtra(EXTRA_AI_MSG, "✓ Interaction ended")
            }
            sendBroadcast(intent)
            
            // Stop the foreground service
            Log.i(TAG, "Stopping service...")
            stopSelf()
        }
    }

    private fun sendUpdate(text:String) {
        Log.d(TAG, "model input: $text")
        session?.sendContextualUpdate(text)
        // broadcast to UI to show in AI log
        val intent = Intent(ACTION_AI_LOG).apply {
            putExtra(EXTRA_AI_MSG, text)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Agent",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Real-time voice interaction with AI agent"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CogPilot Voice Agent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val notification = createNotification(text)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun broadcastStatus(status: String) {
        val intent = Intent(ACTION_STATUS_CHANGE).apply {
            putExtra(EXTRA_STATUS, status)
            setPackage(packageName)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun playIntroChime() {
        try {
            // Use ToneGenerator to play a pleasant notification chime
            // Two tones: a rising chime effect
            val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)
            toneGen.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200) // 200ms
            Thread.sleep(250)
            toneGen.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
            Thread.sleep(200)
            toneGen.release()
            Log.i(TAG, "✓ Intro chime played")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play intro chime: ${e.message}")
        }
    }

    private fun generateInitialGreeting(): String {
        val greeting = when (currentInteractionType) {
            VoiceAgentTrigger.INTERACTION_TYPE_START_DRIVE -> {
                "Hey $currentDriverId! Ready to hit the road? Let's make this a smooth drive ahead."
            }
            else -> {
                "Hey $currentDriverId, just checking in. How are you doing?"
            }
        }
        Log.d(TAG, "Generated initial greeting: $greeting")
        return greeting
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        serviceScope.launch {
            try {
                session?.endSession()
                session = null
            } catch (e: Exception) {
                Log.e(TAG, "Cleanup error: ${e.message}")
            }
        }
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = VoiceAgentBinder()
}

