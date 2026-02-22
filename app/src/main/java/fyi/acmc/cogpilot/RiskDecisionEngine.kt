package fyi.acmc.cogpilot

import android.content.Context
import android.location.Location
import android.util.Log
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs

/**
 * RiskDecisionEngine: Evaluates driving state every 15 seconds
 * 
 * Input signals:
 * - Real-time GPS + speed from device
 * - Voice telemetry (VAD, latency, vocal_energy) from ElevenLabs callbacks
 * - Road metadata from Google Maps Roads API (road type, speed limit, traffic)
 * - Driver profile + memory from Snowflake
 * - Circadian window calculation
 * 
 * Output:
 * - Risk state: STABLE, EMERGING, WINDOW, CRITICAL
 * - Interaction level: 0-4
 * - Rationale (for debug UI)
 */
class RiskDecisionEngine(private val context: Context) {

    companion object {
        const val TAG = "RiskDecisionEngine"
        const val DECISION_INTERVAL_MS = 15_000L  // 15 seconds
    }

    // State
    private var currentRiskState: RiskState = RiskState.STABLE
    private var currentInteractionLevel: Int = 0
    private var tripStartTime: Long = 0L
    private var tripStartVocalEnergy: Float = 1.0f

    // Rolling history (for trend analysis)
    private val vocalEnergyHistory = ConcurrentLinkedQueue<Float>()  // last 10 samples
    private val responseLatencyHistory = ConcurrentLinkedQueue<Float>()  // ms, last 10
    private val speedHistory = ConcurrentLinkedQueue<Float>()  // mph, last 30
    private val riskStateHistory = ConcurrentLinkedQueue<RiskState>()  // last 5 decisions
    private var cumulativeDriveMinutes: Int = 0 // Persistent across trips in same session
    private var lastDecisionTime: Long = 0L
    private var lastInteractionTime: Long = 0L

    // Current session data
    private var lastLocation: Location? = null
    private var lastVocalEnergy: Float = 0.8f
    private var lastResponseLatencyMs: Float = 500f
    private var lastVadScore: Float = 0.7f
    private var lastUserResponse: UserResponseType = UserResponseType.NONE

    // Driver profile (loaded at trip start)
    private var driverProfile: DriverProfile? = null
    private var driverMemory: List<DriverMemory> = emptyList()

    // Road context (from Google Maps)
    private var currentRoadType: RoadType = RoadType.MIXED
    private var trafficCondition: TrafficCondition = TrafficCondition.MODERATE

    // Circadian state
    private var circadianWindow: CircadianWindow = CircadianWindow.NORMAL
    private var sleepHoursToday: Int = 7
    private var ambientTemperatureF: Float = 60.0f

    // Callbacks
    var onRiskStateChanged: ((RiskState, Int, String) -> Unit)? = null  // state, level, reason

    // ========================================================================
    // LIFECYCLE
    // ========================================================================

    fun startTrip(userId: String, sleepHours: Int) {
        Log.i(TAG, "🚗 Trip started: $userId, sleep=$sleepHours hrs")
        tripStartTime = System.currentTimeMillis()
        lastDecisionTime = tripStartTime
        sleepHoursToday = sleepHours
        tripStartVocalEnergy = 0.8f
        clearHistory()

        // Load driver profile from Snowflake
        loadDriverProfile(userId)

        // Start decision loop
        startDecisionLoop()
    }

    fun stopTrip() {
        Log.i(TAG, "🛑 Trip stopped. Total cumulative minutes: $cumulativeDriveMinutes")
        clearHistory()
        currentRiskState = RiskState.STABLE
        currentInteractionLevel = 0
        lastInteractionTime = 0L
    }

    fun recordInteraction() {
        lastInteractionTime = System.currentTimeMillis()
        Log.i(TAG, "🕒 Interaction recorded. Suppression active (60s recovery).")
    }

    fun setSleepHours(hours: Int) {
        Log.i(TAG, "💤 Sleep hours updated to $hours")
        sleepHoursToday = hours
    }

    fun updateTemperature(tempF: Float) {
        Log.d(TAG, "🌡️ Temperature updated: $tempF°F")
        ambientTemperatureF = tempF
    }

    fun getLastLocation(): Location? = lastLocation
    fun getRoadType(): RoadType = currentRoadType
    fun getTrafficCondition(): TrafficCondition = trafficCondition

    // ========================================================================
    // SNOWFLAKE: Load driver profile + memory
    // ========================================================================

    private fun loadDriverProfile(userId: String) {
        // In production: query Snowflake via SnowflakeSqlApiClient
        // For now, use hardcoded profiles
        driverProfile = getHardcodedProfile(userId)
        driverMemory = getHardcodedMemory(userId)
        Log.i(TAG, "✓ Loaded profile for $userId: ${driverProfile?.name}")
    }

    private fun getHardcodedProfile(userId: String): DriverProfile? {
        return when (userId) {
            "aldan_creo" -> DriverProfile(
                userId = userId,
                name = "Aldan",
                riskLevel = RiskLevel.HIGH,
                sleepPattern = "Erratic sleeper, 4-6 hrs on weekdays",
                riskTriggers = listOf("afternoon highway after 2pm", "post-flight", "driving alone at night"),
                effectiveLevers = listOf("technology_data", "objective_metrics"),
                rejectionPattern = "Wants proof, engages with stats",
                baselineVocalEnergy = 0.72f,
                boundary = "Show metrics, don't say drowsy"
            )
            "ana_campillo" -> DriverProfile(
                userId = userId,
                name = "Ana",
                riskLevel = RiskLevel.MODERATE,
                sleepPattern = "Regular, 7-8 hrs",
                riskTriggers = listOf("post-writing", "difficult conversations"),
                effectiveLevers = listOf("life_lessons", "teaching_analogy"),
                rejectionPattern = "Thoughtful, wants to ponder",
                baselineVocalEnergy = 0.68f,
                boundary = "Frame as self-care"
            )
            "marta_sanchez" -> DriverProfile(
                userId = userId,
                name = "Marta",
                riskLevel = RiskLevel.LOW,
                sleepPattern = "Excellent, 7.5-8.5 hrs, 5:30am wake",
                riskTriggers = listOf("rare: 18+ hour lab days"),
                effectiveLevers = listOf("discipline", "animals_routine"),
                rejectionPattern = "Almost never pushes back",
                baselineVocalEnergy = 0.81f,
                boundary = "Brief, direct language"
            )
            else -> null
        }
    }

    private fun getHardcodedMemory(userId: String): List<DriverMemory> {
        return when (userId) {
            "aldan_creo" -> listOf(
                DriverMemory("Afternoon highway drives (14:00-17:00) trigger WINDOW within 60-75 min", 0.89f),
                DriverMemory("Responds to objective metric comparisons immediately", 0.92f),
                DriverMemory("Engages when connected to tech/data infrastructure", 0.78f)
            )
            "ana_campillo" -> listOf(
                DriverMemory("2+ hour creative sessions before driving drop vocal energy 0.15-0.22", 0.82f),
                DriverMemory("Responds to frame of modeling boundaries for students", 0.88f),
                DriverMemory("Thoughtful decision-maker, needs 10-15s to consider", 0.79f)
            )
            "marta_sanchez" -> listOf(
                DriverMemory("Rarely fatigued, happens ~1x per 40 trips", 0.95f),
                DriverMemory("Discipline-and-routine language lands immediately", 0.91f),
                DriverMemory("Pre-aware of fatigue, quick to accept breaks", 0.93f)
            )
            else -> emptyList()
        }
    }

    // ========================================================================
    // DECISION LOOP: Runs every 15 seconds
    // ========================================================================

    private fun startDecisionLoop() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed(object : Runnable {
            override fun run() {
                evaluateRiskState()
                handler.postDelayed(this, DECISION_INTERVAL_MS)
            }
        }, DECISION_INTERVAL_MS)
    }

    private fun evaluateRiskState() {
        // Gather current signals
        val now = System.currentTimeMillis()
        val sessionMinutes = ((now - tripStartTime) / 60_000).toInt()
        
        // Update cumulative fatigue
        val deltaMinutes = ((now - lastDecisionTime) / 60_000).toInt()
        if (deltaMinutes > 0) {
            cumulativeDriveMinutes += deltaMinutes
            lastDecisionTime = now
        }

        val currentVocalEnergyTrend = calculateVocalEnergyTrend()
        val currentLatencyTrend = calculateLatencyTrend()
        val currentSpeedVariance = calculateSpeedVariance()

        // Calculate circadian window and weighted modifier
        circadianWindow = calculateCircadianWindow()
        val envModifier = calculateThresholdModifier(sessionMinutes)

        // Calculate interaction suppression factor S(t)
        val secondsSinceLastInteraction = (now - lastInteractionTime) / 1000f
        val suppressionFactor = if (lastInteractionTime == 0L) 1.0f 
                                else Math.min(1.0f, (Math.log(1.0 + secondsSinceLastInteraction) / Math.log(61.0)).toFloat())

        // Apply thresholds to determine risk state
        val newRiskState = when {
            // If suppression is very high (just interacted), force STABLE unless CRITICAL
            suppressionFactor < 0.1f -> RiskState.STABLE

            // CRITICAL: immediate danger signals - reduced tolerance if modifier is high
            lastVocalEnergy < (0.35f - (envModifier * 0.1f)) || 
            lastResponseLatencyMs > (3000f - (envModifier * 500f)) || 
            lastUserResponse == UserResponseType.NO_RESPONSE -> {
                RiskState.CRITICAL
            }

            // WINDOW: sustained fatigue with environmental factors
            (lastVocalEnergy < (0.55f - envModifier) || 
             lastResponseLatencyMs > (1800f - (envModifier * 300f)) ||
             (currentRiskState == RiskState.EMERGING && riskStateHistory.toList().count { s -> s == RiskState.EMERGING } > (15 - (envModifier * 5).toInt()))) -> {
                RiskState.WINDOW
            }

            // EMERGING: early signs of declining performance - highly affected by environmental weights
            (lastVocalEnergy < (0.75f - envModifier) || 
             lastResponseLatencyMs > (1000f - (envModifier * 200f)) ||
             (sessionMinutes > 45 && circadianWindow == CircadianWindow.CIRCADIAN_LOW) ||
             isRiskTriggerActive()) -> {
                
                // Final check: is the risk suppressed by recent interaction?
                if (suppressionFactor < 0.95f) {
                    RiskState.STABLE // Supress emerging triggers until fully recovered
                } else {
                    RiskState.EMERGING
                }
            }

            // STABLE: all signals nominal
            else -> RiskState.STABLE
        }

        // Determine interaction level based on risk state
        val newInteractionLevel = determineInteractionLevel(newRiskState, sessionMinutes)

        // Update state
        if (newRiskState != currentRiskState || newInteractionLevel != currentInteractionLevel) {
            currentRiskState = newRiskState
            currentInteractionLevel = newInteractionLevel

            val reason = buildDecisionRationale(
                newRiskState, newInteractionLevel, sessionMinutes,
                currentVocalEnergyTrend, currentLatencyTrend
            )

            Log.i(TAG, "🔄 Risk state changed: $newRiskState (Level $newInteractionLevel) - $reason")
            onRiskStateChanged?.invoke(newRiskState, newInteractionLevel, reason)
        }

        riskStateHistory.add(newRiskState)
        if (riskStateHistory.size > 5) riskStateHistory.poll()
    }

    // ========================================================================
    // THRESHOLD CALCULATIONS
    // ========================================================================

    private fun calculateVocalEnergyTrend(): Float {
        if (vocalEnergyHistory.size < 3) return lastVocalEnergy
        val recent = vocalEnergyHistory.toList().takeLast(3)
        return recent.average().toFloat()
    }

    private fun calculateLatencyTrend(): Float {
        if (responseLatencyHistory.size < 3) return lastResponseLatencyMs
        val recent = responseLatencyHistory.toList().takeLast(3)
        return recent.average().toFloat()
    }

    private fun calculateSpeedVariance(): Float {
        if (speedHistory.size < 10) return 0f
        val recent = speedHistory.toList().takeLast(10)
        val mean = recent.average()
        val variance = recent.map { (it - mean) * (it - mean) }.average()
        return variance.toFloat()
    }

    private fun calculateCircadianWindow(): CircadianWindow {
        val now = java.util.Calendar.getInstance()
        val hour = now.get(java.util.Calendar.HOUR_OF_DAY)

        // Circadian low windows: 
        // 02:00-05:00 (Human body's primary sleep pressure peak)
        // 14:00-16:00 (Post-prandial dip/secondary sleep pressure)
        return when (hour) {
            in 2..5 -> CircadianWindow.CIRCADIAN_LOW
            in 14..16 -> CircadianWindow.CIRCADIAN_LOW
            in 23..23, in 0..1, in 6..6 -> CircadianWindow.NORMAL // Early/late night
            else -> CircadianWindow.NORMAL
        }
    }

    private fun calculateThresholdModifier(sessionMinutes: Int): Float {
        var modifier = 0f

        // 1. Driving Time (Duration Weight)
        // Monotony increases linearly after 45 minutes
        if (sessionMinutes > 45) modifier += 0.05f
        if (sessionMinutes > 90) modifier += 0.10f
        if (sessionMinutes > 150) modifier += 0.15f

        // 2. Time of Day (Circadian Weight)
        if (circadianWindow == CircadianWindow.CIRCADIAN_LOW) {
            modifier += 0.12f // Significant bump during danger windows
        }

        // 3. Traffic Condition (Complexity Weight)
        // High traffic ignores monotony but increases cognitive load, reducing allowed latency
        when (trafficCondition) {
            TrafficCondition.HEAVY -> modifier += 0.08f
            TrafficCondition.MODERATE -> modifier += 0.03f
            TrafficCondition.LIGHT -> modifier += 0f
        }

        // 4. Road Complexity & Speed (Environment Weight)
        // Highway driving is more monotonous than mixed or urban roads
        // Sustained high speeds (65-75mph) on highways increase monotony
        val currentSpeedMph = speedHistory.toList().lastOrNull() ?: 0f
        when (currentRoadType) {
            RoadType.HIGHWAY -> {
                modifier += 0.07f
                if (currentSpeedMph in 65f..75f) {
                    modifier += 0.05f // High speed monotony multiplier
                }
            }
            RoadType.SUBURBAN -> modifier += 0.02f
            RoadType.URBAN -> modifier -= 0.05f // Cities keep people alert
            RoadType.MIXED -> modifier += 0f
        }

        // 5. Temperature (Fatigue Weight)
        // Assume baseline 60°F. Deviations into heat or cold increase fatigue.
        val tempDeviation = Math.abs(ambientTemperatureF - 60.0f)
        if (tempDeviation > 20f) { // Above 80°F or below 40°F
            modifier += 0.06f
        }
        if (tempDeviation > 35f) { // Above 95°F or below 25°F
            modifier += 0.12f
        }

        // 6. Sleep Debt (Baseline Weight)
        if (sleepHoursToday < 6) modifier += 0.05f
        if (sleepHoursToday < 5) modifier += 0.12f
        if (sleepHoursToday < 4) modifier += 0.25f

        // 6. Risk Level (Baseline Weight)
        if (driverProfile?.riskLevel == RiskLevel.HIGH) modifier += 0.08f

        // 7. Interaction Analysis (Future: Average words per interaction length)
        // Placeholder for avg_words_per_duration calculation
        // val avgInteractionEfficiency = calculateInteractionEfficiency()
        // modifier += (1.0f - avgInteractionEfficiency) * 0.1f

        return modifier.coerceIn(0f, 0.45f) // Cap the modifier to avoid over-sensitivity
    }

    private fun isRiskTriggerActive(): Boolean {
        val triggers = driverProfile?.riskTriggers ?: emptyList()

        // Check time-based triggers
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (triggers.any { it.contains("afternoon") } && (hour in 14..17)) return true
        if (triggers.any { it.contains("night") } && (hour in 22..23)) return true

        // Check road-based triggers
        if (triggers.any { it.contains("highway") } && currentRoadType == RoadType.HIGHWAY) return true

        return false
    }

    private fun determineInteractionLevel(riskState: RiskState, driveMinutes: Int): Int {
        return when (riskState) {
            RiskState.STABLE -> 0  // silence

            RiskState.EMERGING -> {
                when (currentRoadType) {
                    RoadType.HIGHWAY -> 2  // micro-activation
                    else -> 1  // binary check-in
                }
            }

            RiskState.WINDOW -> {
                if (lastUserResponse == UserResponseType.FAST || lastUserResponse == UserResponseType.POSITIVE) {
                    3  // direct activation with argument
                } else {
                    2  // micro-activation
                }
            }

            RiskState.CRITICAL -> 4  // safety message only
        }
    }

    // ========================================================================
    // GOOGLE MAPS INTEGRATION
    // ========================================================================

    fun updateLocation(location: Location) {
        lastLocation = location
        speedHistory.add(location.speed * 2.237f)  // convert m/s to mph
        if (speedHistory.size > 30) speedHistory.poll()

        // Query Google Maps Roads API async
        queryRoadMetadata(location.latitude, location.longitude)
    }

    private fun queryRoadMetadata(lat: Double, lon: Double) {
        // In production: call Google Maps Roads API via HTTP
        // For now, mock implementation
        Thread {
            try {
                // Simulate API call
                Thread.sleep(200)

                // Mock responses
                currentRoadType = RoadType.HIGHWAY  // would come from Roads API
                trafficCondition = TrafficCondition.MODERATE  // would come from Distance Matrix API

                Log.d(TAG, "✓ Road metadata: $currentRoadType")
            } catch (e: Exception) {
                Log.e(TAG, "Road metadata error: ${e.message}")
            }
        }.start()
    }

    // ========================================================================
    // TELEMETRY UPDATES (from ElevenLabs)
    // ========================================================================

    fun updateVoiceTelemetry(vocalEnergy: Float, responseLatencyMs: Float, vadScore: Float) {
        lastVocalEnergy = vocalEnergy
        lastResponseLatencyMs = responseLatencyMs
        lastVadScore = vadScore

        vocalEnergyHistory.add(vocalEnergy)
        if (vocalEnergyHistory.size > 10) vocalEnergyHistory.poll()

        responseLatencyHistory.add(responseLatencyMs)
        if (responseLatencyHistory.size > 10) responseLatencyHistory.poll()

        Log.d(TAG, "📊 Voice: energy=$vocalEnergy, latency=${responseLatencyMs}ms, vad=$vadScore")
    }

    fun updateDriverResponse(responseType: UserResponseType, latencyMs: Float) {
        lastUserResponse = responseType
        lastResponseLatencyMs = latencyMs
        Log.d(TAG, "💬 Driver response: $responseType (${latencyMs}ms)")
    }

    // ========================================================================
    // DEBUG / TELEMETRY
    // ========================================================================

    fun getRiskStateDebugInfo(): RiskDebugInfo {
        val driveMinutes = ((System.currentTimeMillis() - tripStartTime) / 60_000).toInt()
        return RiskDebugInfo(
            riskState = currentRiskState,
            interactionLevel = currentInteractionLevel,
            driveMinutes = driveMinutes,
            vocalEnergy = lastVocalEnergy,
            responseLatencyMs = lastResponseLatencyMs,
            vadScore = lastVadScore,
            roadType = currentRoadType,
            circadianWindow = circadianWindow,
            driverProfile = driverProfile?.name ?: "unknown",
            vocalEnergyTrend = calculateVocalEnergyTrend(),
            latencyTrend = calculateLatencyTrend(),
            speedVariance = calculateSpeedVariance()
        )
    }

    fun getDriverProfileDetails(): Map<String, String> {
        val profile = driverProfile ?: return emptyMap()
        return mapOf(
            "name" to (profile.name),
            "triggers" to profile.riskTriggers.joinToString(", "),
            "levers" to profile.effectiveLevers.joinToString(", "),
            "boundary" to profile.boundary,
            "rejection_pattern" to profile.rejectionPattern
        )
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private fun buildDecisionRationale(
        state: RiskState,
        level: Int,
        driveMinutes: Int,
        vocalTrend: Float,
        latencyTrend: Float
    ): String {
        val factors = mutableListOf<String>()

        when (state) {
            RiskState.STABLE -> factors.add("all signals nominal")
            RiskState.EMERGING -> {
                if (lastVocalEnergy < 0.75f) factors.add("vocal energy declining")
                if (lastResponseLatencyMs > 1000f) factors.add("latency rising")
                if (circadianWindow == CircadianWindow.CIRCADIAN_LOW) factors.add("circadian low")
            }
            RiskState.WINDOW -> {
                if (lastVocalEnergy < 0.55f) factors.add("vocal energy critical")
                if (lastResponseLatencyMs > 1800f) factors.add("latency very high")
                factors.add("sustained fatigue")
            }
            RiskState.CRITICAL -> {
                factors.add("immediate danger: ${lastVocalEnergy}")
                if (lastUserResponse == UserResponseType.NO_RESPONSE) factors.add("no response")
            }
        }

        factors.add("${driveMinutes}min driving")
        if (currentRoadType == RoadType.HIGHWAY) factors.add("highway")

        return factors.joinToString(", ")
    }

    private fun clearHistory() {
        vocalEnergyHistory.clear()
        responseLatencyHistory.clear()
        speedHistory.clear()
        riskStateHistory.clear()
    }
}

// ============================================================================
// DATA CLASSES
// ============================================================================

enum class RiskState {
    STABLE, EMERGING, WINDOW, CRITICAL
}

enum class RiskLevel {
    LOW, MODERATE, HIGH
}

enum class CircadianWindow {
    NORMAL, CIRCADIAN_LOW
}

enum class RoadType {
    URBAN, SUBURBAN, MIXED, HIGHWAY
}

enum class TrafficCondition {
    LIGHT, MODERATE, HEAVY
}

enum class UserResponseType {
    NONE, NO_RESPONSE, FAST, SLOW, ANNOYED, COMPLIANT, POSITIVE, NEGATIVE
}

data class DriverProfile(
    val userId: String,
    val name: String,
    val riskLevel: RiskLevel,
    val sleepPattern: String,
    val riskTriggers: List<String>,
    val effectiveLevers: List<String>,
    val rejectionPattern: String,
    val baselineVocalEnergy: Float,
    val boundary: String
)

data class DriverMemory(
    val observation: String,
    val confidence: Float
)

data class RiskDebugInfo(
    val riskState: RiskState,
    val interactionLevel: Int,
    val driveMinutes: Int,
    val vocalEnergy: Float,
    val responseLatencyMs: Float,
    val vadScore: Float,
    val roadType: RoadType,
    val circadianWindow: CircadianWindow,
    val driverProfile: String,
    val vocalEnergyTrend: Float,
    val latencyTrend: Float,
    val speedVariance: Float
)
