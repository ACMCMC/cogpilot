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
```

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

### 8. **Query Acceleration Service Hints**
- ✅ Enabled results caching for repeated queries

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
- **Effective lever**: Discipline + routine language, animal welfare analogy
- **Trips**: 42 (0 high-risk)
- **Vocal energy baseline**: 0.81
- **Profile depth**: Early riser (05:30), responds to brief direct language

---

## Sample Queries (Run After Population)

### Find high-risk drivers
```sql
SELECT user_id, high_risk_trips, avg_trip_length_minutes 
FROM DRIVER_RISK_SUMMARY 
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

## Next Steps

1. ✅ Run the population script
2. 🔧 Build the **Decision Engine** (RiskStateCalculator) in Kotlin
3. 🎤 Integrate voice telemetry collection
4. 📊 Query driver profiles from app
5. 🧠 Feed LLM prompts with driver memory + current state
