package fyi.acmc.cogpilot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.media.MediaPlayer
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import android.content.BroadcastReceiver
import org.json.JSONObject
import org.json.JSONArray
import io.elevenlabs.ClientTool
import io.elevenlabs.ClientToolResult
import kotlin.random.Random

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
        const val ACTION_LOCATION_UPDATE = "fyi.acmc.cogpilot.voice.LOCATION_UPDATE"

        private var _riskEngine: RiskDecisionEngine? = null
        val riskEngine: RiskDecisionEngine? get() = _riskEngine
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

    // hold context updates until connected
    private var pendingTopics: String? = null
    private var pendingEventSummary: String? = null
    private var pendingStartMessage: String? = null
    
    // Keep MediaPlayer references to prevent garbage collection during playback
    private var introMediaPlayer: MediaPlayer? = null
    private var outroMediaPlayer: MediaPlayer? = null

    private var locationReceiver: BroadcastReceiver? = null

    inner class VoiceAgentBinder : Binder() {
        fun getService(): VoiceAgentService = this@VoiceAgentService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "✓ Service created")
        
        // Initialize RiskDecisionEngine
        if (_riskEngine == null) {
            _riskEngine = RiskDecisionEngine(this)
        }
        
        // Register location receiver
        locationReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_LOCATION_UPDATE) {
                    val speed = intent.getFloatExtra("extra_speed_mph", 0f)
                    val lat = intent.getFloatExtra("extra_lat", 0f)
                    val lon = intent.getFloatExtra("extra_lon", 0f)
                    
                    val loc = android.location.Location("provider").apply {
                        this.speed = speed / 2.237f // convert mph back to m/s
                        this.latitude = lat.toDouble()
                        this.longitude = lon.toDouble()
                    }
                    _riskEngine?.updateLocation(loc)
                }
            }
        }
        val filter = android.content.IntentFilter(ACTION_LOCATION_UPDATE)
        registerReceiver(locationReceiver, filter, Context.RECEIVER_EXPORTED)
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

        // Start RiskDecisionEngine trip
        _riskEngine?.startTrip(currentDriverId, 7) // Hardcoded 7h sleep for now

        serviceScope.launch {
            try {
                // Fade out Spotify music before starting voice agent (pause, not stop)
                Log.i(TAG, "🎵 Fading out Spotify music (5000ms)...")
                spotifyManager.fadeOutAndPause(durationMs = 5000L)
                
                // Wait for fade to complete before playing chime
                Log.d(TAG, "⏳ Waiting for music fade to complete...")
                kotlinx.coroutines.delay(5500L)  // 5s fade + 0.5s buffer
                
                // Play intro chime after fade completes
                Log.i(TAG, "🔔 Music paused, now playing intro chime...")
                try {
                    playIntroChime()
                    Log.i(TAG, "✅ 🔔 Chime playback method completed successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Chime playback failed: ${e.message}", e)
                }
                
                // gather context before starting the conversation
                val greetingSeed = Random.nextInt(1000, 9999)
                val recentInteractions = snowflakeManager.getRecentInteractionSummaries(currentDriverId, limit = 3)
                val lastTripSummary = snowflakeManager.getLastTripSummary(currentDriverId)
                val startMsg = snowflakeManager.generateStartMessage(currentDriverId, greetingSeed, recentInteractions)
                val topics = snowflakeManager.generateConversationTopics(driverId = 1)
                val profile = snowflakeManager.getUserProfileById(currentDriverId)
                val events = calendarContext.getUpcomingEvents(limit = 5, windowMinutes = 240)
                if (events.isNotEmpty()) {
                    snowflakeManager.insertCalendarEvents(driverId = 1, events = events)
                }

                val info = _riskEngine?.getRiskStateDebugInfo()
                val profileDetails = _riskEngine?.getDriverProfileDetails() ?: emptyMap()
                
                // Build rich calendar context
                val calendarContext = if (events.isNotEmpty()) {
                    "\n\nUPCOMING CALENDAR EVENTS:\n" + events.joinToString("\n") { ev ->
                        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(ev.startMs)
                        val title = ev.title.ifBlank { "(untitled)" }
                        val loc = ev.location?.let { " @ $it" } ?: ""
                        "- $time: $title$loc"
                    }
                } else {
                    ""
                }
                
                // prepare plain-text profile description for AI with explicit system context
                val interactionTypeContext = when (currentInteractionType) {
                    VoiceAgentTrigger.INTERACTION_TYPE_START_DRIVE -> {
                        // Clear history for start of drive - fresh start
                        interactionHistory.clear()
                        Log.d(TAG, "Cleared interaction history for START_DRIVE")
                        """INTERACTION TYPE: Start of drive
Welcome the driver warmly and set a positive mood. Ask engaging questions like:
- Where are they headed today?
- What's their main focus/goal for the drive?
- Are there any concerns or things on their mind?
- Reference their calendar events if relevant to the journey
Keep it conversational and friendly to build rapport.$calendarContext"""
                    }
                    else -> {
                        // Include previous interaction context and smarter prompts for mid-drive check-ins
                        val historyContext = if (interactionHistory.isNotEmpty()) {
                            "\n\nRECENT CONVERSATION:\n${interactionHistory.takeLast(2).joinToString("\n")}"
                        } else {
                            ""
                        }
                        """INTERACTION TYPE: Mid-drive check-in
Have a focused, contextual conversation. Ask smart questions like:
- How are they feeling about what lies ahead?
- Any update on the events they mentioned?
- Thoughts on the current road conditions or scenery?
- Would they benefit from a break or music change?
Be concise and easy to answer while driving.
$historyContext$calendarContext"""
                    }
                }
                
                val systemContext = """
                    |YOU ARE: The cognitive co-pilot for $currentDriverId.
                    |
                    |DRIVER PROFILE:
                    |- Interests: ${profile["interests"]}
                    |- Complexity Level: ${profile["complexity"]}
                    |- Last trip memory: ${if (lastTripSummary.isNotBlank()) lastTripSummary else "No previous trip data available."}
                    |- Known risk triggers: ${profileDetails["triggers"] ?: "None"}
                    |- Effective persuasion levers: ${profileDetails["levers"] ?: "None"}
                    |- Rejection pattern: ${profileDetails["rejection_pattern"] ?: "None"}
                    |- Communication boundary: ${profileDetails["boundary"] ?: "None"}
                    |
                    |CURRENT TELEMETRY (TRENDS):
                    |- Vocal Energy: ${String.format("%.2f", info?.vocalEnergyTrend ?: 0.8f)} (Baseline: 0.8)
                    |- Response Latency: ${info?.latencyTrend ?: 500}ms (Delayed if > 1200ms)
                    |- Driving Time: ${info?.driveMinutes ?: 0} min
                    |
                    |MISSION: Monitor driver drowsiness and intervene with cognitive stimuli before they fall asleep.
                    |
                    |INTERACTION LEVELS (MANDATORY CONSTRAINTS):
                    |Level 0: ACTIVE SILENCE. Do not speak unless spoken to.
                    |Level 1: CHECK-IN. Binary (Yes/No) questions only. Example: "Are you holding up okay?"
                    |Level 2: MICRO-ACTIVATION. Short, low-effort cognitive tasks. 
                    |   - counting (1-5), environmental checks ("color of car ahead"), or deep breathing.
                    |Level 3: PERSUASIVE ARGUMENT. Use data to suggest a break. Use the "Effective persuasion levers" above.
                    |Level 4: SAFETY COMMAND. Direct, firm commands. "Miguel, pull over. Exit in 2km."
                    |
                    |Vocal Rules:
                    |- Use only the next single response.
                    |- Be concise. 
                    |- Consult `get_driving_status` to determine the current level and follow its constraints.
                    |
                    |$interactionTypeContext
                    |
                    |Tone Rules: Conversational, professional, and safety-focused.
                """.trimMargin()
                
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
                        "interaction_history" to interactionHistory.joinToString("\n---\n"),
                        "greeting_seed" to greetingSeed,
                        "recent_interactions" to recentInteractions.joinToString("\n"),
                        // NEW: Calendar context for smarter questions
                        "has_calendar_events" to (events.isNotEmpty()),
                        "calendar_events_count" to events.size,
                        "calendar_events" to events.joinToString("; ") { ev ->
                            val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(ev.startMs)
                            val title = ev.title.ifBlank { "(untitled)" }
                            val loc = ev.location?.let { " @ $it" } ?: ""
                            "$time: $title$loc"
                        },
                        // NEW: Suggested topics for conversation
                        "suggested_topics" to topics,
                        // NEW: Timestamp for session awareness
                        "session_start_time" to System.currentTimeMillis()
                    ),
                    onAgentToolResponse = { toolName, toolCallId, toolType, isError ->
                        if (toolName.lowercase() == "end_call") {
                            Log.i(TAG, "📢 Agent tool end_call executed - forcing cleanup")
                            handleAgentEndTool("end_call")
                        }
                    },
                    onConnect = { conversationId ->
                        Log.i(TAG, "✓ Connected: $conversationId")
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
                        
                        val notifText = when {
                            statusStr.contains("connected") -> "🎙️ Listening..."
                            statusStr.contains("disconnected") -> "Voice ended"
                            statusStr.contains("error") -> "❌ Connection error"
                            else -> "Connecting..."
                        }
                        updateNotification(notifText)
                        broadcastStatus(statusStr)
                        
                        // Trigger cleanup when participant disconnects (only if we were running)
                        if (isRunning && statusStr.contains("disconnected")) {
                            Log.i(TAG, "🔌 Participant disconnected - triggering service cleanup (onDestroy)")
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
                        // Feed telemetry to RiskDecisionEngine
                        // Note: ElevanLabs SDK currently provides score, 
                        // but we mock latency/energy or use recent session stats.
                        _riskEngine?.updateVoiceTelemetry(
                            vocalEnergy = score, 
                            responseLatencyMs = 500f, 
                            vadScore = score
                        )
                    },
                    onMessage = { source, messageJson ->
                        Log.d(TAG, "Message from $source: ${messageJson.take(500)}")
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
                        
                        // Update response type in RiskEngine if driver spoke
                        if (source == "driver") {
                            _riskEngine?.updateDriverResponse(UserResponseType.COMPLIANT, 500f)
                        }
                    },
                    onUnhandledClientToolCall = { call ->
                        val callStr = call.toString()
                        Log.w(TAG, "Unhandled tool call: $callStr")
                        if (callStr.lowercase().contains("end_call")) {
                            Log.i(TAG, "📢 Detected end_call via unhandled tool call, forcing cleanup")
                            handleAgentEndTool("unhandled:$callStr")
                        }
                    },
                    clientTools = mapOf(
                        "end_call" to object : ClientTool {
                            override suspend fun execute(parameters: Map<String, Any>): ClientToolResult? {
                                Log.i(TAG, "📢 Agent requested end_call tool")
                                handleAgentEndTool("end_call")
                                return ClientToolResult.success("stopped")
                            }
                        },
                        "get_driving_status" to object : ClientTool {
                            override suspend fun execute(parameters: Map<String, Any>): ClientToolResult? {
                                Log.i(TAG, "📢 Agent requested get_driving_status")
                                val info = _riskEngine?.getRiskStateDebugInfo()
                                val response = JSONObject().apply {
                                    put("risk_state", info?.riskState?.name ?: "UNKNOWN")
                                    put("interaction_level", info?.interactionLevel ?: 0)
                                    put("drive_minutes", info?.driveMinutes ?: 0)
                                    put("vocal_energy", info?.vocalEnergy ?: 0f)
                                    put("response_latency_ms", info?.responseLatencyMs ?: 0f)
                                    put("road_type", info?.roadType?.name ?: "MIXED")
                                    put("circadian_window", info?.circadianWindow?.name ?: "NORMAL")
                                }
                                Log.d(TAG, "Tool response: $response")
                                return ClientToolResult.success(response.toString())
                            }
                        },
                        "set_sleep_hours" to object : ClientTool {
                            override suspend fun execute(parameters: Map<String, Any>): ClientToolResult? {
                                val hours = (parameters["hours"] as? Number)?.toInt() ?: 7
                                Log.i(TAG, "📢 Agent recorded sleep hours: $hours")
                                _riskEngine?.setSleepHours(hours)
                                return ClientToolResult.success("Sleep hours set to $hours")
                            }
                        },
                        "search_nearby_places" to object : ClientTool {
                            override suspend fun execute(parameters: Map<String, Any>): ClientToolResult? {
                                val query = parameters["query"] as? String ?: "rest area"
                                Log.i(TAG, "📢 Agent requested nearby places: $query")
                                
                                // Mock response for places search
                                val places = JSONArray()
                                
                                val place1 = JSONObject()
                                place1.put("name", "Starbucks")
                                place1.put("address", "123 Main St")
                                place1.put("distance", "0.8 miles")
                                place1.put("rating", 4.2)
                                places.put(place1)

                                val place2 = JSONObject()
                                place2.put("name", "Rest Area 42")
                                place2.put("address", "I-95 North, Mile 142")
                                place2.put("distance", "2.1 miles")
                                place2.put("rating", 3.8)
                                places.put(place2)
                                
                                val response = JSONObject()
                                response.put("results", places)
                                response.put("query", query)
                                response.put("count", places.length())
                                
                                Log.d(TAG, "Places response: $response")
                                return ClientToolResult.success(response.toString())
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
        stopSelf()  // Triggers onDestroy which handles outro + Spotify + cleanup
    }

    private fun handleAgentEndTool(toolName: String) {
        Log.i(TAG, "🧰 handleAgentEndTool: $toolName - stopping service to trigger Android lifecycle (onDestroy)")
        isRunning = false
        stopSelf()  // This triggers onDestroy() which plays outro + resumes Spotify
    }

    private fun endInteraction() {
        if (interactionEnded) {
            Log.d(TAG, "ℹ️ Interaction already ended, skipping")
            return
        }
        interactionEnded = true
        
        Log.i(TAG, "🔄 Ending interaction - cleaning up session and recording history")
        
        // Stop RiskDecisionEngine trip
        _riskEngine?.stopTrip()
        
        // Record interaction summary to history (for mid-drive interactions only)
        try {
            if (currentInteractionType != VoiceAgentTrigger.INTERACTION_TYPE_START_DRIVE && currentSessionMessages.isNotEmpty()) {
                val summary = currentSessionMessages.joinToString("\n")
                interactionHistory.add(summary)
                Log.d(TAG, "📝 Recorded interaction summary (total: ${interactionHistory.size})")
                
                // NEW: Complete the memory loop by generating and saving a summary to Snowflake
                serviceScope.launch {
                    try {
                        val sessionSummary = snowflakeManager.generateTripSummary(currentSessionMessages)
                        if (sessionSummary.isNotBlank()) {
                            snowflakeManager.updateLastTripSummary(currentDriverId, sessionSummary)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error generating/saving session summary: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error recording summary: ${e.message}")
        }
        
        // Clean up session (async)
        serviceScope.launch {
            try {
                session?.endSession()
                session = null
                Log.d(TAG, "✓ Session ended")
            } catch (e: Exception) {
                Log.e(TAG, "Error ending session: ${e.message}")
            }
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
        Log.i(TAG, "🔔 playIntroChime() STARTING - Playing chime_in.mp3...")
        try {
            // Release any existing player first
            introMediaPlayer?.release()
            
            // Create new MediaPlayer as class field to prevent GC
            introMediaPlayer = MediaPlayer()
            val mediaPlayer = introMediaPlayer!!
            
            val resId = resources.getIdentifier("chime_in", "raw", packageName)
            if (resId == 0) {
                Log.e(TAG, "❌ chime_in.mp3 not found in res/raw/")
                return
            }
            
            Log.d(TAG, "Loading chime_in.mp3 (resource ID: $resId)...")
            val afd = resources.openRawResourceFd(resId)
            mediaPlayer.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            
            mediaPlayer.setOnPreparedListener {
                Log.d(TAG, "🔔 MediaPlayer prepared, starting playback...")
                mediaPlayer.start()
            }
            
            mediaPlayer.setOnCompletionListener {
                Log.i(TAG, "✅ Chime playback completed")
                // Delay release to avoid "unhandled events" warning
                Handler(Looper.getMainLooper()).postDelayed({
                    mediaPlayer.release()
                    introMediaPlayer = null
                }, 100)
            }
            
            mediaPlayer.setOnErrorListener { mp, what, extra ->
                Log.e(TAG, "❌ MediaPlayer error: what=$what extra=$extra")
                Handler(Looper.getMainLooper()).postDelayed({
                    mp.release()
                    introMediaPlayer = null
                }, 100)
                true
            }
            
            Log.i(TAG, "🔔 Preparing to play chime_in.mp3...")
            mediaPlayer.prepareAsync()
            Log.i(TAG, "✅ Chime playback initiated")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception playing chime: ${e.message}", e)
            introMediaPlayer?.release()
            introMediaPlayer = null
        }
    }

    private fun playOutro() {
        Log.i(TAG, "🎧 playOutro() STARTING - Playing outro.mp3...")
        try {
            // Release any existing player first
            outroMediaPlayer?.release()
            
            // Create new MediaPlayer as class field to prevent GC
            outroMediaPlayer = MediaPlayer()
            val mediaPlayer = outroMediaPlayer!!
            
            val resId = resources.getIdentifier("outro", "raw", packageName)
            if (resId == 0) {
                Log.e(TAG, "❌ outro.mp3 not found in res/raw/")
                return
            }
            
            Log.d(TAG, "Loading outro.mp3 (resource ID: $resId)...")
            val afd = resources.openRawResourceFd(resId)
            mediaPlayer.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            
            mediaPlayer.setOnPreparedListener {
                Log.d(TAG, "🎧 Outro MediaPlayer prepared, starting playback...")
                mediaPlayer.start()
            }
            
            mediaPlayer.setOnCompletionListener {
                Log.i(TAG, "✅ Outro playback completed")
                // Delay release to avoid "unhandled events" warning
                Handler(Looper.getMainLooper()).postDelayed({
                    mediaPlayer.release()
                    outroMediaPlayer = null
                }, 100)
            }
            
            mediaPlayer.setOnErrorListener { mp, what, extra ->
                Log.e(TAG, "❌ Outro MediaPlayer error: what=$what extra=$extra")
                Handler(Looper.getMainLooper()).postDelayed({
                    mp.release()
                    outroMediaPlayer = null
                }, 100)
                true
            }
            
            Log.i(TAG, "🎧 Preparing to play outro.mp3...")
            mediaPlayer.prepareAsync()
            Log.i(TAG, "✅ Outro playback initiated")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception playing outro: ${e.message}", e)
            outroMediaPlayer?.release()
            outroMediaPlayer = null
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
            // ANDROID LIFECYCLE: Play outro and resume Spotify BEFORE ending interaction
            Log.i(TAG, "🎧 Playing outro (Android lifecycle cleanup)...")
            try {
                playOutro()
                Log.i(TAG, "✅ Outro playback initiated")
                Thread.sleep(500)  // Let outro start
            } catch (e: Exception) {
                Log.e(TAG, "Warning: Outro failed: ${e.message}")
            }
            
            // Resume Spotify in a coroutine
            Log.i(TAG, "🎵 Resuming Spotify (Android lifecycle cleanup)...")
            serviceScope.launch {
                try {
                    spotifyManager.resumeIfNeeded()
                    Log.i(TAG, "✅ Spotify resume completed")
                } catch (e: Exception) {
                    Log.e(TAG, "Error resuming Spotify: ${e.message}", e)
                }
            }
            Thread.sleep(500)  // Give it a moment
            
            // Now call endInteraction for ElevenLabs session cleanup
            Log.i(TAG, "Calling endInteraction() from onDestroy()...")
            endInteraction()
            Log.i(TAG, "✓ endInteraction() initiated")
            
            // Wait for cleanup coroutine to complete
            Log.i(TAG, "⏳ Waiting 1.5 seconds for cleanup coroutine to complete...")
            Thread.sleep(1500)
            Log.i(TAG, "✓ Cleanup coroutine should have completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in onDestroy: ${e.message}", e)
        } finally {
            Log.i(TAG, "🛑 Final cleanup: releasing resources...")
            
            // Unregister location receiver
            try {
                locationReceiver?.let { unregisterReceiver(it) }
                locationReceiver = null
                Log.d(TAG, "Location receiver unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering location receiver: ${e.message}")
            }

            // Release MediaPlayers
            try {
                introMediaPlayer?.release()
                introMediaPlayer = null
                outroMediaPlayer?.release()
                outroMediaPlayer = null
                Log.d(TAG, "MediaPlayers released")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing MediaPlayers: ${e.message}")
            }
            
            // Cancel scope
            serviceScope.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            super.onDestroy()
            Log.i(TAG, "✅ Service onDestroy() fully completed")
        }
    }

    override fun onBind(intent: Intent?): IBinder = VoiceAgentBinder()
}

