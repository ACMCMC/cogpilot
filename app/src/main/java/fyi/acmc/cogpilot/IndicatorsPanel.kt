package fyi.acmc.cogpilot

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * IndicatorsPanel - Displays all real-time indicators: attention, VAD, mode, risk, and more
 */
class IndicatorsPanel(context: Context) : LinearLayout(context) {

    private val indicatorViews = mutableMapOf<String, TextView>()

    init {
        orientation = VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        setPadding(16, 16, 16, 16)
        
        // Title
        val titleView = TextView(context).apply {
            text = "🎯 Live Indicators"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 12
            }
        }
        addView(titleView)
        
        // Row 1: KSS Level & Risk State
        addRow("attention_score", "🎯 KSS Level", "5", "risk_state", "⚠️ Risk", "normal")
        
        // Row 2: Voice Activity & Interaction Level
        addRow("vad_score", "🎤 Voice Activity", "0%", "interaction_level", "📈 Level", "0")
        
        // Row 3: Mode & Circadian Window
        addRow("mode", "🔊 Mode", "idle", "circadian_window", "🌙 Circadian", "normal")
        
        // Row 4: Drive Minutes & Road Type
        addRow("drive_minutes", "⏱️ Drive Time", "0min", "road_type", "🛣️ Road Type", "mixed")
        
        // Row 5: Vocal Energy & Response Latency
        addRow("vocal_energy", "🎙️ Vocal Energy", "0.00", "response_latency", "⏱️ Latency", "0ms")
        
        // Row 6: Vocal Trend & Latency Trend
        addRow("vocal_trend", "📈 Energy Trend", "0.00", "latency_trend", "📊 Latency Trend", "0.00")
        
        // Row 7: Speed Variance & Driver Profile
        addRow("speed_variance", "🏎️ Speed Var", "0.00", "driver_profile", "👤 Driver Profile", "unknown")
    }

    private fun addRow(key1: String, label1: String, default1: String, key2: String, label2: String, default2: String) {
        val rowView = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 6
            }
        }
        
        // Left indicator
        val left = createIndicatorView(context, label1, default1)
        indicatorViews[key1] = left.second
        rowView.addView(left.first, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        
        // Spacer
        val spacer = LinearLayout(context).apply {
            layoutParams = LayoutParams(8, LayoutParams.MATCH_PARENT)
        }
        rowView.addView(spacer)
        
        // Right indicator
        val right = createIndicatorView(context, label2, default2)
        indicatorViews[key2] = right.second
        rowView.addView(right.first, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        
        addView(rowView)
    }

    private fun createIndicatorView(
        context: Context,
        label: String,
        defaultValue: String
    ): Pair<LinearLayout, TextView> {
        val indicatorView = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setPadding(12, 10, 12, 10)
            setBackgroundColor(Color.argb(40, 100, 150, 255))
        }
        
        val labelView = TextView(context).apply {
            text = label
            textSize = 12f
            setTextColor(Color.WHITE)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 4
            }
        }
        
        val valueView = TextView(context).apply {
            text = defaultValue
            textSize = 13f
            setTextColor(Color.argb(255, 100, 200, 100))
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        
        indicatorView.addView(labelView)
        indicatorView.addView(valueView)
        
        return Pair(indicatorView, valueView)
    }

    fun updateIndicators(
        attentionScore: Float,
        vadScore: Float,
        mode: String,
        riskState: String,
        interactionLevel: Int,
        driveMinutes: Int,
        vocalEnergy: Float,
        responseLatency: Float,
        roadType: String,
        circadianWindow: String,
        driverProfile: String,
        vocalEnergyTrend: Float,
        latencyTrend: Float,
        speedVariance: Float
    ) {
        val kssValue = when {
            attentionScore >= 0.9f -> 1 // Extremely alert
            attentionScore >= 0.8f -> 2 // Very alert
            attentionScore >= 0.7f -> 3 // Alert
            attentionScore >= 0.6f -> 4 // Rather alert
            attentionScore >= 0.5f -> 5 // Neither alert nor sleepy
            attentionScore >= 0.4f -> 6 // Some signs of sleepiness
            attentionScore >= 0.3f -> 7 // Sleepy, no effort
            attentionScore >= 0.2f -> 8 // Sleepy, some effort
            else -> 9 // Very sleepy, fighting sleep
        }
        indicatorViews["attention_score"]?.text = kssValue.toString()
        indicatorViews["vad_score"]?.text = "${(vadScore * 100).toInt()}%"
        indicatorViews["mode"]?.text = mode.replaceFirstChar { it.uppercase() }
        indicatorViews["risk_state"]?.text = riskState.replaceFirstChar { it.uppercase() }
        indicatorViews["interaction_level"]?.text = interactionLevel.toString()
        indicatorViews["drive_minutes"]?.text = "${driveMinutes}min"
        indicatorViews["vocal_energy"]?.text = String.format("%.2f", vocalEnergy)
        indicatorViews["response_latency"]?.text = "${responseLatency.toInt()}ms"
        indicatorViews["road_type"]?.text = roadType.replaceFirstChar { it.uppercase() }
        indicatorViews["circadian_window"]?.text = circadianWindow.replaceFirstChar { it.uppercase() }
        indicatorViews["speed_variance"]?.text = String.format("%.2f", speedVariance)
        indicatorViews["driver_profile"]?.text = driverProfile.replaceFirstChar { it.uppercase() }
        indicatorViews["vocal_trend"]?.text = String.format("%.2f", vocalEnergyTrend)
        indicatorViews["latency_trend"]?.text = String.format("%.2f", latencyTrend)
        
        // Color code the risk state
        val riskView = indicatorViews["risk_state"]
        riskView?.setTextColor(when {
            riskState.contains("high", ignoreCase = true) -> Color.argb(255, 255, 100, 100)  // Red
            riskState.contains("medium", ignoreCase = true) -> Color.argb(255, 255, 200, 100)  // Orange
            else -> Color.argb(255, 100, 200, 100)  // Green
        })
    }
}

