package fyi.acmc.cogpilot

import android.Manifest
import android.app.UiModeManager
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
    private val elevenLabsManager = ElevenLabsManager()
    private val handler = Handler(Looper.getMainLooper())
    private var isDrivingMode = false
    private var voiceActive = false

    private lateinit var uiManager: UIManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        detectDrivingMode()
        Log.i("CogPilot", "🚗 MainActivity created | Driving Mode: $isDrivingMode")
        
        requestRequiredPermissions()

        uiManager = UIManager(this) {
            onVoiceToggle()
        }
        setContentView(uiManager.createUI())
        
        if (isDrivingMode) {
            Log.i("CogPilot", "✅ DRIVING MODE DETECTED - Full interactive support enabled")
        }

        lifecycleScope.launch {
            val connected = snowflakeManager.initConnection()
            if (connected) {
                Log.i("CogPilot", "✓ Snowflake Connected")
                uiManager.setConnectionStatus("✓ Connected", "#4CAF50")
                
                locationCapture.startCapture(snowflakeManager) { speed, heading ->
                    uiManager.updateMetrics(speed, heading)
                }
                
                updateRiskPeriodically()
            } else {
                Log.e("CogPilot", "✗ Failed to connect to Snowflake")
                uiManager.setConnectionStatus("✗ Connection Failed", "#FF6B6B")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        VoiceSessionController.onTrigger = {
            onVoiceTrigger()
        }
    }

    override fun onStop() {
        super.onStop()
        VoiceSessionController.onTrigger = null
    }

    private fun onVoiceToggle() {
        voiceActive = !voiceActive
        if (voiceActive) {
            Log.i("CogPilot", "🎙️ Voice session starting")
            uiManager.setVoiceState(true)
            lifecycleScope.launch {
                val token = elevenLabsManager.getWebrtcToken("driver")
                if (token.isNullOrBlank()) {
                    Log.e("CogPilot", "ElevenLabs token failed")
                    voiceActive = false
                    uiManager.setVoiceState(false)
                } else {
                    Log.i("CogPilot", "ElevenLabs token ok, ready for WebRTC")
                    // TODO: start WebRTC session using token
                }
            }
        } else {
            Log.i("CogPilot", "🛑 Voice session stopping")
            uiManager.setVoiceState(false)
            // TODO: stop ElevenLabs WebRTC session here
        }
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
            isDrivingMode = uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_CAR
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
                        
                        if (logs.isNotEmpty()) {
                            val riskData = riskScorer.calculateRisk(logs)
                            uiManager.updateRisk(riskData)

                            if (riskData.needsIntervention) {
                                Log.w("CogPilot", "⚠️ HIGH RISK: ${riskData.riskScore}")
                                val stimulus = snowflakeManager.generateStimulus()
                                uiManager.showIntervention(stimulus)
                                riskScorer.recordIntervention()
                            } else if (riskData.riskScore < 0.4f) {
                                uiManager.hideIntervention()
                            }
                        } else {
                            Log.d("CogPilot", "Telemetry buffer empty")
                        }
                    } catch (e: Exception) {
                        Log.e("CogPilot", "Risk update: ${e.message}", e)
                    }
                }
                handler.postDelayed(this, 3000)
            }
        })
    }

    private fun requestRequiredPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.INTERNET
        )

        val missing = permissions.filter {
            PermissionChecker.checkSelfPermission(this, it) != PermissionChecker.PERMISSION_GRANTED
        }.toTypedArray()

        if (missing.isNotEmpty()) {
            Log.i("CogPilot", "Requesting: ${missing.joinToString(", ")}")
            ActivityCompat.requestPermissions(this, missing, 100)
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
        locationCapture.stopCapture()
        snowflakeManager.close()
        handler.removeCallbacksAndMessages(null)
    }
}

/**
 * UIManager: Handles all UI rendering for Material Design 3.
 */
class UIManager(private val activity: AppCompatActivity) {

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

    private var onVoiceToggle: (() -> Unit)? = null

    constructor(activity: AppCompatActivity, onVoiceToggle: () -> Unit) : this(activity) {
        this.onVoiceToggle = onVoiceToggle
    }

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
            setPadding(16, 16, 16, 16)
        }

        container.addView(createHeaderView())
        container.addView(createStatusCard())
        container.addView(createVoiceControlCard())
        container.addView(createRiskGauge())
        container.addView(createMetricsRow())
        container.addView(createInterventionCard())
        container.addView(createDetailsCard())

        scroll.addView(container)
        return scroll
    }

    private fun createHeaderView(): android.widget.LinearLayout {
        val header = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(6, 20, 6, 16)
        }

        val title = TextView(activity).apply {
            text = "CogPilot"
            textSize = 34f
            setTextColor(android.graphics.Color.parseColor("#D5FF8A"))
            typeface = Typeface.create("serif", Typeface.BOLD)
        }

        val subtitle = TextView(activity).apply {
            text = "Attention companion, parked UI"
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#A4B4C2"))
            setPadding(0, 6, 0, 0)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }

        val pill = createPill("PARKED MODE", "#1D2A33", "#9ED8FF")

        header.addView(title)
        header.addView(subtitle)
        header.addView(pill)
        return header
    }

    private fun createStatusCard(): com.google.android.material.card.MaterialCardView {
        val card = createCard(android.graphics.Color.parseColor("#0D2C22"), 22f, "#1B8F6A")

        val content = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(22, 20, 22, 20)
        }

        statusTitle = TextView(activity).apply {
            text = "Initializing..."
            textSize = 24f
            setTextColor(android.graphics.Color.parseColor("#BDF4DA"))
            typeface = Typeface.create("serif", Typeface.BOLD)
        }

        statusSubtitle = TextView(activity).apply {
            text = "Connecting..."
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#8BD3B5"))
            setPadding(0, 8, 0, 0)
        }

        content.addView(statusTitle)
        content.addView(statusSubtitle)
        card.addView(content)
        return card
    }

    private fun createVoiceControlCard(): com.google.android.material.card.MaterialCardView {
        val card = createCard(android.graphics.Color.parseColor("#101820"), 14f, "#263241")
        val content = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(18, 14, 18, 14)
        }

        val label = TextView(activity).apply {
            text = "Voice loop"
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#8EA3B5"))
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }

        voiceButton = MaterialButton(activity).apply {
            text = "Start conversation"
            isAllCaps = false
            setPadding(18, 10, 18, 10)
            setOnClickListener {
                onVoiceToggle?.invoke()
            }
        }

        val hint = TextView(activity).apply {
            text = "Low latency: WebRTC, Scribe v2"
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#6F8395"))
            setPadding(0, 8, 0, 0)
        }

        content.addView(label)
        content.addView(voiceButton)
        content.addView(hint)
        card.addView(content)
        return card
    }

    private fun createRiskGauge(): android.widget.LinearLayout {
        val container = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 10, 0, 16)
        }

        val label = TextView(activity).apply {
            text = "Attention meter"
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#A8B7C4"))
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }

        riskGauge = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                28
            ).apply { topMargin = 10 }
            progress = 0
            max = 100
        }

        riskText = TextView(activity).apply {
            text = "0.00 / 1.00 - SAFE"
            textSize = 16f
            setTextColor(android.graphics.Color.parseColor("#9EF0B3"))
            typeface = Typeface.create("serif", Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 12, 0, 0)
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
            setPadding(0, 10, 0, 8)
        }

        row.addView(createMetricCard("Speed", "0.0 mph", "#00ACC1"))
        row.addView(createMetricCard("Heading", "0°", "#FF6F00"))
        return row
    }

    private fun createMetricCard(title: String, value: String, color: String): com.google.android.material.card.MaterialCardView {
        val card = createCard(android.graphics.Color.parseColor("#0F1A22"), 8f, "#1F2B36")
        card.layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = 8
            marginEnd = 8
        }

        val content = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val valueText = TextView(activity).apply {
            text = value
            textSize = 20f
            setTextColor(android.graphics.Color.parseColor(color))
            typeface = Typeface.create("serif", Typeface.BOLD)
        }

        val label = TextView(activity).apply {
            text = title
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#95A6B7"))
            setPadding(0, 8, 0, 0)
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
        interventionCard = createCard(android.graphics.Color.parseColor("#1E1A10"), 18f, "#3B2C12")
        interventionCard.visibility = android.view.View.GONE

        interventionText = TextView(activity).apply {
            text = ""
            textSize = 16f
            setTextColor(android.graphics.Color.parseColor("#FFD27A"))
            setPadding(20, 20, 20, 20)
        }

        interventionCard.addView(interventionText)
        return interventionCard
    }

    private fun createDetailsCard(): com.google.android.material.card.MaterialCardView {
        val card = createCard(android.graphics.Color.parseColor("#0F141A"), 14f, "#1E2A36")
        val content = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(18, 18, 18, 18)
        }

        metricsText = TextView(activity).apply {
            text = "Monotony: 0.00\nTime: 0.00\nComplexity: 0.00"
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#8FA2B3"))
        }

        content.addView(metricsText)
        card.addView(content)
        return card
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
            typeface = Typeface.create("monospace", Typeface.BOLD)
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

    private fun animateProgress(target: Int) {
        android.animation.ValueAnimator.ofInt(riskGauge.progress, target).apply {
            duration = 500
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { riskGauge.progress = it.animatedValue as Int }
            start()
        }
    }
}
