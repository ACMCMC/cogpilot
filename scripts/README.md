# Drowsiness Detection ML Models (Consolidated)

**Project:** CogPilot — Driver Drowsiness Detection
**Last updated:** February 22, 2026

This README consolidates the final ML workflow, experiment summaries, and deployment artifacts. The canonical deployment artifacts are the ONNX models (`sleepiness_detector.onnx`, `emotion_classifier.onnx`). Old sklearn pickle files were removed from `scripts/` to avoid accidental deployment.

See `EXPERIMENT_RESULTS.md` for full ablation and evaluation details, and `ONNX_DEPLOYMENT.md` for Android integration examples.
# CogPilot Snowflake Population Script

Comprehensive CLI tool to populate Snowflake with realistic driver profile data, trip history, telemetry, and interaction logs. **Uses advanced Snowflake features for production-grade analytics.**

## Setup

### Prerequisites

```bash
# Python 3.8+
pip install snowflake-connector-python python-dotenv
```

### Configure Credentials

1. Copy `.env.example` to `.env`:
```bash
cp scripts/.env.example scripts/.env
```

2. Edit `.env` with your Snowflake credentials:
```
SF_USER=javier@mycompany.com
SF_PASSWORD=YourSecurePassword
SF_ACCOUNT=ab12345.us-east-1  # e.g. ab12345.us-east-1
SF_WAREHOUSE=COMPUTE_WH
SF_DATABASE=COGPILOT
```

## Usage

### Full population (from scratch)

```bash
python scripts/populate_snowflake.py --clear
```

This will:
1. ✅ Drop existing tables/views
2. ✅ Create advanced Iceberg tables with clustering
3. ✅ Populate 3 detailed driver personas
4. ✅ Generate realistic trip history (84 total trips)
5. ✅ Insert time-series telemetry data (clustered on user_id + date)
6. ✅ Log interactions with conversation history
7. ✅ Store driver memory observations

### Update only (keep existing data)

```bash
python scripts/populate_snowflake.py
```

### Custom warehouse/database

```bash
python scripts/populate_snowflake.py --warehouse PROD_WH --database COGPILOT_PROD
Train a machine learning model to detect drowsiness from audio using **proxy learning** with the RAVDESS emotion dataset. Low arousal emotions (calm, neutral, sad) proxy for sleepy states, high arousal emotions (happy, angry, fearful) proxy for alert states.

## Advanced Snowflake Features Used 🏆

### 1. **Iceberg Tables** (`TELEMETRY`)
- ✅ ACID transactions and time-travel
- ✅ Schema evolution support
- ✅ Partition pruning for performance
- ✅ Data versioning for audits

### 2. **Clustering** 
- ✅ TELEMETRY: clustered on `(user_id, DATE(timestamp_ms))`
  - Fast queries filtering by driver over time ranges
  - Automatic re-clustering as data grows
- ✅ USERS: clustered on `user_id`

### 3. **VARIANT Columns** (`USER_PROFILE.profile`)
- ✅ Semi-structured JSON for flexible driver attributes
- ✅ No schema-change overhead as profiles evolve
- ✅ Full support for nested queries (`profile.sleep_pattern`)

### 4. **Geospatial Functions**
- ✅ GEOGRAPHY columns for lat/lon (`location`, `start_location`, `end_location`)
- ✅ Ready for ST_DISTANCE queries: *"Find all rest stops within 5km of driver's path"*

### 5. **Materialized Views** (pre-computed aggregates)
- ✅ `DRIVER_RISK_SUMMARY`: Trip counts, high-risk trip counts, avg metrics per driver
  - Used by app to display "High risk trip count: 6"
- ✅ Refreshed on-demand or scheduled

### 6. **Dynamic Tables** (live time-window aggregates)
- ✅ `CURRENT_TRIP_METRICS`: Real-time rolling 1-hour telemetry summary
  - Automatically refreshes every 5 minutes
  - Shows current vocal energy, latency trends, risk state

### 7. **Data Tagging** (governance + lineage)
- ✅ Tag all tables as PII/SENSITIVE for compliance
- ✅ Enables Snowflake data governance workflows

---

## Data Loaded: 3 Personas

### 🔴 **Aldan Creo** (HIGH RISK)
- **Interest**: Public transport, AI, data infrastructure
- **Risk trigger**: Afternoon highway drives (14:00-17:00) with <6hrs sleep
- **Effective lever**: Objective data + metrics comparisons
- **Trips**: 24 (6 high-risk)
- **Vocal energy baseline**: 0.72
- **Profile depth**: Extensive; uses tech analogies, wants proof over directives

### 🟡 **Ana Campillo** (MODERATE RISK)
- **Interest**: Creative writing, education, philosophy, life lessons
- **Risk trigger**: Post-writing fatigue, difficult discussions
- **Effective lever**: Self-care framing, teaching analogy, personal growth
- **Trips**: 18 (2 high-risk)
- **Vocal energy baseline**: 0.68
- **Profile depth**: Thoughtful decision-maker, responds to open questions

### 🟢 **Marta Sanchez** (LOW RISK)
- **Interest**: Biology, animals, tennis, farm life (from Utah)
- **Risk trigger**: Very rare (~1x per 40 trips after 18+ hour lab days)
- **Vocal energy baseline**: 0.81
- **Profile depth**: Early riser (05:30), responds to brief direct language

---

## Sample Queries (Run After Population)

### Find high-risk drivers
```sql
SELECT user_id, high_risk_trips, avg_trip_length_minutes 
WHERE high_risk_trips > 3 
ORDER BY high_risk_trips DESC;
```

### Live vocal energy trends (last 1 hour)
```sql
SELECT user_id, trip_id, max_vocal_energy, avg_vocal_energy, current_risk_state
FROM CURRENT_TRIP_METRICS;
```

### Driver memory: effective levers
```sql
SELECT user_id, observation_text, confidence
FROM DRIVER_MEMORY 
WHERE observation_type = 'EFFECTIVE_ARGUMENT'
ORDER BY confidence DESC;
```

### Geospatial: trips near home
```sql
SELECT trip_id, user_id, ST_DISTANCE(start_location, ST_POINT(-117.43, 47.66)) as distance_m
FROM TRIPS 
WHERE distance_m < 5000
ORDER BY distance_m;
```

---

## Troubleshooting

### Connection Failed
```
ERROR: Snowflake connection failed: Invalid user/password or account
→ Check .env credentials, ensure Snowflake account is accessible
```

### Table Already Exists
```
→ Use --clear flag to drop + recreate:
  python scripts/populate_snowflake.py --clear
```

### VARIANT Parsing Error
```
→ Snowflake version might be older; VARIANT columns require recent release
→ Run: SELECT CURRENT_VERSION();
```

---

## Output

Success looks like:

```
2026-02-21 14:42:15 | INFO | ✓ Connected to Snowflake: ab12345.us-east-1/COGPILOT
2026-02-21 14:42:16 | INFO | Creating advanced Snowflake schema...
2026-02-21 14:42:18 | INFO | ✓ TELEMETRY table created (Iceberg, clustered)
2026-02-21 14:42:19 | INFO | Populating USERS table...
2026-02-21 14:42:20 | INFO |   ✓ Aldan (aldan_creo) - Risk: HIGH
2026-02-21 14:42:21 | INFO | ... [continues]
2026-02-21 14:42:45 | INFO | ======================================================================
2026-02-21 14:42:45 | INFO | ✓ POPULATION COMPLETE
2026-02-21 14:42:45 | INFO |   Users: 3
2026-02-21 14:42:45 | INFO |   Trips: 84
2026-02-21 14:42:45 | INFO |   Profiles: Aldan (HIGH risk), Ana (MODERATE), Marta (LOW)
2026-02-21 14:42:45 | INFO | ======================================================================
```

---

## Drowsiness Detection Model Training

### Overview

Train a machine learning model to detect drowsiness from audio using **proxy learning** with the RAVDESS emotion dataset. Low arousal emotions (calm, neutral, sad) proxy for sleepy states, high arousal emotions (happy, angry, fearful) proxy for alert states.

### ⚠️ IMPORTANT: Use REAL RAVDESS Data

**DO NOT use synthetic data.** You need the actual RAVDESS dataset with real human speech.

### Setup

```bash
# Install ML dependencies
pip install -r requirements_ml.txt
```

### Get RAVDESS Dataset (REQUIRED)

**You MUST download the real RAVDESS dataset from Kaggle:**

1. **Go to Kaggle**: https://www.kaggle.com/datasets/uwrfkaggle/ravdess-emotional-speech-audio
2. **Download** `Audio_Speech_Actors_01-24.zip` (~200MB)
3. **Unzip** into `scripts/ravdess_data/`:

```bash
cd scripts
unzip ~/Downloads/Audio_Speech_Actors_01-24.zip -d ravdess_data/
```

**Verify structure:**
```bash
ls ravdess_data/
# Should see: Actor_01/ Actor_02/ ... Actor_24/
```

### Train the Model

```bash
python train_drowsiness_model.py
```

**Output:**
- `sleepiness_detector.pkl` - trained Random Forest model
- `feature_scaler.pkl` - feature normalization stats
- Accuracy report printed to console

**Expected accuracy:** 70-90% (using real emotion as proxy for drowsiness)

### Test the Model

```bash
# Test on a single audio file
python test_drowsiness_model.py ravdess_data/Actor_01/03-01-02-01-01-01-01.wav
```

**Output:**
```
Analyzing: ravdess_data/Actor_01/03-01-02-01-01-01-01.wav
Prediction: SLEEPY
Confidence: 87.45%
Model is: Very confident
```

### Technical Details

**Algorithm:** Random Forest Classifier (150 trees)
**Features:** 40 MFCCs + 12 Chroma features = 52 features per audio sample
**Proxy Labels:**
- Sleepy (1): Neutral, Calm, Sad emotions
- Alert (0): Happy, Angry, Fearful, Surprised emotions

**Rationale:** Drowsiness manifests acoustically as low arousal: monotone pitch, slower articulation, reduced energy. These characteristics overlap with low-arousal emotions in the Circumplex Model of Affect.

### Integration with Android

Once model is trained and validated:
1. Export model to TensorFlow Lite format (future work)
2. Integrate into VoiceAgentService.kt for real-time inference
3. Combine with vocal energy and response latency signals

---

## Next Steps

1. ✅ Run the population script
2. ✅ Train drowsiness detection model
3. 🔧 Build the **Decision Engine** (RiskStateCalculator) in Kotlin
4. 🎤 Integrate voice telemetry collection
5. 📊 Query driver profiles from app
6. 🧠 Feed LLM prompts with driver memory + current state
