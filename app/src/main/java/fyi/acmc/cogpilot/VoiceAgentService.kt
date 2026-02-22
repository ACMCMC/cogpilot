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
import io.elevenlabs.Overrides
import io.elevenlabs.AgentOverrides
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
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
        const val EXTRA_MSG_SOURCE = "msg_source"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var session: ConversationSession? = null
    private var isRunning = false
    private val snowflakeManager = SnowflakeManager()
    private val calendarContext = CalendarContextProvider(this)
    private val spotifyManager by lazy { SpotifyManager(this) }

    // hold context updates until connected
    private var pendingSystemContext: String? = null
    private var pendingDrivingContext: String? = null
    private var pendingTopics: String? = null
    private var pendingEventSummary: String? = null

    inner class VoiceAgentBinder : Binder() {
        fun getService(): VoiceAgentService = this@VoiceAgentService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "✓ Service created")
    }

    private var currentDriverId: String = "aldan_creo"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: ${intent?.action}")
        intent?.getStringExtra("EXTRA_USER_ID")?.let { currentDriverId = it }

        // Capture driving context from caller (available at the moment the session is triggered)
        if (intent?.action == ACTION_START) {
            val speedMph     = if (intent.hasExtra(VoiceAgentTrigger.EXTRA_SPEED_MPH))     intent.getFloatExtra(VoiceAgentTrigger.EXTRA_SPEED_MPH,     -1f) else null
            val roadTypes    = intent.getStringExtra(VoiceAgentTrigger.EXTRA_ROAD_TYPES)
            val speedLimit   = if (intent.hasExtra(VoiceAgentTrigger.EXTRA_SPEED_LIMIT))   intent.getFloatExtra(VoiceAgentTrigger.EXTRA_SPEED_LIMIT,   -1f) else null
            val trafficRatio = if (intent.hasExtra(VoiceAgentTrigger.EXTRA_TRAFFIC_RATIO)) intent.getFloatExtra(VoiceAgentTrigger.EXTRA_TRAFFIC_RATIO, -1f) else null
            val tripStartMs  = if (intent.hasExtra(VoiceAgentTrigger.EXTRA_TRIP_START_MS)) intent.getLongExtra(VoiceAgentTrigger.EXTRA_TRIP_START_MS,  -1L) else null

            pendingDrivingContext = buildDrivingContextString(
                speedMph     = speedMph?.takeIf { it >= 0 },
                roadTypes    = roadTypes,
                speedLimit   = speedLimit?.takeIf { it >= 0 },
                trafficRatio = trafficRatio?.takeIf { it >= 0 },
                tripStartMs  = tripStartMs?.takeIf { it > 0 }
            )
            Log.d(TAG, "Queued driving context: $pendingDrivingContext")
        }
        
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

        serviceScope.launch {
            try {
                // gather context before starting the conversation
                val startMsg = snowflakeManager.generateStartMessage(1)
                val topics = snowflakeManager.generateConversationTopics(driverId = 1)
                val profile = snowflakeManager.getUserProfileById(currentDriverId)
                val events = calendarContext.getUpcomingEvents(limit = 5, windowMinutes = 240)
                if (events.isNotEmpty()) {
                    snowflakeManager.insertCalendarEvents(driverId = 1, events = events)
                }
                
                // Fade out Spotify smoothly, then pause, before starting the agent conversation.
                spotifyManager.fadeOutAndPause()

                // Build driver context to inject at conversation start.
                // Use a readable display name (replace underscores with spaces, title-case).
                val displayName = currentDriverId
                    .replace('_', ' ')
                    .split(' ')
                    .joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
                val systemContext = buildString {
                    appendLine("[DRIVER CONTEXT - use this throughout the conversation]")
                    appendLine("Name: $displayName")
                    appendLine("Interests: ${profile["interests"]}")
                    appendLine("Complexity preference: ${profile["complexity"]}")
                    appendLine("Address the driver by name ($displayName) naturally but not on every turn.")
                    appendLine()
                    appendLine("[ENDING THE CONVERSATION]")
                    appendLine("You have a tool called `end_session`. Call it when:")
                    appendLine("- The driver has responded 2-3 times and sounds engaged and awake.")
                    appendLine("- The driver says they're fine, done, or wants to stop.")
                    appendLine("- You've completed a natural conversation arc (question → response → follow-up → wrap-up).")
                    appendLine("End gracefully with a short closing line before calling the tool, e.g. 'Drive safe, I'll check in again soon.'")
                    appendLine()
                    appendLine("[DYNAMIC TOOLS AVAILABLE]")
                    appendLine("You have access to the following dynamic tools:")
                    appendLine("1. `get_now_playing()` -> Returns the currently playing Spotify song.")
                    appendLine("2. `play_music(query: String)` -> Plays a Spotify playlist matching the query (e.g. 'energetic', 'calm', 'focus', 'pop', 'chill').")
                    appendLine("Use these tools as standard JSON function calls when the driver asks about music or needs a mood shift.")
                }
                Log.d(TAG, "Queued system context:\n$systemContext")
                pendingSystemContext = systemContext
                Log.d(TAG, "First message (Cortex): $startMsg")
                if (events.isNotEmpty()) {
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
                    overrides = Overrides(
                        agent = AgentOverrides(
                            firstMessage = startMsg.ifBlank { null }
                        )
                    ),
                    onConnect = { conversationId ->
                        Log.i(TAG, "✓ Connected: $conversationId")
                        updateNotification("🎙️ Listening...")
                        broadcastStatus("connected")
                        // 1. Driver identity + profile
                        pendingSystemContext?.let {
                            sendUpdate(it)
                            Log.d(TAG, "✓ Sent system context (driver profile)")
                            pendingSystemContext = null
                        }
                        // 2. Live driving conditions
                        pendingDrivingContext?.let {
                            sendUpdate(it)
                            Log.d(TAG, "✓ Sent driving context")
                            pendingDrivingContext = null
                        }
                        // 3. Suggested topics
                        pendingTopics?.let {
                            sendUpdate("Suggested topics: $it")
                            Log.d(TAG, "✓ Sent pre-convo topics")
                            pendingTopics = null
                        }
                        // 4. Calendar events
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
                            isRunning = false
                            stopSelf()
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
                    // Use dedicated callbacks instead of parsing onMessage JSON
                    onAgentResponse = { agentResponse ->
                        Log.d(TAG, "Agent: $agentResponse")
                        broadcastAiLog(agentResponse, "🤖 Agent")
                    },
                    onUserTranscript = { userTranscript ->
                        Log.d(TAG, "Driver: $userTranscript")
                        broadcastAiLog(userTranscript, "🧑 You")
                    },
                    onUnhandledClientToolCall = { call ->
                        Log.w(TAG, "Unhandled tool call: $call")
                    },
                    // register device tools that agent can call
                    clientTools = mapOf(
                        "get_now_playing" to object : ClientTool {
                            override suspend fun execute(parameters: Map<String, Any>): ClientToolResult? {
                                Log.i(TAG, "📢 Agent requested get_now_playing tool")
                                val np = spotifyManager.getNowPlaying()
                                return if (np.isSuccess) {
                                    ClientToolResult.success(np.getOrNull() ?: "")
                                } else {
                                    ClientToolResult.success("Spotify is not available or not playing")
                                }
                            }
                        },
                        "play_music" to object : ClientTool {
                            override suspend fun execute(parameters: Map<String, Any>): ClientToolResult? {
                                val query = parameters["query"]?.toString() ?: "energetic"
                                Log.i(TAG, "📢 Agent requested play_music tool with query: $query")
                                val res = spotifyManager.play(query)
                                return if (res.isSuccess) {
                                    ClientToolResult.success(res.getOrNull() ?: "")
                                } else {
                                    ClientToolResult.success("Spotify is not available")
                                }
                            }
                        },
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
                        Log.i(TAG, "Stopping service...")
                        stopSelf()
                    }
                }
            } else {
                Log.d(TAG, "No active session to end")
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stop error: ${e.message}", e)
            stopSelf()
        }
    }

    /**
     * Builds a plain-English driving context string from live telemetry.
     * Deliberately natural so the agent can weave it in without sounding like surveillance.
     */
    private fun buildDrivingContextString(
        speedMph: Float?,
        roadTypes: String?,
        speedLimit: Float?,
        trafficRatio: Float?,
        tripStartMs: Long?
    ): String? {
        val parts = mutableListOf<String>()

        // Driving duration
        if (tripStartMs != null) {
            val minutes = ((System.currentTimeMillis() - tripStartMs) / 60_000).toInt().coerceAtLeast(0)
            val timeOfDay = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val timeLabel = when {
                timeOfDay in 5..11 -> "morning"
                timeOfDay in 12..16 -> "afternoon"
                timeOfDay in 17..20 -> "evening"
                else -> "night"
            }
            parts += if (minutes < 2) "just started driving ($timeLabel)"
                     else "has been driving for $minutes minutes ($timeLabel)"
        }

        // Speed and road type
        if (speedMph != null) {
            val roadDesc = when {
                roadTypes != null && roadTypes.contains("highway", ignoreCase = true) -> "highway"
                roadTypes != null && roadTypes.contains("motorway", ignoreCase = true) -> "motorway"
                roadTypes != null && roadTypes.contains("trunk", ignoreCase = true) -> "main road"
                roadTypes != null && roadTypes.contains("residential", ignoreCase = true) -> "residential street"
                roadTypes != null -> roadTypes.substringBefore(",")
                else -> "road"
            }
            val limitNote = if (speedLimit != null) ", speed limit ${speedLimit.toInt()} mph" else ""
            parts += "going %.0f mph on a %s%s".format(speedMph, roadDesc, limitNote)
        }

        // Traffic
        if (trafficRatio != null) {
            val trafficDesc = when {
                trafficRatio > 1.5f -> "heavy traffic slowing things down"
                trafficRatio > 1.15f -> "moderate traffic"
                else -> "clear roads"
            }
            parts += trafficDesc
        }

        if (parts.isEmpty()) return null
        return "[CURRENT DRIVING CONDITIONS]\nThe driver ${parts.joinToString("; ")}."
    }

    private fun sendUpdate(text: String) {
        Log.d(TAG, "model input: $text")
        session?.sendContextualUpdate(text)
        // broadcast to UI as a context entry (no source label)
        broadcastAiLog(text, source = null)
    }

    private fun broadcastAiLog(text: String, source: String?) {
        val intent = Intent(ACTION_AI_LOG).apply {
            putExtra(EXTRA_AI_MSG, text)
            source?.let { putExtra(EXTRA_MSG_SOURCE, it) }
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
        spotifyManager.disconnect()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = VoiceAgentBinder()
}

