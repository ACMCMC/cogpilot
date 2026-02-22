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
        const val DECISION_INTERVAL_MS = 1_000L  // 1 second for live updates
        const val GOOGLE_MAPS_THROTTLE_MS = 60_000L // 60 seconds
    }

    // State
    private var lastRoadMetadataQueryTime: Long = 0L

    private var currentRiskState: RiskState = RiskState.STABLE
    private var currentInteractionLevel: Int = 0
    private var tripStartTime: Long = 0L

    var isParked: Boolean = true
        private set

    val isDriving: Boolean
        get() = !isParked

    private var tripStartVocalEnergy: Float = 1.0f

    // Rolling history (for trend analysis)
    private val vocalEnergyHistory = ConcurrentLinkedQueue<Float>()  // last 10 samples
    private val responseLatencyHistory = ConcurrentLinkedQueue<Float>()  // ms, last 10
    private val wordsPerSecondHistory = ConcurrentLinkedQueue<Float>() // wps, last 10
    private val speedHistory = ConcurrentLinkedQueue<Float>()  // mph, last 30
    private val riskStateHistory = ConcurrentLinkedQueue<RiskState>()  // last 5 decisions
    private var cumulativeDriveMinutes: Int = 0 // Persistent across trips in same session
    private var lastDecisionTime: Long = 0L
    private var isInteractionActive: Boolean = false
    private var lastInteractionEndTime: Long = 0L

    // ONNX Model Integration
    private var onnxDetector: IDrowsinessDetector? = null
    private val featureExtractor = AudioFeatureExtractor()
    private var lastOnnxRiskScore: Float = 0.0f

    // Current session data
    private var lastLocation: Location? = null
    private var lastVocalEnergy: Float = 0.8f
    private var lastResponseLatencyMs: Float = 500f
    private var lastWordsPerSecond: Float = 2.5f
    private var lastVadScore: Float = 0.7f
    private var lastUserResponse: UserResponseType = UserResponseType.NONE
    private var agenticAttentionScore: Float = 0.5f // Default to 50% neutral

    // Driver profile (loaded at trip start)
    private var driverProfile: DriverProfile? = null
    private var driverMemory: List<DriverMemory> = emptyList()

    // Road context (from Google Maps)
    private var currentRoadType: RoadType = RoadType.MIXED
    private var trafficCondition: TrafficCondition = TrafficCondition.MODERATE

    // Telemetry Priority
    private var lastVehicleSpeedMph: Float? = null
    private var lastVehicleSpeedTime: Long = 0L

    // Circadian state
    private var circadianWindow: CircadianWindow = CircadianWindow.NORMAL
    private var sleepHoursToday: Int = 7
    private var ambientTemperatureF: Float = 60.0f

    // Callbacks
    var onRiskStateChanged: ((RiskState, Int, String) -> Unit)? = null  // state, level, reason
    var onRiskScoreUpdated: ((Float, RiskState, String) -> Unit)? = null // score, state, breakdown

    // ========================================================================
    // LIFECYCLE
    // ========================================================================

    fun startTrip(userId: String, sleepHours: Int) {
        Log.i(TAG, "🚗 Trip started: $userId, sleep=$sleepHours hrs")
        
        // Initialize ONNX detector if not already set (e.g. by a test)
        if (onnxDetector == null) {
            try {
                onnxDetector = OnnxDrowsinessDetector(context)
            } catch (e: Throwable) {
                Log.w(TAG, "⚠️ Could not initialize ONNX detector (likely unit test environment): ${e.message}")
            }
        }
        
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
        tripStartTime = 0L
        clearHistory()
        currentRiskState = RiskState.STABLE
        currentInteractionLevel = 0
        isInteractionActive = false
        lastInteractionEndTime = 0L
        onRiskScoreUpdated?.invoke(0f, RiskState.STABLE, "Not driving")
    }

    fun recordInteraction() {
        isInteractionActive = true
        Log.i(TAG, "🗣️ Interaction started")
    }
    
    fun recordInteractionEnd() {
        isInteractionActive = false
        lastInteractionEndTime = System.currentTimeMillis()
        Log.i(TAG, "🤫 Interaction ended")
    }

    fun setSleepHours(hours: Int) {
        Log.i(TAG, "💤 Sleep hours updated to $hours")
        sleepHoursToday = hours
    }

    fun updateTemperature(tempF: Float) {
        Log.d(TAG, "🌡️ Temperature updated: $tempF°F")
        ambientTemperatureF = tempF
    }

    fun updateVehicleSpeed(speedMs: Float) {
        val speedMph = speedMs * 2.237f
        lastVehicleSpeedMph = speedMph
        lastVehicleSpeedTime = System.currentTimeMillis()
        
        // Push directly to history
        speedHistory.add(speedMph)
        if (speedHistory.size > 30) speedHistory.poll()
        
        Log.d(TAG, "🏎️ Vehicle Speed (Direct): $speedMph mph")
    }

    fun updateParkingState(parked: Boolean) {
        Log.i(TAG, "🅿️ Parking state updated: parked=$parked")
        isParked = parked
        if (parked) {
             onRiskScoreUpdated?.invoke(0f, RiskState.STABLE, "Not driving")
        }
    }

    fun updateAgenticAttention(score: Float) {
        agenticAttentionScore = score.coerceIn(0f, 1f)
        Log.i(TAG, "🧠 Agentic Attention Updated: $agenticAttentionScore")
    }

    /**
     * updateOnnxAudioTelemetry: Called by MicrophoneAnalyzer with live PCM data.
     * Runs ONNX inference and updates the internal ONNX risk score.
     */
    fun updateOnnxAudioTelemetry(pcmData: FloatArray) {
        if (onnxDetector == null) return
        
        try {
            val prediction = onnxDetector?.predict(pcmData) ?: 0.0f
            lastOnnxRiskScore = prediction
            Log.d(TAG, "🤖 ONNX Model Inference Result: $prediction")
            
            // Trigger a re-calculation immediately if we are driving
            if (!isParked && tripStartTime > 0) {
                evaluateRiskState()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error running ONNX inference: ${e.message}")
        }
    }

    fun getLastLocation(): Location? = lastLocation
    fun getRoadType(): RoadType = currentRoadType
    fun getTrafficCondition(): TrafficCondition = trafficCondition
    fun getLastOnnxRiskScore(): Float = lastOnnxRiskScore

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
        val currentPaceTrend = calculatePaceTrend()
        val currentSpeedVariance = calculateSpeedVariance()

        // 1. Calculate Environmental Fatigue Modifier (0.0 to 0.45 range)
        circadianWindow = calculateCircadianWindow()
        val envFactors = calculateThresholdModifier(sessionMinutes)
        val envFatigueModifier = envFactors.total

        // 2. Calculate Base Cognitive Fatigue Trend (0.0 to 0.6 range)
        // Normalize Vocal Energy: 0.8 (Baseline) -> 0.0 risk, 0.35 (Critical) -> 1.0 risk
        val energyRisk = ((0.8f - currentVocalEnergyTrend) / (0.8f - 0.35f)).coerceIn(0f, 1f)
        
        // Normalize Latency: 500ms (Baseline) -> 0.0 risk, 3000ms (Critical) -> 1.0 risk
        val latencyRisk = ((currentLatencyTrend - 500f) / (3000f - 500f)).coerceIn(0f, 1f)

        // Normalize Pace: 2.5 wps (Baseline) -> 0.0 risk, 1.0 wps (Slurred/Slow) -> 1.0 risk
        val paceRisk = ((2.5f - currentPaceTrend) / (2.5f - 1.0f)).coerceIn(0f, 1f)

        // Agentic Risk: 1.0 (Fully attentive) -> 0.0 risk, 0.0 (Sleeping) -> 1.0 risk
        val agenticRisk = (1.0f - agenticAttentionScore).coerceIn(0f, 1f)
        
        // Base fatigue is weighted telemetry (capped to 0.6 to leave room for environment)
        // 15% Voice, 20% Latency, 15% Pace, 10% Agent
        val baseFatigueRisk = (energyRisk * 0.15f + latencyRisk * 0.20f + paceRisk * 0.15f + agenticRisk * 0.10f)

        // 3. Combined Session Risk Score (Telemetry + Environment)
        val deterministicRiskScore = (baseFatigueRisk + envFatigueModifier).coerceIn(0f, 1.1f)

        // 4. Final Effective Risk Score: 50/50 blend with ONNX model
        // If ONNX hasn't run yet, we default to the deterministic score
        val effectiveRiskScore = if (lastOnnxRiskScore > 1e-5) {
            (deterministicRiskScore * 0.5f) + (lastOnnxRiskScore * 0.5f)
        } else {
            deterministicRiskScore
        }

        val newRiskState = when {
            effectiveRiskScore >= 0.95f -> RiskState.CRITICAL
            effectiveRiskScore >= 0.75f -> RiskState.WINDOW // Mandatory trigger point (75%)
            effectiveRiskScore >= 0.60f -> RiskState.EMERGING
            else -> RiskState.STABLE
        }

        // 7. Determine interaction level based on unified score, with strict cooldown blocks
        // Ensure no interactions trigger if we are actively talking, or just finished within 30s
        val isCooldownActive = isInteractionActive || (now - lastInteractionEndTime < 30000L)
        
        val newInteractionLevel = when {
            isCooldownActive -> 0 // 🛑 TOTAL SUPPRESSION: Wait until 30s post-interaction
            effectiveRiskScore >= 0.95f -> 4 // Safety Command
            effectiveRiskScore >= 0.88f -> 3 // Persuasive Argument
            effectiveRiskScore >= 0.75f -> 1 // Mandatory Check-in (75% threshold)
            else -> 0 // Silence
        }

        // Update state
        if (newRiskState != currentRiskState || newInteractionLevel != currentInteractionLevel) {
            currentRiskState = newRiskState
            currentInteractionLevel = newInteractionLevel

            val reason = "Score: ${String.format("%.2f", effectiveRiskScore)} | " + 
                        buildDecisionRationale(
                            newRiskState, newInteractionLevel, sessionMinutes,
                            currentVocalEnergyTrend, currentLatencyTrend, currentPaceTrend,
                            baseFatigueRisk, envFactors, agenticAttentionScore,
                            paceRisk, energyRisk, latencyRisk, agenticRisk
                        )

            Log.i(TAG, "🔄 Risk state changed: $newRiskState (Level $newInteractionLevel) - $reason")
            onRiskStateChanged?.invoke(newRiskState, newInteractionLevel, reason)
        }

        val breakdownStr = buildDecisionRationale(
            currentRiskState, currentInteractionLevel, sessionMinutes,
            currentVocalEnergyTrend, currentLatencyTrend, currentPaceTrend,
            baseFatigueRisk, envFactors, agenticAttentionScore,
            paceRisk, energyRisk, latencyRisk, agenticRisk
        )

        // Always notify of score update for UI/Android Auto updates
        onRiskScoreUpdated?.invoke(effectiveRiskScore, newRiskState, breakdownStr)

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

    private fun calculatePaceTrend(): Float {
        if (wordsPerSecondHistory.size < 3) return lastWordsPerSecond
        val recent = wordsPerSecondHistory.toList().takeLast(3)
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

    private fun calculateThresholdModifier(sessionMinutes: Int): EnvFactors {
        var modifier = 0f
        
        var durationW = 0f
        var circadianW = 0f
        var trafficW = 0f
        var roadW = 0f
        var tempW = 0f
        var baselineW = 0f

        // 1. Driving Time (Demo Weight - Exaggerated Logarithmic Ramp)
        // D(t) = 0.3 * ln(1 + t) / ln(6). After 5 mins, modifier = 0.3
        val durationWeight = (0.3f * (Math.log(1.0 + sessionMinutes) / Math.log(6.0)).toFloat()).coerceAtLeast(0f)
        durationW += durationWeight
        if (sessionMinutes > 120) durationW += 0.15f
        modifier += durationW

        // 2. Time of Day (Circadian Weight)
        if (circadianWindow == CircadianWindow.CIRCADIAN_LOW) {
            circadianW += 0.12f // Significant bump during danger windows
            modifier += circadianW
        }

        // 3. Traffic Condition (Complexity Weight)
        // High traffic ignores monotony but increases cognitive load, reducing allowed latency
        when (trafficCondition) {
            TrafficCondition.HEAVY -> trafficW += 0.08f
            TrafficCondition.MODERATE -> trafficW += 0.03f
            TrafficCondition.LIGHT -> trafficW += 0f
        }
        modifier += trafficW

        // 4. Road Complexity & Speed (Environment Weight)
        // Highway driving is more monotonous than mixed or urban roads
        // Sustained high speeds (65-75mph) on highways increase monotony
        // Use average of last 10 samples to be resistant to stoplight/stop-sign outliers
        val avgSpeedMph = if (speedHistory.isEmpty()) 0f else speedHistory.average().toFloat()
        
        when (currentRoadType) {
            RoadType.HIGHWAY -> {
                roadW += 0.07f
                if (avgSpeedMph in 65f..75f) {
                    roadW += 0.05f // High speed monotony multiplier
                }
            }
            RoadType.SUBURBAN -> roadW += 0.02f
            RoadType.URBAN -> roadW -= 0.05f // Cities keep people alert
            RoadType.MIXED -> roadW += 0f
        }
        modifier += roadW

        // 5. Temperature (Fatigue Weight)
        // Assume baseline 60°F. Deviations into heat or cold increase fatigue.
        val tempDeviation = Math.abs(ambientTemperatureF - 60.0f)
        if (tempDeviation > 20f) { // Above 80°F or below 40°F
            tempW += 0.06f
        }
        if (tempDeviation > 35f) { // Above 95°F or below 25°F
            tempW += 0.12f
        }
        modifier += tempW

        // 6. Sleep Debt (Baseline Weight)
        if (sleepHoursToday < 6) baselineW += 0.05f
        if (sleepHoursToday < 5) baselineW += 0.12f
        if (sleepHoursToday < 4) baselineW += 0.25f

        // 6. Risk Level (Baseline Weight)
        if (driverProfile?.riskLevel == RiskLevel.HIGH) baselineW += 0.08f
        
        modifier += baselineW

        // 7. Interaction Analysis (Future: Average words per interaction length)
        // Placeholder for avg_words_per_duration calculation
        // val avgInteractionEfficiency = calculateInteractionEfficiency()
        // modifier += (1.0f - avgInteractionEfficiency) * 0.1f

        return EnvFactors(
            duration = durationW, durationRaw = sessionMinutes,
            circadian = circadianW, circadianRaw = circadianWindow,
            traffic = trafficW, trafficRaw = trafficCondition,
            road = roadW, roadRaw = currentRoadType, speedRaw = avgSpeedMph,
            temp = tempW, tempRaw = ambientTemperatureF.toFloat(),
            baseline = baselineW, sleepRaw = sleepHoursToday.toFloat(), profileRaw = driverProfile?.riskLevel?.name ?: "UNK",
            total = modifier.coerceIn(0f, 0.45f)
        )
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
        
        // Priority check: Only use GPS speed if car hardware speed is stale (> 5 seconds)
        val now = System.currentTimeMillis()
        if (lastVehicleSpeedMph == null || (now - lastVehicleSpeedTime > 5000)) {
            var gpsSpeedMph = location.speed * 2.237f
            if (gpsSpeedMph < 10f) {
                gpsSpeedMph = 0f // Filter out GPS measurement errors when stationary/slow moving
            } else if (isParked) {
                // If we are moving > 10mph on GPS, we are definitely not parked. Un-park!
                updateParkingState(false)
            }
            speedHistory.add(gpsSpeedMph)
            if (speedHistory.size > 30) speedHistory.poll()
            Log.d(TAG, "📍 GPS Speed (Fallback): $gpsSpeedMph mph")
        }

        // Query Google Maps Roads API async (Throttled to 60 seconds to save quota)
        if (now - lastRoadMetadataQueryTime >= GOOGLE_MAPS_THROTTLE_MS) {
            lastRoadMetadataQueryTime = now
            queryRoadMetadata(location.latitude, location.longitude)
        } else {
            Log.d(TAG, "🗺️ Maps API Throttled using cached road context.")
        }
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

    fun updateVoiceTelemetry(vocalEnergy: Float, responseLatencyMs: Float, vadScore: Float, wordsPerSecond: Float = 2.5f) {
        lastVocalEnergy = vocalEnergy
        lastResponseLatencyMs = responseLatencyMs
        lastVadScore = vadScore
        lastWordsPerSecond = wordsPerSecond

        vocalEnergyHistory.add(vocalEnergy)
        if (vocalEnergyHistory.size > 10) vocalEnergyHistory.poll()

        responseLatencyHistory.add(responseLatencyMs)
        if (responseLatencyHistory.size > 10) responseLatencyHistory.poll()

        wordsPerSecondHistory.add(wordsPerSecond)
        Log.d(TAG, "📊 Voice: energy=$vocalEnergy, latency=${responseLatencyMs}ms, wps=$wordsPerSecond, vad=$vadScore")
    }

    /**
     * Updates the engine with raw audio for ONNX-based drowsiness detection.
     * Processes the audio through feature extraction and model inference.
     */
    fun updateOnnxAudioTelemetry(audio: FloatArray) {
        val features = featureExtractor.extractFeatures(audio)
        lastOnnxRiskScore = onnxDetector?.predict(features) ?: 0.0f
        Log.d(TAG, "🤖 ONNX Model Inference Result: ${String.format("%.4f", lastOnnxRiskScore)}")
    }

    /**
     * For Unit Testing: allows manually setting a mock detector.
     */
    fun setDrowsinessDetector(detector: IDrowsinessDetector) {
        this.onnxDetector = detector
    }

    /**
     * Directly update the last known ONNX score (e.g. if processed elsewhere).
     */
    fun updateOnnxRiskScore(score: Float) {
        lastOnnxRiskScore = score
    }

    fun getRiskStateDebugInfo(): RiskInfo {
        val now = System.currentTimeMillis()
        val sessionMinutes = ((now - tripStartTime) / 60_000).toInt()
        
        // Use the same formula as evaluateRiskState for the debug score
        val envFactors = calculateThresholdModifier(sessionMinutes)
        val vocalEnergyTrend = calculateVocalEnergyTrend()
        val latencyTrend = calculateLatencyTrend()
        val paceTrend = calculatePaceTrend()
        val energyRisk = ((0.8f - vocalEnergyTrend) / (0.8f - 0.35f)).coerceIn(0f, 1f)
        val latencyRisk = ((latencyTrend - 500f) / (3000f - 500f)).coerceIn(0f, 1f)
        val paceRisk = ((2.5f - paceTrend) / (2.5f - 1.0f)).coerceIn(0f, 1f)
        val agenticRisk = (1.0f - agenticAttentionScore).coerceIn(0f, 1f)
        val baseFatigueRisk = (energyRisk * 0.15f + latencyRisk * 0.20f + paceRisk * 0.15f + agenticRisk * 0.10f)
        
        val detScore = (baseFatigueRisk + envFactors.total).coerceIn(0f, 1.1f)
        val finalScore = if (lastOnnxRiskScore > 1e-5) (detScore * 0.5f) + (lastOnnxRiskScore * 0.5f) else detScore

        return RiskInfo(
            riskState = currentRiskState,
            interactionLevel = currentInteractionLevel,
            driveMinutes = sessionMinutes,
            riskScore = finalScore,
            vocalEnergy = lastVocalEnergy,
            responseLatencyMs = lastResponseLatencyMs,
            roadType = currentRoadType,
            circadianWindow = circadianWindow,
            driverProfile = driverProfile?.name ?: "unknown",
            vocalEnergyTrend = vocalEnergyTrend,
            latencyTrend = latencyTrend,
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
        latencyTrend: Float,
        paceTrend: Float,
        baseFatigue: Float,
        env: EnvFactors,
        agentScore: Float,
        paceRisk: Float,
        energyRisk: Float,
        latencyRisk: Float,
        agenticRisk: Float
    ): String {
        return String.format(
            "[Base(Engy(%.2f)*.15=%.2f + Lat(%.0f)*.20=%.2f + Pace(%.1f)*.15=%.2f + Agnt(%.2f)*.1=%.2f) + Env(%.2f)] = %.2f\n" +
            "Env: Tim(%dm)=%.2f Cir(%s)=%.2f Trf(%s)=%.2f Rd(%s,%.0fmph)=%.2f Tmp(%.0fF)=%.2f SlpPrf(%.1fh,%s)=%.2f",
            vocalTrend, energyRisk * 0.15f, 
            latencyTrend, latencyRisk * 0.20f, 
            paceTrend, paceRisk * 0.15f, 
            agentScore, agenticRisk * 0.10f,
            env.total, 
            (baseFatigue + env.total).coerceIn(0f, 1.1f),

            env.durationRaw, env.duration, 
            env.circadianRaw.name.take(3), env.circadian, 
            env.trafficRaw.name.take(3), env.traffic, 
            env.roadRaw.name.take(3), env.speedRaw, env.road, 
            env.tempRaw, env.temp, 
            env.sleepRaw, env.profileRaw.take(3), env.baseline
        )
    }

    private fun clearHistory() {
        vocalEnergyHistory.clear()
        responseLatencyHistory.clear()
        wordsPerSecondHistory.clear()
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

data class RiskInfo(
    val riskState: RiskState,
    val interactionLevel: Int,
    val driveMinutes: Int,
    val riskScore: Float,
    val vocalEnergy: Float,
    val responseLatencyMs: Float,
    val roadType: RoadType,
    val circadianWindow: CircadianWindow,
    val driverProfile: String,
    val vocalEnergyTrend: Float,
    val latencyTrend: Float,
    val speedVariance: Float
)
        
data class EnvFactors(
    val duration: Float, val durationRaw: Int,
    val circadian: Float, val circadianRaw: CircadianWindow,
    val traffic: Float, val trafficRaw: TrafficCondition,
    val road: Float, val roadRaw: RoadType, val speedRaw: Float,
    val temp: Float, val tempRaw: Float,
    val baseline: Float, val sleepRaw: Float, val profileRaw: String,
    val total: Float
)
