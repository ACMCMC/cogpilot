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
import java.io.File
import java.io.FileOutputStream

/**
 * TranscriptEntry: Tracks user/agent speech with timing for audio extraction
 */
data class TranscriptEntry(
    val speaker: String,  // "user" or "agent"
    val text: String,
    val startTimeMs: Long,  // When this utterance started
    val endTimeMs: Long     // When this utterance ended
)

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
        const val ACTION_INDICATORS_UPDATE = "fyi.acmc.cogpilot.voice.INDICATORS_UPDATE"
        const val EXTRA_ATTENTION_SCORE = "attention_score"
        const val EXTRA_VAD_SCORE = "vad_score"
        const val EXTRA_INTERACTION_LEVEL = "interaction_level"
        const val EXTRA_MODE = "mode"
        const val EXTRA_RISK_STATE = "risk_state"
        const val EXTRA_DRIVE_MINUTES = "drive_minutes"
        const val EXTRA_VOCAL_ENERGY = "vocal_energy"
        const val EXTRA_RESPONSE_LATENCY = "response_latency_ms"
        const val EXTRA_ROAD_TYPE = "road_type"
        const val EXTRA_CIRCADIAN_WINDOW = "circadian_window"
        const val EXTRA_DRIVER_PROFILE = "driver_profile"
        const val EXTRA_VOCAL_ENERGY_TREND = "vocal_energy_trend"
        const val EXTRA_LATENCY_TREND = "latency_trend"
        const val EXTRA_SPEED_VARIANCE = "speed_variance"

        private var _riskEngine: RiskDecisionEngine? = null
        val riskEngine: RiskDecisionEngine? get() = _riskEngine
        var riskEngineInstance: RiskDecisionEngine? = null
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var session: ConversationSession? = null
    private var isRunning = false
    private var interactionEnded = false  // Guard against double cleanup
    private val snowflakeManager = SnowflakeManager()
    private val calendarContext = CalendarContextProvider(this)
    private val googleMapsManager by lazy { GoogleMapsManager(BuildConfig.GOOGLE_MAPS_API_KEY) }
    
    // Track interaction history during the driving session for context in future interactions
    private val interactionHistory = mutableListOf<String>()
    // Track messages in current session for creating summary on end
    private val currentSessionMessages = mutableListOf<String>()
    // Track transcript with timing for audio extraction
    private val timedTranscript = mutableListOf<TranscriptEntry>()

    // ONNX Real-time Loop
    private var microphoneAnalyzer: MicrophoneAnalyzer? = null

    // hold context updates until connected
    private var pendingTopics: String? = null
    private var pendingEventSummary: String? = null
    private var pendingStartMessage: String? = null
    
    // Keep MediaPlayer references to prevent garbage collection during playback
    private var introMediaPlayer: MediaPlayer? = null
    private var outroMediaPlayer: MediaPlayer? = null
    
    // Current indicator values for broadcasting to UI
    private var currentAttentionScore = 0.5f
    private var currentVadScore = 0f
    private var currentInteractionLevel = 0
    private var currentMode = "idle"
    private var currentRiskState = "normal"
    private var currentDriveMinutes = 0
    private var currentVocalEnergy = 0f
    private var currentResponseLatency = 0f
    private var currentRoadType = "mixed"
    private var currentCircadianWindow = "normal"
    private var currentDriverProfile = "unknown"
    private var currentVocalEnergyTrend = 0f
    private var currentLatencyTrend = 0f
    private var currentSpeedVariance = 0f
    private var sessionConversationId: String = ""  // Store for audio download

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
        
        // Expose public static access to the engine for tests or other components
        riskEngineInstance = _riskEngine

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

    // Transcript Timing State
    private var lastAgentMessageTime: Long = 0L
    private var userSpeechStartTime: Long = 0L  // When user STARTS speaking (first transcript)
    private var userSpeechEndTime: Long = 0L    // When user FINISHES speaking
    private var latencyAtSpeechStart: Float = 0f // Latency from agent done → user starts
    private val latencyHistory = mutableListOf<Float>() // Track rolling average
    
    // Calculate rolling average latency
    private fun getAverageLatency(): Float {
        return if (latencyHistory.isNotEmpty()) {
            latencyHistory.average().toFloat()
        } else 500f
    }

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
        timedTranscript.clear()  // Reset transcript with timing

        // Record interaction to trigger suppression logic in RiskEngine
        _riskEngine?.recordInteraction()

        // Start RiskDecisionEngine trip
        _riskEngine?.startTrip(currentDriverId, 7) // Hardcoded 7h sleep for now

        // Start MicrophoneAnalyzer for ONNX telemetry
        if (microphoneAnalyzer == null) {
            _riskEngine?.let { engine ->
                microphoneAnalyzer = MicrophoneAnalyzer(engine)
            }
        }
        microphoneAnalyzer?.start()

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
                    |TOOL USAGE CUES:
                    |- Use `get_driving_status` during the conversation to silently check their telemetry if they sound tired.
                    |- Use `search_past_conversations` dynamically if they refer to something you talked about previously, or to bridge topics organically.
                    |- Use `search_nearby_places` if they complain about being hungry, tired, or needing gas.
                    |- Use `set_sleep_hours` immediately after asking the driver how much they slept in the PRE_TRIP check-in.
                    |- Use `update_agentic_attention_score` to explicitly log your AI-judgment of their alertness (0.0=asleep, 1.0=wide awake) if you notice their mood or responsiveness change.
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

                // 2. Play intro chime immediately...
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
                        sessionConversationId = conversationId  // Save for audio download later
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
                        currentRiskState = statusStr
                        broadcastIndicators()
                        
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
                        val modeStr = mode.toString().lowercase()
                        currentMode = modeStr
                        broadcastIndicators()
                        
                        // When user stops speaking (mode changes away from listening)
                        if (!modeStr.contains("listening") && userSpeechStartTime > 0L) {
                            // User finished speaking - reset for next utterance
                            val totalSpeechTime = (userSpeechEndTime - userSpeechStartTime).toFloat() / 1000f
                            Log.i(TAG, "✓ User finished speaking | Total duration: ${totalSpeechTime}s | Response latency: ${latencyAtSpeechStart.toInt()}ms")
                            userSpeechStartTime = 0L
                            userSpeechEndTime = 0L
                        }
                        
                        val notifText = when {
                            modeStr.contains("speaking") -> "🤖 Agent speaking..."
                            modeStr.contains("listening") -> "🎙️ Listening..."
                            else -> modeStr
                        }
                        updateNotification(notifText)
                    },
                    onVadScore = { score ->
                        // voice activity detection
                        if (score > 0.5f) {
                            Log.d(TAG, "Voice detected: $score")
                        }
                        currentVadScore = score
                        broadcastIndicators()
                        // Feed telemetry to RiskDecisionEngine with actual latency
                        _riskEngine?.updateVoiceTelemetry(
                            vocalEnergy = score, 
                            responseLatencyMs = getAverageLatency(),
                            vadScore = score
                        )
                    },
                    onMessage = { source, messageJson ->
                        Log.d(TAG, "Message from $source: ${messageJson.take(500)}")
                        val now = System.currentTimeMillis()
                        
                        // broadcast agent responses to UI and track for history
                        if (source == "agent") {
                            try {
                                val msg = JSONObject(messageJson).optString("message", "")
                                if (msg.isNotEmpty()) {
                                    lastAgentMessageTime = now
                                    // Record agent message for interaction summary
                                    currentSessionMessages.add("Agent: $msg")
                                    
                                    // Track in timed transcript (for audio extraction later)
                                    timedTranscript.add(TranscriptEntry(
                                        speaker = "agent",
                                        text = msg,
                                        startTimeMs = now,
                                        endTimeMs = now + 3000  // Estimate 3s for agent speech
                                    ))
                                    
                                    val intent = Intent(ACTION_AI_LOG).apply {
                                        putExtra(EXTRA_AI_MSG, "🤖 Agent: $msg")
                                    }
                                    sendBroadcast(intent)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to parse agent message: ${e.message}")
                            }
                        } else if (source == "user" || source == "driver") {
                            // Also track driver responses & timings (user_transcript event from ElevenLabs)
                            try {
                                val msg = JSONObject(messageJson).optString("message", "")
                                if (msg.isNotEmpty()) {
                                    val now = System.currentTimeMillis()
                                    
                                    // On FIRST user transcript: capture latency (time from agent done → user starts)
                                    if (userSpeechStartTime == 0L) {
                                        userSpeechStartTime = now
                                        
                                        // Calculate latency: time from last agent message to user starts speaking
                                        if (lastAgentMessageTime > 0) {
                                            latencyAtSpeechStart = (userSpeechStartTime - lastAgentMessageTime).toFloat()
                                            latencyHistory.add(latencyAtSpeechStart)
                                            
                                            // Keep only last 10 measurements for rolling average
                                            if (latencyHistory.size > 10) {
                                                latencyHistory.removeAt(0)
                                            }
                                            
                                            Log.i(TAG, "📊 User started speaking | Latency: ${latencyAtSpeechStart.toInt()}ms | Avg: ${getAverageLatency().toInt()}ms")
                                        }
                                    }
                                    
                                    // Update end time on every message (tracks until speech ends)
                                    userSpeechEndTime = now
                                    
                                    currentSessionMessages.add("Driver: $msg")
                                    
                                    // Track user transcript with timing (for audio extraction)
                                    timedTranscript.add(TranscriptEntry(
                                        speaker = "user",
                                        text = msg,
                                        startTimeMs = userSpeechStartTime,
                                        endTimeMs = now
                                    ))
                                    
                                    // Calculate Words Per Second
                                    val words = msg.split(Regex("\\s+")).filter { it.isNotBlank() }.size
                                    val speechDurationSeconds = (now - userSpeechStartTime).coerceAtLeast(100L) / 1000f
                                    val wps = if (speechDurationSeconds > 0) words / speechDurationSeconds else 2.5f

                                    Log.d(TAG, "⏱️ Transcript Update -> WPS: ${String.format("%.2f", wps)} | Duration so far: ${speechDurationSeconds.toInt()}s")

                                    // Update RiskEngine with average latency (not individual)
                                    val avgLatency = getAverageLatency()
                                    _riskEngine?.updateVoiceTelemetry(
                                        vocalEnergy = 0.8f,
                                        responseLatencyMs = avgLatency,
                                        vadScore = 0.8f,
                                        wordsPerSecond = wps
                                    )
                                    
                                    currentResponseLatency = avgLatency
                                    broadcastIndicators()
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to parse driver message: ${e.message}")
                            }
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
                        "update_agentic_attention_score" to object : ClientTool {
                            override suspend fun execute(parameters: Map<String, Any>): ClientToolResult? {
                                val score = (parameters["param_score"] as? Number ?: parameters["score"] as? Number)?.toFloat() ?: 0.5f
                                Log.i(TAG, "📢 Agent assessed driver attention: $score")
                                currentAttentionScore = score
                                broadcastIndicators()
                                _riskEngine?.updateAgenticAttention(score)
                                return ClientToolResult.success("Attention score updated to $score")
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
                        },
                        "search_past_conversations" to object : ClientTool {
                            override suspend fun execute(parameters: Map<String, Any>): ClientToolResult? {
                                val query = parameters["param_query"] as? String ?: parameters["query"] as? String ?: return ClientToolResult.success("No query provided")
                                Log.i(TAG, "📢 Agent searching past memory: $query")
                                
                                val pastContext = snowflakeManager.searchPastConversations(currentDriverId, query)
                                return ClientToolResult.success(pastContext)
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
        isRunning = false

        // Stop MicrophoneAnalyzer
        microphoneAnalyzer?.stop()
        microphoneAnalyzer = null
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

            // 2. Resume Previous Media
            try {
                Log.i(TAG, "🎵 Requesting system media resume via MEDIA_PLAY intent")
                val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                val eventDown = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
                audioManager.dispatchMediaKeyEvent(eventDown)
                val eventUp = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
                audioManager.dispatchMediaKeyEvent(eventUp)
            } catch (e: Exception) {
                Log.e(TAG, "Cleanup: Media resume failed: ${e.message}")
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
        
        // Stop RiskDecisionEngine trip and record interaction end for 30s cooldown
        _riskEngine?.stopTrip()
        _riskEngine?.recordInteractionEnd()
        
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
                        
                        // NEW: Store raw conversational text for semantic vector search memory
                        if (timedTranscript.isNotEmpty()) {
                            val rawTranscript = timedTranscript.joinToString("\n") { "${it.speaker}: ${it.text}" }
                            Log.i(TAG, "🧠 Storing conversation transcript for semantic memory...")
                            snowflakeManager.storeConversationEmbedding(currentDriverId, rawTranscript)
                        }
                        
                        // Download and analyze conversation audio
                        if (sessionConversationId.isNotEmpty() && timedTranscript.isNotEmpty()) {
                            Log.i(TAG, "🎙️ Downloading conversation audio for analysis...")
                            downloadAndAnalyzeAudio()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error saving session memory/summary: ${e.message}")
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

    private fun broadcastIndicators() {
        // Fetch all current values from RiskEngine
        val debugInfo = _riskEngine?.getRiskStateDebugInfo()
        if (debugInfo != null) {
            currentDriveMinutes = debugInfo.driveMinutes
            currentVocalEnergy = debugInfo.vocalEnergy
            currentResponseLatency = debugInfo.responseLatencyMs
            val roadType = _riskEngine?.getRoadType()?.name ?: "mixed"
        val circadian = _riskEngine?.circadianWindow?.name ?: "normal"
        val profile = _riskEngine?.getDriverId() ?: "unknown"
        val vocalTrend = _riskEngine?.currentVocalEnergyTrend ?: 0f
        val latencyTrend = _riskEngine?.currentLatencyTrend ?: 0f
        val speedVar = _riskEngine?.getSpeedVariance() ?: 0f
        val onnxScore = _riskEngine?.getLastOnnxRiskScore() ?: 0f

        val intent = Intent(ACTION_INDICATORS_UPDATE).apply {
            putExtra(EXTRA_ATTENTION_SCORE, currentAttentionScore) // Assuming 'attention' was meant to be currentAttentionScore
            putExtra(EXTRA_VAD_SCORE, currentVadScore)
            putExtra(EXTRA_MODE, currentMode)
            putExtra(EXTRA_RISK_STATE, currentRiskState)
            putExtra(EXTRA_INTERACTION_LEVEL, currentInteractionLevel)
            putExtra(EXTRA_DRIVE_MINUTES, _riskEngine?.driveMinutes ?: 0)
            putExtra(EXTRA_VOCAL_ENERGY, currentVocalEnergy)
            putExtra(EXTRA_RESPONSE_LATENCY, currentResponseLatency)
            putExtra(EXTRA_ROAD_TYPE, roadType)
            putExtra(EXTRA_CIRCADIAN_WINDOW, circadian)
            putExtra(EXTRA_DRIVER_PROFILE, profile)
            putExtra(EXTRA_VOCAL_ENERGY_TREND, vocalTrend)
            putExtra(EXTRA_LATENCY_TREND, latencyTrend)
            putExtra(EXTRA_SPEED_VARIANCE, speedVar)
            putExtra("onnx_risk_score", onnxScore)
        }
    setPackage(packageName)
        }
        sendBroadcast(intent)
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

    /**
     * Download conversation audio and extract user portions for analysis
     */
    private suspend fun downloadAndAnalyzeAudio() {
        try {
            Log.i(TAG, "🎙️ Starting audio download and analysis for conversation: $sessionConversationId")
            
            val audioFile = downloadConversationAudio()
            if (audioFile != null && audioFile.exists()) {
                Log.i(TAG, "✓ Audio downloaded: ${audioFile.absolutePath}")
                
                // Extract user audio segments based on transcript timing
                val userAudioSegments = extractUserAudioSegments(audioFile)
                
                if (userAudioSegments.isNotEmpty()) {
                    Log.i(TAG, "✓ Extracted ${userAudioSegments.size} user audio segment(s)")
                    
                    // Analyze user audio (stub for now)
                    analyzeUserSpeech(userAudioSegments)
                } else {
                    Log.w(TAG, "⚠️ No user audio segments extracted")
                }
            } else {
                Log.w(TAG, "⚠️ Failed to download audio file")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in downloadAndAnalyzeAudio: ${e.message}", e)
        }
    }

    /**
     * Download conversation audio from ElevenLabs
     * Returns File if successful, null otherwise
     */
    private suspend fun downloadConversationAudio(): File? = withContext(Dispatchers.IO) {
        try {
            // NOTE: ElevenLabs ConversationClient provides session audio
            // In real implementation, you would call session?.downloadAudio() or similar
            // For now, this is a placeholder for the download mechanism
            
            Log.d(TAG, "📥 Downloading audio for conversation: $sessionConversationId")
            
            // Create audio directory
            val audioDir = File(getExternalFilesDir(null), "conversation_audio")
            if (!audioDir.exists()) {
                audioDir.mkdirs()
            }
            
            // Audio file path
            val audioFile = File(audioDir, "conversation_${sessionConversationId}_${System.currentTimeMillis()}.wav")
            
            // TODO: Replace with actual ElevenLabs SDK audio download call
            // Example (when available):
            // val audioBytes = session?.getAudioBytes() ?: return@withContext null
            // audioFile.writeBytes(audioBytes)
            
            Log.i(TAG, "Audio file ready: ${audioFile.absolutePath}")
            return@withContext audioFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download audio: ${e.message}")
            return@withContext null
        }
    }

    /**
     * Extract user audio segments from mixed audio based on transcript timing
     * Uses timedTranscript to identify where user was speaking
     */
    private suspend fun extractUserAudioSegments(audioFile: File): List<File> = withContext(Dispatchers.IO) {
        val userSegments = mutableListOf<File>()
        
        try {
            // Filter transcript to get only user entries
            val userSpeechEntries = timedTranscript.filter { it.speaker == "user" }
            
            if (userSpeechEntries.isEmpty()) {
                Log.w(TAG, "No user speech entries found in transcript")
                return@withContext userSegments
            }
            
            Log.i(TAG, "📍 Found ${userSpeechEntries.size} user speech segments to extract")
            
            // Create extracts directory
            val extractsDir = File(getExternalFilesDir(null), "user_audio_extracts")
            if (!extractsDir.exists()) {
                extractsDir.mkdirs()
            }
            
            // For each user speech segment, create a file marker with timing info
            userSpeechEntries.forEachIndexed { index, entry ->
                val startSec = entry.startTimeMs / 1000.0
                val endSec = entry.endTimeMs / 1000.0
                val duration = endSec - startSec
                
                Log.d(TAG, "#${index + 1} User speech: $startSec - $endSec (${duration.toInt()}s) | Text: ${entry.text.take(50)}")
                
                // Create marker file with timing and transcript
                val extractFile = File(extractsDir, "user_${index}_${entry.startTimeMs}-${entry.endTimeMs}.txt")
                extractFile.writeText("""
                    Speaker: ${entry.speaker.uppercase()}
                    Start Time: $startSec seconds
                    End Time: $endSec seconds
                    Duration: $duration seconds
                    Text: ${entry.text}
                """.trimIndent())
                
                userSegments.add(extractFile)
            }
            
            Log.i(TAG, "✓ Created ${userSegments.size} user audio segment markers")
            return@withContext userSegments
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract user audio segments: ${e.message}")
            return@withContext userSegments
        }
    }

    /**
     * Analyze user speech for patterns, confidence, hesitation, etc.
     * STUB: Placeholder for analysis logic
     */
    private suspend fun analyzeUserSpeech(userAudioSegments: List<File>) = withContext(Dispatchers.Default) {
        try {
            Log.i(TAG, "🔍 Analyzing ${userAudioSegments.size} user speech segment(s)...")
            
            // TODO: Implement actual speech analysis
            // - Speech rate / pacing
            // - Confidence / hesitation detection
            // - Emotional tone
            // - Key phrases / sentiment
            
            userAudioSegments.forEachIndexed { index, file ->
                Log.d(TAG, "Analyzing segment #${index + 1}: ${file.name}")
                
                // STUB: Would call actual analysis here
                // val analysis = performSpeechAnalysis(file)
            }
            
            Log.i(TAG, "✓ User speech analysis completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing user speech: ${e.message}")
        }
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

