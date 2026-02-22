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
import kotlinx.coroutines.withContext
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
    private val googleMapsManager by lazy { GoogleMapsManager(BuildConfig.GOOGLE_MAPS_API_KEY) }
    
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

        // Verify credentials for headless startup compliance
        verifyCredentials()
        
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

    private fun verifyCredentials() {
        if (BuildConfig.ELEVENLABS_API_KEY.isEmpty() || BuildConfig.ELEVENLABS_AGENT_ID.isEmpty()) {
            Log.e(TAG, "❌ PERSISTENCE ERROR: ElevenLabs credentials missing. Background agent will not start.")
        }
        if (BuildConfig.SNOWFLAKE_ACCOUNT.isEmpty() || BuildConfig.SNOWFLAKE_PAT_TOKEN.isEmpty()) {
            Log.w(TAG, "⚠️ PERSISTENCE WARNING: Snowflake credentials missing. Driving logs will not be persisted.")
        }
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

        // Record interaction to trigger suppression logic in RiskEngine
        _riskEngine?.recordInteraction()

        // Start RiskDecisionEngine trip
        _riskEngine?.startTrip(currentDriverId, 7) // Hardcoded 7h sleep for now

        serviceScope.launch {
            try {
                // 1. Gather context first while music is still playing full volume
                val greetingSeed = kotlin.random.Random.nextInt(1000, 9999)
                
                // Robust data gathering with fallbacks
                val recentInteractions = try { snowflakeManager.getRecentInteractionSummaries(currentDriverId, limit = 3) } catch (e: Exception) { emptyList() }
                val lastTripSummary = try { snowflakeManager.getLastTripSummary(currentDriverId) } catch (e: Exception) { "" }
                val startMsg = try { snowflakeManager.generateStartMessage(currentDriverId, greetingSeed, recentInteractions) } catch (e: Exception) { "" }
                val topics = try { snowflakeManager.generateConversationTopics(driverId = 1) } catch (e: Exception) { "" }
                val profile = try { snowflakeManager.getUserProfileById(currentDriverId) } catch (e: Exception) { emptyMap() }
                val events = try { calendarContext.getUpcomingEvents(limit = 5, windowMinutes = 240) } catch (e: Exception) { emptyList() }
                
                if (events.isNotEmpty()) {
                    try { snowflakeManager.insertCalendarEvents(driverId = 1, events = events) } catch (e: Exception) { Log.w(TAG, "Failed to insert events") }
                }

                val info = _riskEngine?.getRiskStateDebugInfo()
                val profileDetails = _riskEngine?.getDriverProfileDetails() ?: emptyMap()
                val driverName = profileDetails["name"] ?: profile["name"] ?: currentDriverId
                
                // Build rich calendar context
                val calendarContextStr = if (events.isNotEmpty()) {
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
Keep it conversational and friendly to build rapport.$calendarContextStr"""
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
$historyContext$calendarContextStr"""
                    }
                }
                
                val roadType = _riskEngine?.getRoadType() ?: RoadType.MIXED
                val traffic = _riskEngine?.getTrafficCondition() ?: TrafficCondition.MODERATE
                val currentTimeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
                val riskStateStr = info?.riskState?.toString() ?: "STABLE"
                val interactionLevel = info?.interactionLevel ?: 0

                val systemContext = """
                    |YOU ARE: The cognitive co-pilot for $driverName.
                    |
                    |ENVIRONMENTAL CONTEXT:
                    |- Current Time: $currentTimeStr
                    |- Interaction Type: $currentInteractionType
                    |
                    |DRIVER PROFILE:
                    |- Name: $driverName
                    |- Interests: ${profile["interests"]}
                    |- Complexity Level: ${profile["complexity"]}
                    |- Last trip memory: ${if (lastTripSummary.isNotBlank()) lastTripSummary else "No previous trip data available."}
                    |- Known risk triggers: ${profileDetails["triggers"] ?: "None"}
                    |- Effective persuasion levers: ${profileDetails["levers"] ?: "None"}
                    |- Rejection pattern: ${profileDetails["rejection_pattern"] ?: "None"}
                    |- Communication boundary: ${profileDetails["boundary"] ?: "None"}
                    |
                    |CURRENT DRIVING CONTEXT:
                    |- Road Type: $roadType
                    |- Traffic Condition: $traffic
                    |- Destination Context: ${if (calendarContextStr.isNotBlank()) "Heading towards calendar events" else "General drive"}
                    |
                    |CURRENT TELEMETRY (TRENDS):
                    |- Attention State: $riskStateStr (Interaction Level $interactionLevel)
                    |- Vocal Energy: ${String.format("%.2f", info?.vocalEnergyTrend ?: 0.8f)} (Baseline: 0.8)
                    |- Response Latency: ${info?.latencyTrend ?: 500}ms (Delayed if > 1200ms)
                    |- Driving Time: ${info?.driveMinutes ?: 0} min
                    |
                    |MISSION: Monitor $driverName's drowsiness and intervene with cognitive stimuli before they fall asleep. 
                    |Your presence is essential for their safety on this $roadType drive.
                    |
                    |CAPABILITY CONSTRAINTS (STRICT):
                    |- NO NAVIGATION: You CANNOT navigate the user, change routes, or provide turn-by-turn directions.
                    |- NO VEHICLE CONTROL: You CANNOT control the car, windows, climate, or any vehicle systems.
                    |- INFORMATIONAL ONLY: Search results (nearby places) are for driver AWARENESS only. 
                    |  Frame them as: "I found a [Place] about [X] miles away," NEVER "I'll navigate you to [Place]."
                    |
                    |INTERACTION LEVELS (MANDATORY CONSTRAINTS):
                    |Level 0: ACTIVE SILENCE. Do not speak unless spoken to.
                    |Level 1: CHECK-IN. Binary (Yes/No) questions only. Example: "Are you holding up okay, $driverName?"
                    |Level 2: MICRO-ACTIVATION. Short, low-effort cognitive tasks. 
                    |   - counting (1-5), environmental checks ("color of car ahead"), or deep breathing.
                    |Level 3: PERSUASIVE ARGUMENT. Use data to suggest a break. Use the "Effective persuasion levers" above.
                    |Level 4: SAFETY COMMAND. Direct, firm commands. "$driverName, pull over. Exit in 2km."
                    |
                    |Vocal Rules:
                    |- Use only the next single response.
                    |- Be concise. 
                    |- Consult `get_driving_status` to determine the current level and follow its constraints.
                    |
                    |$interactionTypeContext
                    |
                    |Tone Rules: Conversational, professional, and safety-focused. Always address the driver as $driverName.
                """.trimMargin()
                
                Log.d(TAG, "System context: $systemContext")
                
                // ALWAYS have an initial message, especially for START_DRIVE
                val initialMessage = if (startMsg.isNotBlank()) startMsg else generateInitialGreeting(driverName)
                Log.i(TAG, "✅ Initial message determined: $initialMessage")

                // 2. Now that we're ready to talk, fade out Spotify (snappier 2s fade)
                Log.i(TAG, "🎵 Fading out Spotify music (2000ms)...")
                spotifyManager.fadeOutAndPause(durationMs = 2000L)
                
                // 3. Play intro chime immediately after fadeOutAndPause returns (it handles its own settling)
                Log.i(TAG, "🔔 Music paused, now playing intro chime...")
                try {
                    playIntroChime()
                    Log.i(TAG, "✅ 🔔 Chime playback method completed successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Chime playback failed: ${e.message}", e)
                }
                
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
                        "driver_name" to driverName,
                        "current_time" to currentTimeStr,
                        "interaction_type" to currentInteractionType,
                        "risk_state" to riskStateStr,
                        "attention_level" to interactionLevel,
                        "road_type" to roadType.toString(),
                        "traffic_condition" to traffic.toString(),
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
                                val hours = (parameters["param_hours"] as? Number ?: parameters["hours"] as? Number)?.toInt() ?: 7
                                Log.i(TAG, "📢 Agent recorded sleep hours: $hours")
                                _riskEngine?.setSleepHours(hours)
                                return ClientToolResult.success("Sleep hours set to $hours")
                            }
                        },
                        "search_nearby_places" to object : ClientTool {
                            override suspend fun execute(parameters: Map<String, Any>): ClientToolResult? {
                                val query = parameters["param_query"] as? String ?: parameters["query"] as? String ?: "rest area"
                                Log.i(TAG, "📢 Agent requested nearby places: $query")
                                
                                val location = _riskEngine?.getLastLocation()
                                val lat = location?.latitude ?: 37.7749
                                val lng = location?.longitude ?: -122.4194
                                
                                val places = googleMapsManager.searchPlaces(query, lat, lng)
                                
                                val response = JSONObject()
                                response.put("results", places)
                                response.put("query", query)
                                response.put("count", places.length())
                                
                                Log.d(TAG, "Real Places response: $response")
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
        Log.i(TAG, "🛑 Stopping voice session via performCleanupAndStop (user action)...")
        performCleanupAndStop()
    }

    private fun handleAgentEndTool(toolName: String) {
        Log.i(TAG, "🧰 handleAgentEndTool: $toolName - initiating graceful cleanup")
        performCleanupAndStop()
    }

    private fun performCleanupAndStop() {
        if (!isRunning && interactionEnded) return
        isRunning = false
        
        serviceScope.launch {
            Log.i(TAG, "🏗️ Graceful cleanup starting...")
            
            // 1. Play outro
            try {
                playOutro()
                kotlinx.coroutines.delay(1800) 
            } catch (e: Exception) {
                Log.e(TAG, "Cleanup: Outro failed: ${e.message}")
            }

            // 2. Resume Spotify
            try {
                spotifyManager.resumeIfNeeded()
            } catch (e: Exception) {
                Log.e(TAG, "Cleanup: Spotify resume failed: ${e.message}")
            }

            // 3. End interaction (includes Snowflake summary + session close)
            endInteraction()
            
            // 4. Final wait for async tasks
            kotlinx.coroutines.delay(1000)
            
            // 5. Finally stop the service
            Log.i(TAG, "🏁 Graceful cleanup finished. Stopping service.")
            withContext(Dispatchers.Main) {
                stopSelf()
            }
        }
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

    private fun generateInitialGreeting(name: String): String {
        val driverName = if (name.equals("driver", true)) "friend" else name
        val greeting = when (currentInteractionType) {
            VoiceAgentTrigger.INTERACTION_TYPE_START_DRIVE -> {
                "Hey $driverName! Ready to hit the road? Let's make this a smooth drive ahead."
            }
            else -> {
                "Hey $driverName, just checking in. How are you doing?"
            }
        }
        Log.d(TAG, "Generated fallback initial greeting: $greeting")
        return greeting
    }

    override fun onDestroy() {
        Log.i(TAG, "🛑 SERVICE onDestroy() - Final resource release")
        
        // Ensure interaction is marked as ended if it wasn't already
        // (but don't block for long async tasks here)
        if (!interactionEnded) {
            _riskEngine?.stopTrip()
            interactionEnded = true
        }
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

    override fun onBind(intent: Intent?): IBinder = VoiceAgentBinder()
}

