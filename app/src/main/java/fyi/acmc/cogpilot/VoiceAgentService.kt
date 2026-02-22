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
import io.elevenlabs.AgentOverrides
import io.elevenlabs.ClientOverrides
import io.elevenlabs.Language
import io.elevenlabs.Overrides
import io.elevenlabs.PromptOverrides
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
    
    // Track interaction history during the driving session for context in future interactions
    private val interactionHistory = mutableListOf<String>()
    // Track messages in current session for creating summary on end
    private val currentSessionMessages = mutableListOf<String>()
    // Heartbeat monitoring to detect silent disconnects
    private var lastHeartbeatMs = 0L
    private var heartbeatJob: Job? = null
    private val HEARTBEAT_TIMEOUT_MS = 12_000L

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
        currentSessionMessages.clear()  // Reset messages for this session
        lastHeartbeatMs = System.currentTimeMillis()
        startHeartbeatMonitor()

        serviceScope.launch {
            try {
                // Fade out Spotify music before starting voice agent
                Log.i(TAG, "🎵 Fading out Spotify music...")
                spotifyManager.fadeOutAndPause(durationMs = 5000L)
                
                // Play intro chime after fade completes
                Log.i(TAG, "🔔 About to play intro chime...")
                try {
                    playIntroChime()
                    Log.i(TAG, "✅ 🔔 Chime playback method completed successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Chime playback failed: ${e.message}", e)
                }
                
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
                        // Clear history for start of drive - fresh start
                        interactionHistory.clear()
                        Log.d(TAG, "Cleared interaction history for START_DRIVE")
                        "INTERACTION TYPE: Start of drive - Welcome the driver warmly, set a positive mood, ask open-ended questions like where they are headed or what their day looks like. Keep it conversational and friendly to build rapport."
                    }
                    else -> {
                        // Include previous interaction context for mid-drive check-ins
                        val historyContext = if (interactionHistory.isNotEmpty()) {
                            "\n\nPREVIOUS INTERACTIONS DURING THIS DRIVE:\n${interactionHistory.joinToString("\n---\n")}"
                        } else {
                            ""
                        }
                        "INTERACTION TYPE: Mid-drive check-in - Brief check-in to monitor well-being. Be concise and focused. Ask how they are doing or if they need a break. Keep questions short and easy to answer while driving.$historyContext"
                    }
                }
                val systemContext = "DRIVER NAME: $currentDriverId\nDRIVER INTERESTS: ${profile["interests"]}\nDRIVER COMPLEXITY: ${profile["complexity"]}\n\n$interactionTypeContext"
                Log.d(TAG, "System context: $systemContext")

                val initialMessage = startMsg.ifBlank { generateInitialGreeting() }
                Log.d(TAG, "Initial message override: $initialMessage")
                pendingStartMessage = null
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
                    userId = currentDriverId,
                    overrides = Overrides(
                        agent = AgentOverrides(
                            prompt = PromptOverrides(prompt = systemContext),
                            firstMessage = initialMessage,
                            language = Language.EN
                        ),
                        client = ClientOverrides(
                            source = "android_sdk",
                            version = BuildConfig.VERSION_NAME
                        )
                    ),
                    dynamicVariables = mapOf(
                        "driver_name" to currentDriverId,
                        "interaction_type" to currentInteractionType,
                        "driver_interests" to (profile["interests"] ?: ""),
                        "driver_complexity" to (profile["complexity"] ?: ""),
                        "interaction_history" to interactionHistory.joinToString("\n---\n")
                    ),
                    onConnect = { conversationId ->
                        Log.i(TAG, "✓ Connected: $conversationId")
                        recordHeartbeat("connect")
                        updateNotification("🎙️ Listening...")
                        broadcastStatus("connected")
                        
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
                        recordHeartbeat("status:$statusStr")
                        
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
                        recordHeartbeat("mode:${mode.toString().lowercase()}")
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
                        recordHeartbeat("vad")
                    },
                    onMessage = { source, messageJson ->
                        Log.d(TAG, "Message from $source: ${messageJson.take(500)}")
                        recordHeartbeat("message:$source")
                        // broadcast agent responses to UI and track for history
                        if (source == "agent") {
                            try {
                                val msg = JSONObject(messageJson).optString("message", "")
                                if (msg.isNotEmpty()) {
                                    // Record agent message for interaction summary
                                    currentSessionMessages.add("Agent: $msg")
                                    
                                    val intent = Intent(ACTION_AI_LOG).apply {
                                        putExtra(EXTRA_AI_MSG, "🤖 Agent: $msg")
                                    }
                                    sendBroadcast(intent)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to parse agent message: ${e.message}")
                            }
                        } else if (source == "driver") {
                            // Also track driver responses
                            try {
                                val msg = JSONObject(messageJson).optString("message", "")
                                if (msg.isNotEmpty()) {
                                    currentSessionMessages.add("Driver: $msg")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to parse driver message: ${e.message}")
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
        Log.i(TAG, "🛑 Stopping voice session (user pressed stop button)...")
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
                        Log.d(TAG, "Calling endInteraction from stopVoiceSession finally block")
                        endInteraction()
                    }
                }
            } else {
                Log.d(TAG, "No active session to end, calling endInteraction directly")
                endInteraction()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stop error: ${e.message}", e)
            // Ensure cleanup happens even if error occurs
            endInteraction()
        }
    }

    private fun endInteraction() {
        Log.i(TAG, "════════════════════════════════════════════════════════")
        Log.i(TAG, "📍 endInteraction() CRITICAL CLEANUP ROUTINE STARTING")
        Log.i(TAG, "════════════════════════════════════════════════════════")
        
        if (interactionEnded) {
            Log.w(TAG, "⚠️ GUARD: Interaction already ended, skipping duplicate cleanup")
            return
        }
        interactionEnded = true
        Log.i(TAG, "✅ GUARD SET: interactionEnded=true - proceeding with cleanup...")

        // Stop heartbeat monitor now that we're ending
        heartbeatJob?.cancel()
        heartbeatJob = null
        
        // Log state before cleanup
        Log.d(TAG, "Spotify manager initialized: ${spotifyManager != null}")
        Log.d(TAG, "Session exists: ${session != null}")
        
        try {
            // Record interaction summary to history (for mid-drive interactions only)
            if (currentInteractionType != VoiceAgentTrigger.INTERACTION_TYPE_START_DRIVE && currentSessionMessages.isNotEmpty()) {
                val summary = currentSessionMessages.joinToString("\n")
                interactionHistory.add(summary)
                Log.i(TAG, "📝 Added interaction summary to history (total interactions: ${interactionHistory.size})")
                Log.d(TAG, "Summary: $summary")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error recording interaction summary: ${e.message}")
        }
        
        Log.i(TAG, "🎙️ CRITICAL CLEANUP: Start resuming Spotify and finalizing...")
        
        // Launch async cleanup but ensure it starts
        serviceScope.launch {
            Log.i(TAG, "🔄 Cleanup coroutine started")
            
            // HIGHEST PRIORITY: Resume Spotify
            try {
                Log.i(TAG, "🎵 PRIORITY: Calling spotifyManager.resumeIfNeeded()...")
                Log.d(TAG, "About to call: spotifyManager.resumeIfNeeded()")
                spotifyManager.resumeIfNeeded()
                Log.i(TAG, "✅ SUCCESS: spotifyManager.resumeIfNeeded() returned (music should resume now)")
            } catch (e: Exception) {
                Log.e(TAG, "❌ FAILED: Resume Spotify error: ${e.message}", e)
            }
            
            // Small delay to let Spotify actually start
            kotlinx.coroutines.delay(500)
            
            // Clean up session
            try {
                Log.d(TAG, "Ending ElevenLabs session...")
                session?.endSession()
                session = null
                Log.i(TAG, "✓ Session ended")
            } catch (e: Exception) {
                Log.e(TAG, "Error ending session: ${e.message}")
            }
            
            // Broadcast interaction end event
            try {
                Log.d(TAG, "Broadcasting interaction end event...")
                val intent = Intent(ACTION_AI_LOG).apply {
                    putExtra(EXTRA_AI_MSG, "✓ Interaction ended")
                }
                sendBroadcast(intent)
                Log.i(TAG, "✓ Broadcast sent")
            } catch (e: Exception) {
                Log.e(TAG, "Error broadcasting: ${e.message}")
            }
            
            Log.i(TAG, "════════════════════════════════════════════════════════")
            Log.i(TAG, "✅ CLEANUP ROUTINE COMPLETE - All critical steps executed")
            Log.i(TAG, "════════════════════════════════════════════════════════")
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

    private fun recordHeartbeat(source: String) {
        lastHeartbeatMs = System.currentTimeMillis()
        Log.d(TAG, "Heartbeat updated from $source")
    }

    private fun startHeartbeatMonitor() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isRunning && !interactionEnded) {
                kotlinx.coroutines.delay(2000)
                val elapsed = System.currentTimeMillis() - lastHeartbeatMs
                if (elapsed > HEARTBEAT_TIMEOUT_MS) {
                    Log.w(TAG, "⏱️ No heartbeat for ${elapsed}ms - triggering endInteraction()")
                    isRunning = false
                    endInteraction()
                    break
                }
            }
        }
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
        Log.i(TAG, "🔔🔔🔔 playIntroChime() STARTING - THIS SHOULD PLAY A SOUND 🔔🔔🔔")
        try {
            // Request audio focus first
            val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            Log.d(TAG, "Requesting audio focus for notification stream...")
            val result = audioManager.requestAudioFocus(
                null,
                android.media.AudioManager.STREAM_NOTIFICATION,
                android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
            Log.d(TAG, "Audio focus request returned: $result")
            
            if (result == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.i(TAG, "✅ Audio focus GRANTED, creating ToneGenerator...")
                val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)
                Log.i(TAG, "✅ ToneGenerator created")
                
                // Play two rising tones
                Log.i(TAG, "🔔 Playing FIRST tone (200ms)...")
                toneGen.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
                Thread.sleep(250)
                Log.i(TAG, "✓ First tone completed")
                
                Log.i(TAG, "🔔 Playing SECOND tone (200ms)...")
                toneGen.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
                Thread.sleep(100)
                Log.i(TAG, "✓ Second tone completed")
                
                toneGen.release()
                Log.i(TAG, "✓ ToneGenerator released")
                
                // Abandon audio focus
                audioManager.abandonAudioFocus(null)
                Log.i(TAG, "✅✅✅ CHIME PLAYED SUCCESSFULLY - SHOULD HAVE HEARD TWO TONES ✅✅✅")
            } else {
                Log.e(TAG, "❌❌❌ AUDIO FOCUS DENIED (result=$result) - CHIME WILL NOT PLAY ❌❌❌")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌❌❌ EXCEPTION IN CHIME: ${e.message}", e)
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
        Log.i(TAG, "🛑 ⚠️ SERVICE DESTROYED - GUARANTEED CLEANUP TRIGGERED ⚠️")
        Log.i(TAG, "Reason: Service exiting for ANY reason (stop, crash, memory pressure, system kill, etc.)")
        
        try {
            // MUST call endInteraction() - this is the ONLY guaranteed cleanup hook
            // onDestroy() fires EVERY TIME the service exits, no matter the reason
            Log.i(TAG, "Calling endInteraction() from onDestroy()...")
            
            // Call the full interaction cleanup (blocking call to ensure it starts)
            endInteraction()
            Log.i(TAG, "✓ endInteraction() initiated in onDestroy")
            
            // CRITICAL: Wait for cleanup coroutine to complete before cancelling scope
            // This ensures Spotify resume and other async cleanup actually happens
            Log.i(TAG, "⏳ Waiting 1 second for cleanup coroutine to complete...")
            Thread.sleep(1000)
            Log.i(TAG, "✓ Cleanup coroutine should have completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in onDestroy: ${e.message}", e)
        } finally {
            Log.i(TAG, "🛑 Cancelling scope and stopping service...")
            // Now safe to cancel scope - cleanup is done
            serviceScope.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            super.onDestroy()
            Log.i(TAG, "✅ Service onDestroy() fully completed")
        }
    }

    override fun onBind(intent: Intent?): IBinder = VoiceAgentBinder()
}

