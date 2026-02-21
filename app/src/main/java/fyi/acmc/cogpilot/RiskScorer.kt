package fyi.acmc.cogpilot

import android.util.Log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * RiskScorer: Hybrid Risk Window Model for driver drowsiness prediction.
 * Combines: Monotony (low speed variance) + Time-on-Task - Road Complexity (turns).
 */
class RiskScorer {

    private var lastInterventionTime = System.currentTimeMillis()
    private val monotonyThreshold = 2.0f  // mph std dev
    private val timeThreshold = 1800000L  // 30 minutes in milliseconds
    private val riskThreshold = 0.6f

    fun calculateRisk(telemetryLogs: List<FloatArray>): RiskData {
        if (telemetryLogs.isEmpty()) {
            return RiskData(riskScore = 0f, needsIntervention = false)
        }

        // Extract speeds and headings
        val speeds = telemetryLogs.map { it[0] }
        val headings = telemetryLogs.map { it[1] }

        // Calculate components
        val monotonyFactor = calculateMonotonyFactor(speeds)
        val timeFactor = calculateTimeFactor()
        val complexityFactor = calculateComplexityFactor(headings)

        // Weights
        val monotonyWeight = 0.4f
        val timeWeight = 0.3f
        val complexityWeight = 0.3f

        // Final risk score
        var riskScore = (monotonyWeight * monotonyFactor) +
                (timeWeight * timeFactor) -
                (complexityWeight * complexityFactor)

        // Clamp to 0-1
        riskScore = max(0f, min(1f, riskScore))

        val needsIntervention = riskScore > riskThreshold

        Log.i(
            "RiskScorer",
            "Risk: %.3f | Monotony: %.2f | Time: %.2f | Complexity: %.2f | Intervene: $needsIntervention"
                .format(riskScore, monotonyFactor, timeFactor, complexityFactor)
        )

        return RiskData(
            riskScore = riskScore,
            needsIntervention = needsIntervention,
            monotony = monotonyFactor,
            timeOnTask = timeFactor,
            complexity = complexityFactor
        )
    }

    private fun calculateMonotonyFactor(speeds: List<Float>): Float {
        if (speeds.size < 2) return 0f

        val mean = speeds.average()
        val variance = speeds.map { (it - mean) * (it - mean) }.average()
        val stdDev = sqrt(variance.toFloat())

        return if (stdDev < monotonyThreshold) {
            min(1f, 1f - (stdDev / monotonyThreshold))
        } else {
            0f
        }
    }

    private fun calculateTimeFactor(): Float {
        val timeSinceIntervention = System.currentTimeMillis() - lastInterventionTime
        return min(1f, timeSinceIntervention.toFloat() / timeThreshold)
    }

    private fun calculateComplexityFactor(headings: List<Float>): Float {
        if (headings.isEmpty()) return 0f

        val maxHeading = headings.maxOrNull() ?: 0f
        val minHeading = headings.minOrNull() ?: 0f
        val headingVariance = maxHeading - minHeading

        return min(1f, headingVariance / 45f)  // Normalize on 45° threshold
    }

    fun recordIntervention() {
        lastInterventionTime = System.currentTimeMillis()
        Log.i("RiskScorer", "✓ Intervention recorded")
    }
}

data class RiskData(
    val riskScore: Float,
    val needsIntervention: Boolean,
    val monotony: Float = 0f,
    val timeOnTask: Float = 0f,
    val complexity: Float = 0f
)
