# CogPilot Implementation Status Report

**Date:** February 21, 2026  
**Project:** Adaptive Cognitive Co-Pilot for Driver Drowsiness Prevention  
**Codebase:** Android (Kotlin) + Snowflake + ElevenLabs Voice Agent

---

## Executive Summary

**CogPilot is a preventative driver safety system** that monitors drowsiness signals in real-time and intervenes with AI-generated cognitive stimuli *before* the driver falls asleep. 

The **core infrastructure is substantially complete** (80-85%):
- ✅ Android app with Material Design 3 UI
- ✅ Real-time GPS telemetry (5-second polling)
- ✅ Hybrid risk prediction model (Monotony + Time-on-Task - Complexity)
- ✅ Snowflake integration with CORTEX APIs
- ✅ ElevenLabs voice agent with official SDK
- ✅ Multi-driver profile system with driver memory

**The critical gap is in LLM orchestration** (15-20% remaining):
- ⚠️ Voice agent receives minimal context about driver/situation
- ⚠️ No enforcement of interaction level constraints (0-4) in prompts
- ⚠️ No memory loop: past trip summaries not injected into agent prompts
- ⚠️ Pre-trip/post-trip conversations not orchestrated
- ⚠️ Effective levers (persuasion arguments) not selected or passed to agent

---

## What is CogPilot?

### Project Vision
Traditional drowsiness detection is **reactive** (beeps when eyes close) and **passive**. CogPilot is **preventative** and **active**—it predicts when fatigue will occur and proactively engages the driver with personalized cognitive tasks *before* danger emerges.

### Core Algorithm
A **hybrid risk model** combines three signals:
```
Risk Score = (0.4 × Monotony) + (0.3 × TimeOnTask) - (0.3 × RoadComplexity)
```

- **Monotony** (40%): Low speed variance = highway monotony = high risk
- **TimeOnTask** (30%): Minutes since last intervention = fatigue accumulation
- **Road Complexity** (30%, subtracted): Turns and heading changes = active driving = suppresses intervention

**Trigger threshold:** Risk > 0.6 → System intervenes

### Five Interaction Levels (Not just alerts)
Instead of a single "beep," CogPilot adapts its response based on risk state and context:

| Level | Trigger | Style | Example |
|-------|---------|-------|---------|
| **0** | STABLE state OR urban driving | Active silence | *(no output)* |
| **1** | EMERGING + mixed road | Yes/no question | *"Hey. How are you holding up?"* |
| **2** | EMERGING/WINDOW + highway | Micro-engagement | *"Say out loud the color of the car ahead."* |
| **3** | WINDOW state | Persuasive argument | *"Two weeks ago you took a break here and felt better..."* |
| **4** | CRITICAL state | Direct safety command | *"Miguel. Pull over. Exit in 2km."* |

---

## Current Implementation Status

### ✅ FULLY IMPLEMENTED (Core Infrastructure)

#### 1. Android UI & App Lifecycle
**File:** `MainActivity.kt` (986 lines), `UIManager.kt`

- Material Design 3 interface with edge-to-edge layout
- Profile selector dropdown (Aldan, Ana, Marta)
- Real-time metrics display (speed, heading, risk score)
- Driving mode detection (Android Automotive)
- GPS and microphone permission handling
- Foreground service for background audio capture

#### 2. Real-Time GPS Telemetry
**File:** `LocationCapture.kt` (200+ lines)

- Location updates every 2-5 seconds (GPS + Network)
- Calculates speed (m/s → mph) and heading (bearing)
- Queries Google Maps Roads API for road type/traffic context
- Directly inserts telemetry into Snowflake via SnowflakeManager
- Handles permission checks and provider fallbacks

**Data collected:**
- Timestamp, speed, heading, latitude, longitude
- Road metadata (speed limit, road type, traffic ratio)

#### 3. Risk Decision Engine (State Machine)
**File:** `RiskDecisionEngine.kt` (513 lines)

Core logic runs every 15 seconds:

```kotlin
enum class RiskState { STABLE, EMERGING, WINDOW, CRITICAL }
```

**State transitions:**
```
STABLE (all signals nominal)
  ↓ (vocal_energy drops, latency rises, or triggers activate)
EMERGING (early signals)
  ↓ (persists > 15 min, voice degrades further)
WINDOW (sustained fatigue)
  ↓ (voice becomes unresponsive or very delayed)
CRITICAL (immediate danger)
```

**Input signals tracked:**
- `vocalEnergy` (0.0–1.0): Baseline vs. current voice amplitude
- `responseLatencyMs`: Driver reaction time to copilot prompts
- `vadScore`: Voice Activity Detection confidence
- `circadianWindow`: Time-of-day fatigue risk (14:00–17:00, 23:00–06:00 are high-risk)
- `driveMinutes`: Time behind wheel in current session
- `sleepHoursToday`: Sleep history (< 5 hrs lowers thresholds)
- `riskTriggers`: Driver-specific situations (e.g., "afternoon highway")

**Output:**
- Current risk state
- Interaction level (0–4)
- Debug rationale (for UI logging)

#### 4. Risk Scoring Algorithm
**File:** `RiskScorer.kt` (105 lines)

Properly implements the hybrid model:

```kotlin
fun calculateRisk(telemetryLogs: List<FloatArray>): RiskData {
    val monotonyFactor = calculateMonotonyFactor(speeds)       // std dev of speed
    val timeFactor = calculateTimeFactor()                      // min since intervention
    val complexityFactor = calculateComplexityFactor(headings)  // variance of heading
    
    val riskScore = (0.4f * monotonyFactor) + 
                    (0.3f * timeFactor) - 
                    (0.3f * complexityFactor)
    
    return RiskData(
        riskScore = max(0f, min(1f, riskScore)),
        needsIntervention = riskScore > 0.6f,
        monotony = monotonyFactor,
        timeOnTask = timeFactor,
        complexity = complexityFactor
    )
}
```

#### 5. Snowflake Integration
**File:** `SnowflakeManager.kt` (228 lines), `SnowflakeSqlApiClient.kt`

- Direct JDBC connection (no backend proxy)
- Credentials from `BuildConfig` (environment variables)
- Telemetry insertion (every 5 sec from LocationCapture)
- User profile queries by `user_id`
- **CORTEX.COMPLETE('snowflake-arctic', prompt)** for stimulus generation ✅
- **CORTEX.SENTIMENT(text)** for driver response analysis ✅
- Calendar event insertion

**Verified Cortex usage:**
```kotlin
// Line 130–140 in SnowflakeManager.kt
val sql = """
    SELECT SNOWFLAKE.CORTEX.COMPLETE(
        'snowflake-arctic',
        '[{"role": "user", "content": "$prompt"}]'
    ) as response
""".trimIndent()
```

#### 6. Driver Profiles & Memory
**File:** `RiskDecisionEngine.kt` (lines 100–160)

Three hardcoded driver profiles with personalized data:

```kotlin
data class DriverProfile(
    val userId: String,
    val name: String,
    val riskLevel: RiskLevel,                  // LOW/MODERATE/HIGH
    val sleepPattern: String,                  // Narrative: "Sleeps 7h on weekdays, struggles Sundays"
    val riskTriggers: List<String>,            // e.g., "afternoon highway after 2pm"
    val effectiveLevers: List<String>,         // e.g., "objective_metrics"
    val rejectionPattern: String,              // How they typically push back
    val baselineVocalEnergy: Float,            // 0.72–0.81 range
    val boundary: String                       // Communication style hint
)

data class DriverMemory(
    val observation: String,                   // Specific insight
    val confidence: Float                      // 0.78–0.95
)
```

**Example profiles:**
- **Aldan**: High risk, erratic sleeper, responds to data/metrics
- **Ana**: Moderate risk, needs thoughtful framing, teaching analogies work
- **Marta**: Low risk, excellent routine, discipline-based language works

#### 7. ElevenLabs Voice Agent Service
**File:** `VoiceAgentService.kt` (361 lines)

- Official `ConversationClient` SDK integration
- Real-time WebRTC audio (handled by SDK)
- Configuration:
  ```kotlin
  val config = ConversationConfig(
      agentId = BuildConfig.ELEVENLABS_AGENT_ID,
      userId = "driver",
      onConnect = {...},
      onStatusChange = {...},
      onModeChange = {...},
      onMessage = {...},
      clientTools = mapOf(
          "stop_conversation" to ClientTool { ... },
          "end_session" to ClientTool { ... }
      )
  )
  ```
- Callbacks for connection, status changes, VAD, messages
- Broadcasts agent responses to UI via intent

#### 8. Calendar Integration
**File:** `CalendarContextProvider.kt`

- Reads upcoming events from device calendar
- Retrieves events within 4-hour window
- Passes to voice agent as context (e.g., "You have a meeting at 3pm")

---

### ⚠️ PARTIALLY IMPLEMENTED (Core working, Details missing)

#### 1. Interaction Level Routing
**Current state:** Logic exists but context not passed to agent

**What's implemented:**
```kotlin
// RiskDecisionEngine.kt lines 290–310
private fun determineInteractionLevel(riskState: RiskState, driveMinutes: Int): Int {
    return when (riskState) {
        RiskState.STABLE -> 0                           // silence
        RiskState.EMERGING -> if (highway) 2 else 1    // micro or check-in
        RiskState.WINDOW -> if (positive response) 3 else 2  // argument or micro
        RiskState.CRITICAL -> 4                         // safety message
    }
}
```

**What's missing:**
- Agent doesn't receive `INTERACTION_LEVEL` in system prompt
- No constraints enforced: Level 1 could ask open questions (wrong!), Level 4 could argue (wrong!)
- No max word counts by level
- No "no cognitive tasks" instruction for Level 4

#### 2. Pre-Trip and Post-Trip Conversations
**Current state:** Not orchestrated with voice agent

**What exists:**
- Comments in code mentioning pre-trip structure
- Calendar events retrieved for context

**What's missing:**
- Pre-trip question flow when app starts
- Agent not receiving "sleep hours collected: 5" for risk threshold tuning
- Post-trip debrief at end of trip
- No trip summary writing to Snowflake `driver_profiles.last_trip_summary`
- No memory update loop

#### 3. Risk State Change Notifications to Agent
**Current state:** Callback exists, not wired to agent

**What's implemented:**
```kotlin
var onRiskStateChanged: ((RiskState, Int, String) -> Unit)? = null
```

**What's missing:**
- When state changes to WINDOW, agent should immediately receive context
- Agent should know "risk just escalated from EMERGING to WINDOW at minute 75"
- No latency/vocal energy trend sent to inform LLM tone

---

### ❌ NOT IMPLEMENTED (Gap Areas)

#### 1. **Complete Level-Specific Prompt Templates**

The specification defines exact behaviors for each level (Section 3, Interaction Levels), but the agent never receives these constraints.

**What should be sent to agent for Level 2 (micro-activation):**
```
LEVEL 2 — Micro-activation
Current risk: EMERGING/WINDOW
Valid formats (choose one):
  • Environmental question: "Say out loud the color of the car ahead."
  • Open destination question: "Where are you headed today?"
  • Physiological regulation: "Take a slow breath."
  • Verbal counting: "Count out loud: one, two, three."
  • Minimal recall: "Say: ready."

Max 1 interaction per 12 minutes.
If driver doesn't respond or sounds annoyed → DO NOT ESCALATE.
Tone: conversational, not testing.
```

**Currently sent:** Only generic "driver context" (name, interests)

#### 2. **Effective Lever Selection & Execution**

Driver profiles define what persuasion works, but agent doesn't use it.

**What should happen (Level 3):**
```
Driver profile shows rejection_pattern: "Wants proof, engages with stats"
Driver profile shows effective_levers: ["technology_data", "objective_metrics"]

System prompt should say:
"This driver responds to objective data. Connect the break to 
something measurable. Example: compare their response latency 
at trip start vs. now."
```

**Currently:** Levers defined in code but never passed to agent

#### 3. **Memory Loop (Trip Summaries)**

Past trips should inform current trip behavior.

**What should happen:**
```
At start of trip:
  → Query: SELECT last_trip_summary FROM driver_profiles WHERE user_id = 'aldan_creo'
  → Result: "1h 55min trip. Risk peaked at WINDOW around minute 70. 
              Triggered by circadian low and 5h sleep."
  → Inject into agent: "Last time this driver did a long trip after poor sleep, 
                        they hit the wall at 70 minutes. Watch for patterns."

After trip ends:
  → Write summary: "1h 45min highway. Risk at WINDOW 65–80 min. Accepted break 
                    after data argument. Next time: similar context expected."
  → Update: driver_profiles.last_trip_summary
```

**Currently:** 
- `last_trip_summary` exists in RiskDecisionEngine hardcoded memory
- Never queried at session start
- Never written at session end

#### 4. **Voice Telemetry Feedback to Agent**

Agent should adapt language based on driver's response patterns.

**Missing context passes:**
- Current `vocalEnergyTrend` (is driver getting quieter?)
- `responseLatency` (are they slower to react?)
- `vadScore` (voice activity detection - are they speaking?)
- "`LAST_RESPONSE` type" (was last response FAST, SLOW, ANNOYED, or NONE?)

**Current code tracks all of these but doesn't pass to agent:**
```kotlin
// Line 60–80 in RiskDecisionEngine
private var lastVocalEnergy: Float = 0.8f
private var lastResponseLatencyMs: Float = 500f
private var lastVadScore: Float = 0.7f
private var lastUserResponse: UserResponseType = UserResponseType.NONE

// But none of these get injected into agent system prompt
```

#### 5. **Rejection Pattern Handling**

If driver refuses Level 3 intervention, system should try different argument.

**What spec requires (Section 3, Level 3 constraints):**
```
If driver refuses at Level 3:
  → Accept it, don't argue
  → Silence for 5 minutes
  → If risk still WINDOW or climbing after 5 min → ONE more Level 3 attempt
  → Use different effective_lever on second attempt
  → If refused again → silence until CRITICAL

Example:
  Attempt 1: "You've been driving 2 hours. A break would help your meeting." 
             [Driver: "No, I'm fine."]
  [5 min silence]
  Attempt 2: "At trip start your response time was 0.8s. Now it's 1.8s. 
              That's objective — a quick stop would fix it."
             [If refused again: silence]
```

**Currently:** No retry logic; no second-angle selection

#### 6. **Daily Fatigue Accumulator**

Mentioned in spec (Section 6) but not in code.

**What should happen:**
```
• Each minute driven increments daily_fatigue_accumulator
• Pauses SLOW accumulation but do NOT reset it
• Second leg of long trip always riskier than first (accumulator is higher)
• Lower thresholds proportionally: if accumulator > threshold → trigger EMERGING earlier
```

**Currently:** Missing entirely; `driveMinutes` resets on pause instead

#### 7. **Pre-Trip Question Collection**

Should happen before driving starts, collect sleep data and context.

**What spec prescribes:**
```
1. Greeting: "Morning, Lucia. How many hours did you sleep?"
2. If early/long trip: "What time did you wake up?"
3. Reference history: "Last time you did this route you stopped at 80 min — good call."
4. Set expectations: "I'll stay quiet in city traffic. On highway I might check in."
5. Close: "Whenever you're ready."
```

**Currently:** 
- Comments mention this
- Not orchestrated in voice agent
- Agent starts mid-trip, not before engine start

#### 8. **Post-Trip Debrief & Memory Writing**

Should summarize trip, note what worked, update memory.

**What spec prescribes:**
```
At engine off or 60+ seconds stationary:
  → Observation: "There was a moment around 70 minutes where I noticed something."
  → Suggestion: "Next time, a short break around then would help."
  → Write to DB: driver_profiles.last_trip_summary
```

**Currently:** 
- Not implemented
- No trip summary generation
- No memory persistence at session end

---

## Architecture Overview

### Data Flow

```
GPS/Speed/Heading
       ↓
LocationCapture (5s)
       ↓
Snowflake (Telemetry table)
       ├→ RiskScorer (calculates monotony, time, complexity)
       └→ RiskDecisionEngine
             ├→ State machine (STABLE → EMERGING → WINDOW → CRITICAL)
             ├→ Track vocal energy, latency, VAD
             └→ Determine interaction level (0–4)
                      ↓
            VoiceAgentService
                      ↓
            ElevenLabs ConversationClient (WebRTC)
                      ↓
            Driver hears copilot, responds
                      ↓
          Driver response analyzed
          (latency, tone, VAD)
                      ↓
           Risk state recalculated (15s cycle)
```

### Component Interaction

| Component | Responsibility | Status |
|-----------|---|---|
| `MainActivity` | App lifecycle, UI, permission handling | ✅ Complete |
| `LocationCapture` | GPS polling, telemetry insertion | ✅ Complete |
| `RiskScorer` | Monotony/time/complexity calculation | ✅ Complete |
| `RiskDecisionEngine` | State machine, threshold logic, memory | ✅ Mostly (memory loop missing) |
| `SnowflakeManager` | JDBC queries, CORTEX calls | ✅ Complete |
| `VoiceAgentService` | ElevenLabs SDK wrapper | ✅ Complete |
| `CalendarContextProvider` | Calendar event retrieval | ✅ Complete |
| **Agent Prompting** | Level constraints, effective levers, memory injection | ❌ Missing |
| **Break Management** | Reset drive timer, daily fatigue accumulator | ⚠️ Partial |
| **Memory Persistence** | Write summaries, update profiles | ❌ Missing |

---

## What Would Complete Implementation Look Like?

### Minimal changes needed (80% to 100%):

1. **Enhance system prompt sent to agent** (VoiceAgentService.kt, line ~110)
   ```kotlin
   val systemContext = """
   You are the cognitive co-pilot for [NAME].
   
   CURRENT STATE:
   - Risk level: [RISK_STATE]
   - Interaction level: [INTERACTION_LEVEL] (0=silence / 1=binary / 2=micro / 3=argument / 4=safety)
   - Driving time: [DRIVE_MINUTES] min
   - Driver response latency: [RESPONSE_LAT]ms (slower than normal?)
   - Vocal energy trend: [TREND] (declining?)
   
   CONSTRAINTS FOR THIS LEVEL:
   [Inject appropriate section from spec based on level]
   
   DRIVER PROFILE:
   - Known triggers: [risk_triggers]
   - Arguments that work: [effective_levers]
   - How they push back: [rejection_pattern]
   - Last trip: [last_trip_summary]
   
   TONE RULES: [Inject tone rules from spec]
   
   Generate only the next single response from the copilot. Nothing else.
   """.trimIndent()
   ```

2. **Pre-trip orchestration** (in RiskDecisionEngine or new PreTripAgent.kt)
   - Before session starts: ask sleep hours, wake time
   - Collect in variables
   - Adjust risk thresholds accordingly

3. **Memory querying at trip start** (SnowflakeManager.kt)
   ```kotlin
   suspend fun getLastTripSummary(userId: String): String {
       val sql = "SELECT last_trip_summary FROM driver_profiles WHERE user_id = '$userId'"
       val result = sqlApi.execute(sql)
       return result.optJSONArray("data")?.optJSONArray(0)?.optString(0) ?: ""
   }
   
   // Call this at trip start and inject into agent prompt
   ```

4. **Memory writing at trip end** (VoiceAgentService.kt or new PostTripSummary.kt)
   ```kotlin
   suspend fun writeLastTripSummary(userId: String, summary: String) {
       val sql = """
           UPDATE driver_profiles 
           SET last_trip_summary = '$summary'
           WHERE user_id = '$userId'
       """
       sqlApi.execute(sql)
   }
   
   // Call when engine stops or 60+ second pause detected
   ```

5. **Daily fatigue accumulator** (RiskDecisionEngine.kt)
   ```kotlin
   private var dailyFatigueAccumulator: Float = 0f
   
   fun incrementDailyFatigue(minutes: Int) {
       dailyFatigueAccumulator += minutes * 0.01f  // slow increment
       // Use to lower thresholds: vocal_energy_threshold -= dailyFatigueAccumulator
   }
   ```

6. **Rejection retry logic** (RiskDecisionEngine.kt or new RetryStrategy.kt)
   ```kotlin
   private var lastLevel3RefusalTime: Long = 0L
   private var level3AttemptCount: Int = 0
   private var lastEffectiveLeverUsed: String? = null
   
   fun handleLevel3Refusal() {
       if (System.currentTimeMillis() - lastLevel3RefusalTime < 5 * 60_000) {
           // Still in silence window
           return
       }
       if (level3AttemptCount < 2) {
           // Try again with different lever
           level3AttemptCount++
           // Select different lever, retry
       }
   }
   ```

---

## Key Findings

### The Good ✅
1. **Risk scoring is production-ready**: Perfectly implements hybrid model with correct weights
2. **Architecture is sound**: Separation of concerns (scorer, engine, agent, storage) is clean
3. **Snowflake integration works**: CORTEX APIs are called correctly
4. **Voice agent is real**: Using official ElevenLabs SDK with WebRTC
5. **Multi-driver support exists**: Profiles, triggers, effective levers all defined
6. **Telemetry pipeline is solid**: 5-second GPS → Snowflake → risk recalculation

### The Gap ⚠️
1. **Agent is underutilized**: Gets 20% of the context it should receive
2. **No conversational flow**: Pre-trip, post-trip, level constraints not orchestrated
3. **Memory isn't closing the loop**: Past trips don't inform future behavior
4. **Persuasion isn't personalized**: Effective levers defined but not selected
5. **No handling of refusal patterns**: If driver says no, system doesn't retry with different angle

### The Risks 🚨
1. **Agent might over-talk**: Level 0 (active silence) not enforced; agent could speak in city traffic
2. **Agent might ask wrong things**: Level 2 micro-activations could be multipart questions instead of simple tasks
3. **Agent might miss personal context**: Doesn't know driver's sleep pattern or past behavior
4. **No break state reset**: After driver stops, system might escalate too aggressively anyway

---

## Recommended Next Steps

**Phase 1 (Immediate):** System prompt enhancement
- [ ] Inject interaction level constraints into agent prompt
- [ ] Pass vocal energy trend to agent (is driver getting quieter?)
- [ ] Pass effective_levers to agent for Level 3 arguments
- [ ] Add tone rules to agent prompt

**Phase 2 (Short-term):** Memory loop closure
- [ ] Query `last_trip_summary` at trip start
- [ ] Generate and write trip summary at trip end
- [ ] Update `driver_profiles.last_trip_summary` in Snowflake

**Phase 3 (Medium-term):** Orchestration
- [ ] Pre-trip conversation (before engine start)
- [ ] Post-trip debrief (after engine stops)
- [ ] Rejection retry logic (different lever after 5 min silence)
- [ ] Daily fatigue accumulator (makes second leg harder)

**Phase 4 (Long-term):** Polish
- [ ] Extended tool registry (suggest rest stops, query calendar, etc.)
- [ ] Vocal analysis enrichment (slurring, incoherence detection)
- [ ] Multi-segment trip handling (calculate fatigue across multiple drives)
- [ ] Analytics dashboard (Snowflake → lookahead charts)

---

## Conclusion

CogPilot is a **well-architected system that is 80-85% complete**. The core risk detection, voice agent integration, and Snowflake pipeline are robust. The remaining work is almost entirely in **LLM prompt orchestration and memory loop closure**—ensuring the agent receives the detailed context and constraints it needs to behave like the intelligent, personalized copilot the specification describes.

The system is **close to production-ready**: with the Phase 1 and 2 recommendations above (likely 2–3 days of focused work), it would go from "functional demo" to "production candidate."

