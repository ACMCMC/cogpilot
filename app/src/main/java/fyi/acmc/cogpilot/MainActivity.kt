package fyi.acmc.cogpilot

import android.Manifest
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable

/**
 * MainActivity: Modern Material Design 3 UI for CogPilot.
 * Detects Android Automotive driving mode - fully interactive while driving.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val ACTION_TRIGGER_VOICE = "fyi.acmc.cogpilot.action.TRIGGER_VOICE"
    }

    private val locationCapture by lazy { LocationCapture(this) }
    private val snowflakeManager = SnowflakeManager()
    private val riskScorer = RiskScorer()
    private val handler = Handler(Looper.getMainLooper())
    private var isDrivingMode = false
    private var voiceActive = false
    private var lastAutoTriggerTime = 0L
    private var lastRiskScore = 0f
    private var currentUserId: String = "aldan_creo"
    private var sustainedSpeedStartTime = 0L

    // Live driving state — updated by location callback, passed to agent on trigger
    private var lastRoadContext: RoadContext? = null
    private var lastSpeedMph: Float = 0f
    private var carSpeedMph: Float? = null
    private var tripStartMs: Long = System.currentTimeMillis()  // reset when session starts

    private lateinit var uiManager: UIManager
    
    private var voiceStatusReceiver: android.content.BroadcastReceiver? = null
    private var aiLogReceiver: android.content.BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("CogPilot", "MainActivity.onCreate")
        
        // suppress noisy third-party library logs
        java.util.logging.Logger.getLogger("net.snowflake").level = java.util.logging.Level.WARNING
        java.util.logging.Logger.getLogger("SnowflakeSqlApi").level = java.util.logging.Level.WARNING
        
        detectDrivingMode()
        Log.i("CogPilot", "🚗 MainActivity created | Driving Mode: $isDrivingMode")
        
        requestRequiredPermissions()

        uiManager = UIManager(this, currentUserId,
            { newId: String ->
                currentUserId = newId
                Log.i("CogPilot","Switched profile to $newId")
                // refresh profile info display
                refreshProfileDisplay()
            },
            { onVoiceToggle() },
            { onStartDrive() }
        )
        val rootView = uiManager.createUI()
        setContentView(rootView)
        
        // Ensure Spotify is authorized while in the foreground
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            try {
                SpotifyManager(this@MainActivity).authorize()
            } catch (e: Exception) {
                Log.e("CogPilot", "Spotify auth trigger failed: ${e.message}")
            }
        }
        // Make content respect the status bar and navigation bar heights.
        // The scroll view background fills edge-to-edge; the padding keeps content visible.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        
        if (isDrivingMode) {
            Log.i("CogPilot", "✅ DRIVING MODE DETECTED - Full interactive support enabled")
        }

        lifecycleScope.launch {
            val connected = snowflakeManager.initConnection()
            if (connected) {
                Log.i("CogPilot", "✓ Snowflake Connected")
                uiManager.setConnectionStatus("✓ Connected", "#4CAF50")
                // display initial profile
                refreshProfileDisplay()
                // location capture starts after permissions are confirmed
                updateRiskPeriodically()
            } else {
                Log.e("CogPilot", "✗ Failed to connect to Snowflake")
                uiManager.setConnectionStatus("✗ Connection Failed", "#FF6B6B")
            }
        }

        // register AI log receiver
        aiLogReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                val msg = intent.getStringExtra(VoiceAgentService.EXTRA_AI_MSG) ?: return
                uiManager.logAiInput(msg)
            }
        }
        val filter = android.content.IntentFilter("fyi.acmc.cogpilot.voice.AI_LOG")
        registerReceiver(aiLogReceiver, filter, android.content.Context.RECEIVER_EXPORTED)
    }

    override fun onStart() {
        super.onStart()
        VoiceSessionController.onTrigger = {
            onVoiceTrigger()
        }
        
        // Register receiver to listen for voice agent status changes
        if (voiceStatusReceiver == null) {
            voiceStatusReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == VoiceAgentService.ACTION_STATUS_CHANGE) {
                        val status = intent.getStringExtra(VoiceAgentService.EXTRA_STATUS) ?: return
                        Log.d("CogPilot", "Voice status changed: $status")
                        when (status) {
                            "connected" -> {
                                voiceActive = true
                                uiManager.setVoiceState(true)
                            }
                            "disconnected" -> {
                                voiceActive = false
                                uiManager.setVoiceState(false)
                            }
                            "error" -> {
                                voiceActive = false
                                uiManager.setVoiceState(false)
                            }
                        }
                    }
                }
            }
            val filter = android.content.IntentFilter(VoiceAgentService.ACTION_STATUS_CHANGE)
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                voiceStatusReceiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED
            )
        }
    }

    override fun onStop() {
        super.onStop()
        VoiceSessionController.onTrigger = null
        
        // Unregister the broadcast receiver
        if (voiceStatusReceiver != null) {
            try {
                unregisterReceiver(voiceStatusReceiver)
            } catch (e: Exception) {
                Log.e("CogPilot", "Error unregistering receiver: ${e.message}")
            }
            voiceStatusReceiver = null
        }
    }

    private fun onVoiceToggle() {
        voiceActive = !voiceActive
        if (voiceActive) {
            Log.i("CogPilot", "🎙️ Voice session starting via button")
            uiManager.setVoiceState(true)
            val rc = lastRoadContext
            val activeSpeedMph = carSpeedMph ?: lastSpeedMph
            VoiceAgentTrigger.start(
                this,
                driverId      = currentUserId,
                source        = "button_click",
                interactionType = VoiceAgentTrigger.INTERACTION_TYPE_CHECK_IN,
                speedMph      = activeSpeedMph.takeIf { it > 0 },
                roadTypes     = rc?.types?.joinToString(",")?.ifBlank { null },
                trafficRatio  = rc?.trafficRatio,
                tripStartMs   = tripStartMs
            )
        } else {
            Log.i("CogPilot", "🛑 Voice session stopping")
            uiManager.setVoiceState(false)
            VoiceAgentTrigger.stop(this)
        }
    }

    private fun onStartDrive() {
        Log.i("CogPilot", "🚗 Start Drive interaction triggered")
        uiManager.setVoiceState(true)
        val rc = lastRoadContext
        val activeSpeedMph = carSpeedMph ?: lastSpeedMph
        VoiceAgentTrigger.start(
            this,
            driverId = currentUserId,
            source = "start_drive_button",
            interactionType = VoiceAgentTrigger.INTERACTION_TYPE_START_DRIVE,
            speedMph = activeSpeedMph.takeIf { it > 0 },
            roadTypes = rc?.types?.joinToString(",")?.ifBlank { null },
            trafficRatio = rc?.trafficRatio,
            tripStartMs = tripStartMs
        )
        voiceActive = true
    }

    private fun onVoiceTrigger() {
        Log.i("CogPilot", "🔔 Voice trigger received")
        if (!voiceActive) {
            onVoiceToggle()
        } else {
            Log.i("CogPilot", "ℹ️ Voice session already active")
        }
    }

    private fun detectDrivingMode() {
        try {
            val uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
            val newIsDrivingMode = uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_CAR
            
            // Check for transition from parking (false) to driving (true)
            if (newIsDrivingMode && !isDrivingMode) {
                Log.i("CogPilot", "🚗 Switched to driving mode, proactively starting drive sequence.")
                if (!voiceActive) {
                    val now = System.currentTimeMillis()
                    lastAutoTriggerTime = now
                    val rc = lastRoadContext
                    val activeSpeedMph = carSpeedMph ?: lastSpeedMph
                    VoiceAgentTrigger.start(
                        this@MainActivity,
                        driverId      = currentUserId,
                        source        = "driving_mode_transition",
                        interactionType = VoiceAgentTrigger.INTERACTION_TYPE_START_DRIVE,
                        speedMph      = activeSpeedMph.takeIf { it > 0 },
                        roadTypes     = rc?.types?.joinToString(",")?.ifBlank { null },
                        trafficRatio  = rc?.trafficRatio,
                        tripStartMs   = tripStartMs
                    )
                    voiceActive = true
                    uiManager.setVoiceState(true)
                }
            }
            
            isDrivingMode = newIsDrivingMode
            Log.d("CogPilot", "UI Mode detected: ${if (isDrivingMode) "CAR" else "NORMAL"}")
        } catch (e: Exception) {
            Log.d("CogPilot", "UiModeManager not available: ${e.message}")
            isDrivingMode = false
        }
    }

    private fun updateRiskPeriodically() {
        handler.post(object : Runnable {
            override fun run() {
                lifecycleScope.launch {
                    try {
                        val logs = snowflakeManager.getLastNLogs(24)
                        Log.d("CogPilot", "Risk update: fetched ${logs.size} telemetry rows")
                        
                        if (logs.isNotEmpty()) {
                            val riskData = riskScorer.calculateRisk(logs)
                            uiManager.updateRisk(riskData)
                            lastRiskScore = riskData.riskScore

                            if (riskData.needsIntervention) {
                                Log.w("CogPilot", "⚠️ HIGH RISK: ${riskData.riskScore}")
                                val stimulus = snowflakeManager.generateStimulus()
                                uiManager.showIntervention(stimulus)
                                riskScorer.recordIntervention()
                            } else if (riskData.riskScore < 0.4f) {
                                uiManager.hideIntervention()
                            } else {
                                val now = System.currentTimeMillis()
                                if (!voiceActive && now - lastAutoTriggerTime > 30000) {
                                    val rc = lastRoadContext
                                    val timeOfDay = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                                    val driveMins = ((now - tripStartMs) / 60000).toInt()
                                    
                                    var triggerSource: String? = null
                                    if (rc?.trafficRatio != null && rc.trafficRatio > 1.5f) {
                                        triggerSource = "sudden_heavy_traffic"
                                    } else if (driveMins > 45 && (timeOfDay >= 23 || timeOfDay <= 5)) {
                                        triggerSource = "late_night_fatigue"
                                    } else if (riskData.riskScore > 0.5f) {
                                        triggerSource = "attention_drop"
                                    }
                                    
                                    if (triggerSource != null) {
                                        Log.i("CogPilot", "📢 Auto-triggering voice agent - trigger=$triggerSource (risk: ${riskData.riskScore})")
                                        lastAutoTriggerTime = now
                                        val activeSpeedMph = carSpeedMph ?: lastSpeedMph
                                        VoiceAgentTrigger.start(
                                            this@MainActivity,
                                            driverId      = currentUserId,
                                            source        = triggerSource,
                                            interactionType = VoiceAgentTrigger.INTERACTION_TYPE_CHECK_IN,
                                            speedMph      = activeSpeedMph.takeIf { it > 0 },
                                            roadTypes     = rc?.types?.joinToString(",")?.ifBlank { null },
                                            trafficRatio  = rc?.trafficRatio,
                                            tripStartMs   = tripStartMs
                                        )
                                        voiceActive = true
                                        uiManager.setVoiceState(true)
                                    }
                                }
                            }
                        } else {
                            // Log.d("CogPilot", "Telemetry buffer empty")  // noisy, disable
                        }
                    } catch (e: Exception) {
                        Log.e("CogPilot", "Risk update: ${e.message}", e)
                    }
                }
                handler.postDelayed(this, 1500)
            }
        })
    }

    private fun requestRequiredPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.INTERNET
        )

        val missing = permissions.filter {
            PermissionChecker.checkSelfPermission(this, it) != PermissionChecker.PERMISSION_GRANTED
        }.toTypedArray()

        if (missing.isNotEmpty()) {
            Log.i("CogPilot", "🔐 Requesting permissions: ${missing.joinToString(", ")}")
            ActivityCompat.requestPermissions(this, missing, 100)
        } else {
            Log.i("CogPilot", "✓ All permissions already granted")
            onPermissionsGranted()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.d("CogPilot", "onRequestPermissionsResult: requestCode=$requestCode, grantResults=${grantResults.contentToString()}")
        if (requestCode == 100) {
            val allGranted = grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                Log.i("CogPilot", "✓ All permissions granted by user")
                onPermissionsGranted()
            } else {
                val denied = permissions.zip(grantResults.toList())
                    .filter { it.second != android.content.pm.PackageManager.PERMISSION_GRANTED }
                    .map { it.first }
                Log.w("CogPilot", "⚠️ Permissions denied: $denied")
            }
        }
    }

    private fun onPermissionsGranted() {
        Log.i("CogPilot", "Starting location capture with permissions...")
        lifecycleScope.launch {
            Log.d("CogPilot", "Permission scope launched, about to call startCapture()")
            try {
                locationCapture.startCapture(
                    snowflakeManager,
                    callback = { speed, heading ->
                        lastSpeedMph = speed
                        if (tripStartMs == 0L) tripStartMs = System.currentTimeMillis()
                        uiManager.updateMetrics(speed, heading)
                    },
                    debug = { speed, heading, roadCtx, lat, lon ->
                        lastRoadContext = roadCtx  // stash for voice trigger
                        uiManager.updateDebug(
                            speed = speed,
                            heading = heading,
                            riskScore = lastRiskScore,
                            voiceActive = voiceActive,
                            roadCtx = roadCtx,
                            lat = lat,
                            lon = lon
                        )
                        // Movement-based failsafe: Start drive if moving > 10mph for 5 seconds
                        val now = System.currentTimeMillis()
                        if (!voiceActive && speed > 10f && !isDrivingMode) {
                            if (sustainedSpeedStartTime == 0L) {
                                sustainedSpeedStartTime = now
                                Log.d("CogPilot", "💨 Sustained speed detection started...")
                            } else if (now - sustainedSpeedStartTime > 5000L) {
                                Log.i("CogPilot", "🚀 Movement threshold met (5s > 10mph). Automatic start drive triggered.")
                                sustainedSpeedStartTime = 0L // reset
                                onStartDrive() // reuse the drive start logic
                            }
                        } else if (speed <= 3f) {
                            sustainedSpeedStartTime = 0L // reset if we stop
                        }

                        // Broadcast live telemetry to VoiceAgentService so the agent can request it anytime
                        sendBroadcast(Intent("fyi.acmc.cogpilot.voice.LOCATION_UPDATE").apply {
                            putExtra("extra_speed_mph", speed)
                            putExtra("extra_lat", lat.toFloat())
                            putExtra("extra_lon", lon.toFloat())
                            setPackage(packageName)
                        })
                    }
                )
            } catch (e: Exception) {
                Log.e("CogPilot", "Exception in startCapture: ${e.message}", e)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        detectDrivingMode()
        Log.d("CogPilot", "Configuration changed - Driving Mode: $isDrivingMode")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("CogPilot", "Shutting down...")
        if (aiLogReceiver != null) {
            unregisterReceiver(aiLogReceiver)
        }
        locationCapture.stopCapture()
        snowflakeManager.close()
        handler.removeCallbacksAndMessages(null)
        // voice service manages itself; just ensure it's stopped
        if (voiceActive) {
            VoiceAgentTrigger.stop(this)
        }
    }

    // helper to map string id to numeric key used in Snowflake
    private suspend fun driverNum(id: String): Int {
        return snowflakeManager.getDriverNum(id)
    }

    // update profile text in UI
    private fun refreshProfileDisplay() {
        lifecycleScope.launch {
            val prof = snowflakeManager.getUserProfileById(currentUserId)
            val text = "User: $currentUserId\n" +
                    "Interests: ${prof["interests"]}\n" +
                    "Complexity: ${prof["complexity"]}"
            uiManager.updateProfile(text)
            uiManager.updateDebugMessage("profile loaded for $currentUserId")
        }
    }
}

/**
 * UIManager: Handles all UI rendering for Material Design 3.
 */
class UIManager(
    private val activity: AppCompatActivity,
    initialUserId: String,
    private val onUserChange: (String) -> Unit,
    private val onVoiceToggle: () -> Unit,
    private val onStartDrive: (() -> Unit)? = null
) {
    private var selectedUserId = initialUserId

    private lateinit var statusTitle: TextView
    private lateinit var statusSubtitle: TextView
    private var speedText: TextView? = null
    private var headingText: TextView? = null
    private lateinit var riskGauge: android.widget.ProgressBar
    private lateinit var riskText: TextView
    private lateinit var voiceButton: MaterialButton
    private lateinit var interventionCard: com.google.android.material.card.MaterialCardView
    private lateinit var interventionText: TextView
    private lateinit var metricsText: TextView
    private lateinit var debugText: TextView
    private lateinit var aiLogText: TextView
    private val aiInputHistory = mutableListOf<String>()



    fun createUI(): android.widget.ScrollView {
        val scroll = android.widget.ScrollView(activity).apply {
            // soft backgound to reduce glare
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    android.graphics.Color.parseColor("#0F1419"),
                    android.graphics.Color.parseColor("#121B22"),
                    android.graphics.Color.parseColor("#0B0F14")
                )
            )
        }

        val container = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(20, 24, 20, 32)
        }
        // user selector
        container.addView(createUserSelector())
        container.addView(createSpacerView(12))

        container.addView(createHeaderView())
        container.addView(createSpacerView(16))
        container.addView(createStatusCard())
        container.addView(createSpacerView(20))
        container.addView(createVoiceControlCard())
        container.addView(createSpacerView(20))
        container.addView(createRiskGauge())
        container.addView(createSpacerView(12))
        container.addView(createMetricsRow())
        container.addView(createSpacerView(20))
        container.addView(createDebugCard())
        container.addView(createSpacerView(12))
        container.addView(createAiLogCard())
        container.addView(createSpacerView(20))
        container.addView(createInterventionCard())
        container.addView(createSpacerView(20))
        container.addView(createDetailsCard())

        scroll.addView(container)
        return scroll
    }

    private fun createHeaderView(): android.widget.LinearLayout {
        val header = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(8, 16, 8, 20)
        }

        val title = TextView(activity).apply {
            text = "CogPilot"
            textSize = 36f
            setTextColor(android.graphics.Color.parseColor("#C200D6"))
            typeface = Typeface.SANS_SERIF
            setPadding(0, 0, 0, 8)
        }

        val subtitle = TextView(activity).apply {
            text = "Attention companion, parked UI"
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#8EA3B5"))
            setPadding(0, 8, 0, 16)
            typeface = Typeface.SANS_SERIF
        }

        val pill = createPill("PARKED MODE", "#002D72", "#00FFFF")

        header.addView(title)
        header.addView(subtitle)
        header.addView(pill)
        return header
    }

    private fun createUserSelector(): android.view.View {
        val spinner = android.widget.Spinner(activity)
        val users = listOf("aldan_creo" to "Aldan", "ana_campillo" to "Ana", "marta_sanchez" to "Marta")
        val adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_item, users.map { it.second })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(users.indexOfFirst { it.first == selectedUserId }.coerceAtLeast(0))
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                val newId = users[position].first
                if (newId != selectedUserId) {
                    selectedUserId = newId
                    onUserChange(newId)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }
        return spinner
    }

    private fun createStatusCard(): com.google.android.material.card.MaterialCardView {
        val card = createCard(android.graphics.Color.parseColor("#0B0F14"), 22f, "#003CFF")

        val content = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        statusTitle = TextView(activity).apply {
            text = "Initializing..."
            textSize = 26f
            setTextColor(android.graphics.Color.parseColor("#00FFFF"))
            typeface = Typeface.SANS_SERIF
            setPadding(0, 0, 0, 12)
        }

        statusSubtitle = TextView(activity).apply {
            text = "Connecting..."
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#00B8CC"))
            setPadding(0, 0, 0, 0)
            typeface = Typeface.SANS_SERIF
        }

        content.addView(statusTitle)
        content.addView(statusSubtitle)
        card.addView(content)
        return card
    }

    private fun createVoiceControlCard(): com.google.android.material.card.MaterialCardView {
        val card = createCard(android.graphics.Color.parseColor("#0B0F14"), 14f, "#5A00FF")
        val content = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
        }

        val label = TextView(activity).apply {
            text = "Voice loop"
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#00B8CC"))
            typeface = Typeface.SANS_SERIF
            setPadding(0, 0, 0, 12)
        }

        voiceButton = MaterialButton(activity).apply {
            text = "Start conversation"
            isAllCaps = false
            setPadding(20, 14, 20, 14)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8; bottomMargin = 12 }
            setOnClickListener {
                onVoiceToggle?.invoke()
            }
        }

        val startDriveButton = MaterialButton(activity).apply {
            text = "Start Drive"
            isAllCaps = false
            setPadding(20, 14, 20, 14)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 0; bottomMargin = 12 }
            setBackgroundColor(android.graphics.Color.parseColor("#1E90FF"))
            setOnClickListener {
                onStartDrive?.invoke()
            }
        }

        val hint = TextView(activity).apply {
            text = "Low latency: WebRTC, Scribe v2"
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#8EA3B5"))
            setPadding(0, 0, 0, 0)
            typeface = Typeface.SANS_SERIF
        }

        content.addView(label)
        content.addView(voiceButton)
        content.addView(startDriveButton)
        content.addView(hint)
        card.addView(content)
        return card
    }

    private fun createRiskGauge(): android.widget.LinearLayout {
        val container = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(8, 0, 8, 0)
        }

        val label = TextView(activity).apply {
            text = "Attention meter"
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#00B8CC"))
            typeface = Typeface.SANS_SERIF
            setPadding(0, 0, 0, 12)
        }

        riskGauge = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                32
            ).apply { topMargin = 8; bottomMargin = 14 }
            progress = 0
            max = 100
        }

        riskText = TextView(activity).apply {
            text = "0.00 / 1.00 - SAFE"
            textSize = 18f
            setTextColor(android.graphics.Color.parseColor("#00FFFF"))
            typeface = Typeface.SANS_SERIF
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 0)
        }

        container.addView(label)
        container.addView(riskGauge)
        container.addView(riskText)
        return container
    }

    private fun createMetricsRow(): android.widget.LinearLayout {
        val row = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            weightSum = 2f
            setPadding(8, 0, 8, 0)
        }

        row.addView(createMetricCard("Speed", "0.0 mph", "#00ACC1"))
        row.addView(createMetricCard("Heading", "0°", "#FF6F00"))
        return row
    }

    private fun createMetricCard(title: String, value: String, color: String): com.google.android.material.card.MaterialCardView {
        val card = createCard(android.graphics.Color.parseColor("#0B0F14"), 8f, "#007ACC")
        card.layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = 10
            marginEnd = 10
        }

        val content = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(18, 20, 18, 20)
        }

        val valueText = TextView(activity).apply {
            text = value
            textSize = 22f
            setTextColor(android.graphics.Color.parseColor(color))
            typeface = Typeface.SANS_SERIF
            setPadding(0, 0, 0, 10)
        }

        val label = TextView(activity).apply {
            text = title
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#8EA3B5"))
            setPadding(0, 0, 0, 0)
            typeface = Typeface.SANS_SERIF
        }

        content.addView(valueText)
        content.addView(label)
        card.addView(content)

        when (title) {
            "Speed" -> speedText = valueText
            "Heading" -> headingText = valueText
        }

        return card
    }

    private fun createInterventionCard(): com.google.android.material.card.MaterialCardView {
        interventionCard = createCard(android.graphics.Color.parseColor("#0B0F14"), 18f, "#FF00FF")
        interventionCard.visibility = android.view.View.GONE

        interventionText = TextView(activity).apply {
            text = ""
            textSize = 18f
            setTextColor(android.graphics.Color.parseColor("#FF00FF"))
            setPadding(24, 28, 24, 28)
            typeface = Typeface.SANS_SERIF
            setLineSpacing(0f, 1.4f)
        }

        interventionCard.addView(interventionText)
        return interventionCard
    }

    private fun createDetailsCard(): com.google.android.material.card.MaterialCardView {
        val card = createCard(android.graphics.Color.parseColor("#0B0F14"), 14f, "#003CFF")
        val content = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        metricsText = TextView(activity).apply {
            text = "Monotony: 0.00\nTime: 0.00\nComplexity: 0.00"
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#8EA3B5"))
            typeface = Typeface.SANS_SERIF
            setLineSpacing(0f, 1.6f)
        }

        content.addView(metricsText)
        card.addView(content)
        return card
    }

    private fun createDebugCard(): com.google.android.material.card.MaterialCardView {
        val card = createCard(android.graphics.Color.parseColor("#0B0F14"), 10f, "#2B3A42")
        val content = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }

        val label = TextView(activity).apply {
            text = "Debug: system state"
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#8EA3B5"))
            typeface = Typeface.SANS_SERIF
            setPadding(0, 0, 0, 10)
        }

        debugText = TextView(activity).apply {
            text = "waiting for data..."
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#A3B8C7"))
            typeface = Typeface.SANS_SERIF
            setLineSpacing(0f, 1.4f)
        }

        content.addView(label)
        content.addView(debugText)
        card.addView(content)
        return card
    }

    private fun createSpacerView(heightDp: Int): android.view.View {
        return android.view.View(activity).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                heightDp
            )
        }
    }

    private fun createCard(bgColor: Int, elevation: Float, strokeColor: String? = null): com.google.android.material.card.MaterialCardView {
        return com.google.android.material.card.MaterialCardView(activity).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
            cardElevation = elevation
            setCardBackgroundColor(bgColor)
            radius = 16f
            strokeColor?.let { setStrokeColor(android.graphics.Color.parseColor(it)) }
            strokeWidth = 1
        }
    }

    private fun createPill(text: String, bg: String, fg: String): android.widget.LinearLayout {
        val pill = android.widget.LinearLayout(activity).apply {
            setPadding(10, 6, 10, 6)
            orientation = android.widget.LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = 18f
                setColor(android.graphics.Color.parseColor(bg))
            }
        }

        val t = TextView(activity).apply {
            this.text = text
            textSize = 10f
            setTextColor(android.graphics.Color.parseColor(fg))
            typeface = Typeface.SANS_SERIF
        }

        pill.addView(t)
        return pill
    }

    fun setConnectionStatus(title: String, color: String) {
        statusTitle.text = title
        statusTitle.setTextColor(android.graphics.Color.parseColor(color))
        statusSubtitle.text = "Snowflake Connected"
    }

    fun setVoiceState(isActive: Boolean) {
        voiceButton.text = if (isActive) "Stop Voice Session" else "Start Voice Session"
    }

    fun updateMetrics(speed: Float, heading: Float) {
        speedText?.text = "%.1f mph".format(speed)
        headingText?.text = "%.0f°".format(heading)
    }

    fun updateRisk(risk: RiskData) {
        val pct = (risk.riskScore * 100).toInt()
        animateProgress(pct)

        val (status, color) = when {
            risk.riskScore >= 0.6f -> "⚠️ HIGH RISK" to "#FF6B6B"
            risk.riskScore >= 0.3f -> "⚡ MEDIUM RISK" to "#FFB74D"
            else -> "✓ LOW RISK" to "#4CAF50"
        }

        riskText.apply {
            text = "${String.format("%.2f", risk.riskScore)} / 1.00 - $status"
            setTextColor(android.graphics.Color.parseColor(color))
        }

        metricsText.text = "Monotony: %.2f\nTime: %.2f\nComplexity: %.2f".format(
            risk.monotony, risk.timeOnTask, risk.complexity
        )
    }

    fun updateProfile(info: String) {
        // display somewhere: reuse statusSubtitle for simplicity
        statusSubtitle.text = info
    }

    fun updateDebugMessage(msg: String) {
        debugText.text = msg
    }

    fun logAiInput(msg: String, source: String? = null) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val speaker = source ?: "📡 Context"  // null = context update (no source)
        // Distinguish driver vs agent visually
        val entry = when {
            source == null -> "[$timestamp] 📡 context\n$msg"
            source.contains("You", ignoreCase = true)  -> "[$timestamp] $speaker\n$msg"
            else -> "[$timestamp] $speaker\n$msg"
        }
        aiInputHistory.add(entry)
        if (aiInputHistory.size > 10) aiInputHistory.removeAt(0)
        // Build text with separators between turns
        aiLogText.text = aiInputHistory.joinToString("\n" + "—".repeat(28) + "\n")
    }

    fun updateDebug(
        speed: Float,
        heading: Float,
        riskScore: Float,
        voiceActive: Boolean,
        roadCtx: RoadContext,
        lat: Double,
        lon: Double
    ) {
        val placeId = roadCtx.placeId ?: "-"
        val types = if (roadCtx.types.isNotEmpty()) roadCtx.types.joinToString(",") else "-"
        val traffic = roadCtx.trafficRatio?.let { "%.2f".format(it) } ?: "-"
        
        debugText.text = """speed: %.1f mph | heading: %.0f°
location: %.5f, %.5f
risk: %.2f | voice: %s
road place_id: %s
road types: %s
traffic: %s""".trimIndent().format(
            speed,
            heading,
            lat,
            lon,
            riskScore,
            if (voiceActive) "active" else "idle",
            placeId,
            types,
            traffic
        )
    }

    fun showIntervention(text: String) {
        interventionCard.apply {
            visibility = android.view.View.VISIBLE
            interventionText.text = "💡 $text"
        }
        statusTitle.apply {
            this.text = "🚨 INTERVENTION"
            setTextColor(android.graphics.Color.parseColor("#FF6B6B"))
        }
    }

    fun hideIntervention() {
        interventionCard.visibility = android.view.View.GONE
        statusTitle.apply {
            text = "✓ Safe"
            setTextColor(android.graphics.Color.parseColor("#4CAF50"))
        }
    }

    private fun createAiLogCard(): com.google.android.material.card.MaterialCardView {
        val card = createCard(android.graphics.Color.parseColor("#080D12"), 10f, "#5A00FF")
        val content = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }

        val headerRow = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 14)
        }
        val label = TextView(activity).apply {
            text = "🎙️  Conversation log"
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#A0AAFF"))
            typeface = Typeface.SANS_SERIF
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val hint = TextView(activity).apply {
            text = "last 10"
            textSize = 10f
            setTextColor(android.graphics.Color.parseColor("#445566"))
            typeface = Typeface.SANS_SERIF
        }
        headerRow.addView(label)
        headerRow.addView(hint)

        aiLogText = TextView(activity).apply {
            text = "Waiting for conversation..."
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#C8D8E8"))
            typeface = Typeface.MONOSPACE
            setLineSpacing(4f, 1.4f)
        }

        content.addView(headerRow)
        content.addView(aiLogText)
        card.addView(content)
        return card
    }

    private fun animateProgress(target: Int) {
        android.animation.ValueAnimator.ofInt(riskGauge.progress, target).apply {
            duration = 500
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { riskGauge.progress = it.animatedValue as Int }
            start()
        }
    }
}
