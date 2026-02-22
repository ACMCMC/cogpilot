# CogPilot: High-Fidelity Active Driver Safety

**Hackathon Category:** Reinventing the Wheel — Active Drowsiness Prevention
**Tracks:** ❄️ Best Use of Snowflake | 🎙️ Best Use of ElevenLabs

> **The "Reinvention":** Traditional drowsiness detection is **reactive** (beeps when your eyes close) and **passive**. CogPilot is **preventative** and **active**—it predicts monotony fatigue using a hybrid risk model (Math + ML) and intervenes with personalized conversational AI to keep the driver's brain engaged *before* they fall asleep.

---

## 🎯 Project Overview

CogPilot is a full-stack Android Automotive system that prevents driver drowsiness by:

1. **Monitoring** real-time vehicle telemetry (CarHardware chassis speed, heading, GPS) and live voice acoustics (MicrophoneAnalyzer).
2. **Predicting** drowsiness risk using a **50/50 Blended Risk Engine** (Deterministic Math + ONNX Random Forest).
3. **Intervening** with AI-generated cognitive stimuli via ElevenLabs Real-Time Conversational SDK.
4. **Engaging** the driver uniquely based on personalized Snowflake driver profiles and semantic memory.

### Key Features
- ✅ **Real-Time ElevenLabs Client Tools** - AI accesses Google Maps, live driving status, and judges driver attention.
- ✅ **Concurrent Audio Capture** - Runs ONNX ML models on the mic stream *simultaneously* with the active ElevenLabs voice call.
- ✅ **Snowflake Semantic Memory** - Embeds transcripts via Cortex (`snowflake-arctic-embed-m`) for long-term vector recall.
- ✅ **Snowflake Cortex LLM** - `CORTEX.COMPLETE('snowflake-arctic')` for summarizing trips and `CORTEX.SENTIMENT` for emotional analysis.
- ✅ **Direct Snowflake JDBC** - Android talks directly to the warehouse, removing middleman backend latency.
- ✅ **Distraction-Optimized UI** - Material Design 3 interface for Android Auto, strictly complying with Car App Library guidelines.
- ✅ **Acoustic Signal Processing** - Custom Kotlin FFT extracts 29 audio features (ZCR, RMSE, Spectral Centroid) live.

---

## 🏗️ Advanced Architecture

```
┌────────────────────────────────────────────────────────────────────────┐
│              Android Automotive App (Kotlin + Material 3)              │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │ VoiceAgentService (ElevenLabs Conversational SDK)                │  │
│  │  ├─ Client Tools (Google Maps, Risk Status, Attention Judge)     │  │
│  │  └─ WebSocket Hooks (Speech Pace WPS, Response Latency)          │  │
│  ├──────────────────────────────────────────────────────────────────┤  │
│  │ RiskDecisionEngine (50/50 Blended Detection)                     │  │
│  │  ├─ Deterministic: Speed Variance + Time + Circadian + Traffic   │  │
│  │  └─ ONNX Runtime: Random Forest Acoustic Classifier              │  │
│  ├──────────────────────────────────────────────────────────────────┤  │
│  │ MicrophoneAnalyzer (Concurrent Audio Capture API 29+)            │  │
│  │  └─ AudioFeatureExtractor (FFT, Spectral Analysis, 29 Features)  │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                         ↓ JDBC                                         │
└────────────────────────────────────────────────────────────────────────┘
                          ↓
         ┌─────────────────────────────────────────────────┐
         │          Snowflake Cloud Data Platform          │
         │  ┌───────────────────────────────────────────┐  │
         │  │ DRIVER_LOGS (5s Telemetry, High Volume)   │  │
         │  │ TRIP_SUMMARIES (Arctic Generated)         │  │
         │  │ CONVERSATION_MEMORY (Embedded Vectors)    │  │
         │  │ USER_PROFILES (Persuasion Levers)         │  │
         │  └───────────────────────────────────────────┘  │
         │                                                 │
         │  CORTEX APIs:                                   │
         │  ├─ VECTOR_COSINE_SIMILARITY()                  │  │
         │  ├─ CORTEX.SENTIMENT()                          │  │
         │  ├─ CORTEX.EMBED_TEXT_768()                     │  │
         │  └─ CORTEX.COMPLETE('snowflake-arctic')         │  │
         └─────────────────────────────────────────────────┘
```

---

## 🎙️ ElevenLabs Track: Advanced Agentic Interaction

CogPilot utilizes the ElevenLabs Real-Time Conversational SDK (0.7.2) to transform a voice agent into a high-precision car co-pilot, possessing true situational awareness.

### 1. Agentic Sensory Suite (Client Tools)
The agent isn't just speaking; it's *viewing* the car's state. We implemented a sophisticated client-tool registry:
- **`get_driving_status`**: Real-time extraction of vehicle speed, road type (Highway/Urban), and traffic density.
- **`search_nearby_places`**: Integration with Google Maps Places API. The agent can search for "rest areas" or "coffee" within a 10km radius, calculate straight-line distances in real-time using Haversine formulas, and present options verbally.
- **`update_agentic_attention_score`**: The agent acts as a subjective judge. During the conversation, it explicitly evaluates the driver's coherence and updates their global risk score via this tool.
- **`search_past_conversations`**: Allows the AI to query Snowflake's vector database to recall past topics of discussion.

### 2. Micro-Telemetry Analysis & WebSocket Hooks
We hooked directly into the ElevenLabs WebSocket `onMessage` stream (specifically `user_transcript` events) to derive real-time cognitive metrics:
- **Speech Pace (WPS)**: Dynamically calculated Words-Per-Second. A drop in speech pace triggers a fatigue penalty.
- **Response Latency Histogram**: Tracks the precise millisecond delta between the agent finishing speech and the driver starting. Increasing latency trends are treated as high-priority cognitive load signals.
- **Vocal Energy Mapping**: Monitors VAD (Voice Activity Detection) levels to detect slurring or volume drops.

### 3. Context Injection (Zero-Silence Startup)
To eliminate AI "thinking" time at session start, we pre-inject live driving telemetry (Speed, Traffic, Circadian Window, aggregated driving hours) and calendar events directly into ElevenLabs `dynamicVariables`. The agent wakes up already knowing exactly where you are, how tired you feel, and your upcoming schedule.

---

## ❄️ Snowflake Track: The Semantic Brain

CogPilot uses Snowflake not just as a telemetry sink, but as a real-time memory and reasoning engine.

### 1. Long-Term Semantic Memory Loop
CogPilot doesn't forget. We built a complete vector-based memory system directly in Snowflake:
- **Embedding Generation**: At the end of every trip, raw timed-conversation transcripts are vectorized using `SNOWFLAKE.CORTEX.EMBED_TEXT_768`.
- **Vector Storage**: Resulting embeddings are saved to the `CONVERSATION_MEMORY` table alongside the driver ID.
- **Semantic Retrieval**: Drivers can ask, "What did we talk about last time I was this tired?" The AI invokes the `search_past_conversations` tool, which executes a `VECTOR_COSINE_SIMILARITY` scan across years of driving history to retrieve relevant context.

### 2. Cortex AI Orchestration
- **Arctic LLM Summarization**: Every driving session is summarized using the `snowflake-arctic` model. These summaries condense minutes of spoken dialogue into actionable driver insights saved to the `TRIP_SUMMARIES` table, which primes the agent for the *next* trip.
- **Sentiment Analysis**: Driver utterances are passed through `CORTEX.SENTIMENT` to detect frustration or anomalies, allowing the agent to adapt its tone or escalate to a high-risk warning.

### 3. High-Fidelity Personalization
Driver profiles are managed as Snowflake records. The AI adapts its "intervention style" based on columns such as `EFFECTIVE_LEVERS` (e.g., *uses metrics*, *prefers analogies*) and `REJECTION_PATTERNS`. Marta gets a direct, metrics-driven approach, while Ana gets thoughtful, story-based interactions.

---

## 🏎️ Hybrid Risk Model (Math + ML)

The core "Reinvention" is replacing reactive camera-based detection (which fails for sunglasses and night driving) with a **50/50 Blended Risk Engine** that fuses deterministic physics with live Acoustic Machine Learning.

### 1. Deterministic Math Model (Baseline)
- **Monotony Factor**: Calculated via rolling standard deviation of vehicle speed. High speed + low variance = high monotony (highway hypnosis).
- **Circadian Window**: Time-aware weight mapping. The system inherently increases base risk during the known danger window of 2 AM - 5 AM.
- **Duration Multiplier**: Logarithmic fatigue accumulation ($D(t) = 0.3 \cdot \ln(1+t)/\ln(6)$) based on continuous driving time.
- **Telemetry Priority**: Utilizes the `CarHardware` API for direct chassis speed, falling back to OS GPS only if the vehicle signal is stale.

### 2. ONNX Real-Time Inference (ML)
We integrated a Random Forest drowsiness classifier converted to ONNX, analyzing the driver's voice while they talk to ElevenLabs.
- **Concurrent Audio Capture**: On Android 10+ (API 29), we use the `VOICE_RECOGNITION` audio source. This bypasses Android's single-mic limitation, allowing CogPilot's `MicrophoneAnalyzer` loop to capture raw PCM audio *without silencing* the active ElevenLabs WebRTC session.
- **Acoustic Feature Extraction**: Implemented a custom complete Kotlin Audio Processing pipeline that performs real-time FFT (Fast Fourier Transform). It extracts 29 distinct features used by the model: Spectral Centroid, Zero Crossing Rate (ZCR), Root Mean Square Energy (RMSE), and Frequency Band Energy.
- **Blended Score**: The final score is a literal average: `(0.5 * MathModel) + (0.5 * OnnxProbability)`.

### 3. KSS Standard & Strict Suppression
- Risk scores are mapped to the **Karolinska Sleepiness Scale (1-9)**, the clinical standard for fatigue research.
- **Logarithmic Suppression Formula**: Interventions are spaced using a suppression multiplier $S(t) = \ln(1+t)/\ln(61)$. This enforces strict cooldowns (minimum 1 minute between check-ins) to ensure CogPilot remains a helpful partner, not a nagging distraction.

---

## 📊 Snowflake Schema

```sql
CREATE TABLE DRIVER_LOGS (
    id INTEGER AUTOINCREMENT,
    timestamp BIGINT NOT NULL,           
    speed FLOAT NOT NULL,                 
    heading FLOAT NOT NULL,               
    latitude FLOAT NOT NULL,
    longitude FLOAT NOT NULL,
    risk_score FLOAT,                     
    created_at TIMESTAMP_NTZ DEFAULT CURRENT_TIMESTAMP(),
    PRIMARY KEY (id)
);

CREATE TABLE USER_PROFILE (
    driver_id VARCHAR PRIMARY KEY,
    name VARCHAR,
    effective_levers VARCHAR,             -- E.g. "metrics, science"
    rejection_pattern VARCHAR,
    sleep_pattern VARCHAR
);

CREATE TABLE CONVERSATION_MEMORY (
    id INTEGER AUTOINCREMENT,
    driver_id VARCHAR,
    raw_transcript VARCHAR,
    embedding VECTOR(FLOAT, 768),         -- Cortex Embedded
    created_at TIMESTAMP_NTZ DEFAULT CURRENT_TIMESTAMP()
);

CREATE TABLE TRIP_SUMMARIES (
    driver_id VARCHAR,
    summary_text VARCHAR,                 -- Snowflake Arctic Output
    created_at TIMESTAMP_NTZ
);
```

---

## 📱 Automotive UI/UX & Compliance

- **MediaBrowserService Integration**: Shifted the app's automotive category to `Media`. CogPilot appears as a native Media App on Android Automotive head units.
- **Native Playback Resumption**: The agent broadcasts a standard `KEYCODE_MEDIA_PLAY` intent instantly when the interaction ends, gracefully resuming whatever background media (Spotify, Podcasts) was previously playing.
- **Headless Startup Support**: Refined the service architecture to initialize and verify credentials in the background completely headless, fulfilling strict Android Auto guidelines.
- **Explicit Mathematical UI**: Designed the Android Auto media player subtitle to render the exact underlying risk decision mathematics instead of abstract concepts. It displays:
  `Base: 0.12 + Env: 0.05 | Pace: 2.5wps | Agent: 0.50 | ONNX: 0.10 = Total Risk`
- **Distraction Optimized**: All car-facing XML and Compose components are explicitly tagged `distractionOptimized` for safety compliance, favoring high-contrast dark modes and minimal glance times.

---

## 🚀 Quick Start / Setup

### Prerequisites
- Android Studio Giraffe or later
- JDK 11+
- Android SDK 34+
- Snowflake account (Account URL, User/Pass, WH, DB)
- ElevenLabs API Key & Agent ID
- Google Maps SDK Key

### Configuration
Update the following keys in your environment or directly in the respective Manager classes:
1. `app/src/main/java/fyi/acmc/cogpilot/SnowflakeManager.kt` -> Set JDBC URL and credentials.
2. `app/src/main/java/fyi/acmc/cogpilot/GoogleMapsManager.kt` -> Set Google Maps API Key.
3. `app/src/main/java/fyi/acmc/cogpilot/VoiceAgentService.kt` -> Set ElevenLabs Agent ID.

### Building
```bash
# Clone
cd /Users/acmc/cogpilot

# Build APK
./gradlew build

# Install on Emulator
./gradlew installDebug

# Start Headless
adb shell am start -n fyi.acmc.cogpilot/.MainActivity
```

### ⚠️ Crucial: Android Auto Visibility
Since this is a sideloaded debug build, Android Auto **will hide CogPilot** by default.
1. **Open Android Auto Settings**: Go to Settings -> Apps -> Android Auto -> Additional settings in the app.
2. **Enable Developer Mode**: Scroll to the bottom and tap **Version** 10 times.
3. **Enable Unknown Sources**: Tap the three-dot menu (top right) -> **Developer settings** -> Check **Unknown sources**.
4. **Restart**: Disconnect from the car and restart your phone.

---

## 🧪 Verification Logcat

Watch these tags during a drive to see the system in action:
- `LocationCapture`: Polls GPS/chassis speed every 5 seconds.
- `RiskDecisionEngine`: Outputs the blended 50/50 KSS score.
- `MicrophoneAnalyzer`: `🎙️ Real-time audio capture started (Concurrent Mode)` followed by `🤖 ONNX Model Inference Result: 0.35` every 5 seconds.
- `VoiceAgentService`: Shows websocket parsing for `WPS` and `Latency`, and displays all Tool Calls (e.g., `📢 Agent requested get_driving_status`).

---

**Built with ❤️ for CrimsonCode Hackathon 2026**
*"CogPilot: Predicting fatigue, not just detecting it. Powered by Snowflake and ElevenLabs."*
