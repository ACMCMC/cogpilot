#!/usr/bin/env python3
"""
CogPilot Snowflake Population Script

Advanced features used:
- ICEBERG tables for ACID transactions and time-travel
- Clustering for time-series telemetry optimization
- VARIANT columns for flexible, semi-structured driver profiles
- Materialized views for real-time risk aggregation
- Dynamic tables for live trip analysis
- Geospatial functions (ST_DISTANCE) for location analytics
- Tagging for data governance and lineage
- Query acceleration service hints

Usage:
    python populate_snowflake.py --warehouse <name> --user <user> --clear
"""

import os
import json
from datetime import datetime, timedelta
import argparse
import logging
from typing import Dict, List
from dotenv import load_dotenv

try:
    import snowflake.connector
    from snowflake.connector import DictCursor
except ImportError:
    print("ERROR: snowflake-connector-python not installed")
    print("Install with: pip install snowflake-connector-python")
    exit(1)

logging.basicConfig(level=logging.INFO, format='%(asctime)s | %(levelname)s | %(message)s')
logger = logging.getLogger(__name__)

load_dotenv()

# ============================================================================
# ADVANCED SNOWFLAKE SCHEMA DEFINITION
# ============================================================================

SCHEMA_SETUP = """
-- Enable query acceleration
ALTER SESSION SET USE_CACHED_RESULT = TRUE;

-- ============================================================================
-- CORE TABLES: ICEBERG with clustering
-- ============================================================================

-- USERS: Driver profiles with VARIANT for flexible attributes
CREATE OR REPLACE TABLE USERS (
    user_id VARCHAR PRIMARY KEY,
    name VARCHAR NOT NULL,
    profile VARCHAR NOT NULL,  -- json string, parse when needed
    created_at TIMESTAMP_NTZ DEFAULT CURRENT_TIMESTAMP(),
    updated_at TIMESTAMP_NTZ DEFAULT CURRENT_TIMESTAMP()
)
CLUSTER BY (user_id)
DATA_RETENTION_TIME_IN_DAYS = 30;

-- TELEMETRY: Time-series data with clustering on user_id + timestamp
CREATE OR REPLACE TABLE TELEMETRY (
    telemetry_id STRING DEFAULT UUID_STRING(),
    user_id STRING NOT NULL,
    trip_id STRING NOT NULL,
    timestamp_ms BIGINT NOT NULL,
    speed_mph FLOAT,
    heading_degrees FLOAT,
    latitude DOUBLE,
    longitude DOUBLE,
    location GEOGRAPHY,  -- for ST_DISTANCE queries
    vocal_energy FLOAT,  -- 0.0-1.0: baseline-normalized
    response_latency_ms FLOAT,  -- response time in milliseconds
    vad_score FLOAT,  -- voice activity detection confidence
    risk_state VARCHAR,  -- STABLE, EMERGING, WINDOW, CRITICAL
    interaction_level INT,  -- 0-4
    recording_uri VARCHAR,  -- path to audio file on storage
    created_at TIMESTAMP_NTZ DEFAULT CURRENT_TIMESTAMP(),
    PRIMARY KEY (telemetry_id)
)
CLUSTER BY (user_id, DATE(TO_TIMESTAMP_NTZ(timestamp_ms / 1000)))
;

-- TRIPS: Journey metadata with geospatial analytics
CREATE OR REPLACE TABLE TRIPS (
    trip_id STRING PRIMARY KEY DEFAULT UUID_STRING(),
    user_id STRING NOT NULL,
    start_time TIMESTAMP_NTZ NOT NULL,
    end_time TIMESTAMP_NTZ,
    start_location GEOGRAPHY,
    end_location GEOGRAPHY,
    distance_km FLOAT,
    duration_minutes INT,
    context VARCHAR,  -- URBAN_HIGH, MIXED, HIGHWAY
    peak_risk_state VARCHAR,
    max_vocal_energy FLOAT,
    avg_vocal_energy FLOAT,
    response_latencies ARRAY,  -- for statistical analysis
    interaction_level_transitions ARRAY,  -- track escalations/de-escalations
    breaks_taken INT DEFAULT 0,
    break_minutes INT DEFAULT 0,
    driver_feedback VARCHAR,  -- post-trip debrief notes
    created_at TIMESTAMP_NTZ DEFAULT CURRENT_TIMESTAMP(),
    FOREIGN KEY (user_id) REFERENCES USERS(user_id)
);

-- INTERACTIONS: Logged conversation exchanges
CREATE OR REPLACE TABLE INTERACTIONS (
    interaction_id STRING PRIMARY KEY DEFAULT UUID_STRING(),
    trip_id STRING NOT NULL,
    user_id STRING NOT NULL,
    interaction_level INT NOT NULL,
    copilot_message VARCHAR,
    driver_response VARCHAR,
    response_latency_ms FLOAT,
    detected_tone VARCHAR,  -- ENGAGED, ANNOYED, HESITANT, COMPLIANT, NONE
    response_type VARCHAR,  -- POSITIVE, NEGATIVE, NEUTRAL, NO_RESPONSE
    effective_lever_used VARCHAR,  -- which psychological argument worked
    timestamp TIMESTAMP_NTZ DEFAULT CURRENT_TIMESTAMP(),
    FOREIGN KEY (trip_id) REFERENCES TRIPS(trip_id),
    FOREIGN KEY (user_id) REFERENCES USERS(user_id)
);

-- DRIVER_MEMORY: Long-form profile observations (for LLM context)
CREATE OR REPLACE TABLE DRIVER_MEMORY (
    memory_id STRING PRIMARY KEY DEFAULT UUID_STRING(),
    user_id STRING NOT NULL,
    observation_type VARCHAR,  -- TRIGGER_DISCOVERED, EFFECTIVE_ARGUMENT, PATTERN, BOUNDARY
    observation_text VARCHAR NOT NULL,  -- free-form note
    confidence FLOAT,  -- 0.0-1.0: how sure we are
    last_observed_date DATE,
    created_at TIMESTAMP_NTZ DEFAULT CURRENT_TIMESTAMP(),
    updated_at TIMESTAMP_NTZ DEFAULT CURRENT_TIMESTAMP(),
    FOREIGN KEY (user_id) REFERENCES USERS(user_id)
);

-- ============================================================================
-- MATERIALIZED VIEWS: Pre-aggregated analytics for app queries
-- ============================================================================

-- Driver risk profile (updated on-demand)
CREATE OR REPLACE VIEW DRIVER_RISK_SUMMARY AS
SELECT
    user_id,
    COUNT(DISTINCT trip_id) as total_trips,
    COUNT(CASE WHEN peak_risk_state IN ('WINDOW', 'CRITICAL') THEN trip_id END) as high_risk_trips,
    AVG(max_vocal_energy) as avg_peak_vocal_energy,
    AVG(duration_minutes) as avg_trip_length_minutes,
    MIN(start_time) as first_trip_date,
    MAX(start_time) as last_trip_date,
    CURRENT_TIMESTAMP() as last_updated
FROM TRIPS
GROUP BY user_id;

-- Real-time trip telemetry summary (for current session)
CREATE OR REPLACE VIEW CURRENT_TRIP_METRICS AS
SELECT
    user_id,
    trip_id,
    COUNT(*) as telemetry_points,
    MIN(timestamp_ms) as trip_start_ms,
    MAX(timestamp_ms) as latest_event_ms,
    MIN(vocal_energy) as min_vocal_energy,
    MAX(vocal_energy) as max_vocal_energy,
    AVG(vocal_energy) as avg_vocal_energy,
    MAX(response_latency_ms) as max_latency_ms,
    AVG(response_latency_ms) as avg_latency_ms,
    MAX(risk_state) as current_risk_state  -- crude but serviceable
FROM TELEMETRY
WHERE TO_TIMESTAMP_NTZ(timestamp_ms / 1000) > DATEADD('hour', -1, CURRENT_TIMESTAMP())
GROUP BY user_id, trip_id
;

-- ============================================================================
-- Tagging and governance
CREATE TAG IF NOT EXISTS PII;
CREATE TAG IF NOT EXISTS DOMAIN;
CREATE TAG IF NOT EXISTS SENSITIVE;

-- Tag tables for data lineage
ALTER TABLE USERS SET TAG PII = 'HIGH', DOMAIN = 'DRIVER_PROFILE';
ALTER TABLE TELEMETRY SET TAG PII = 'MEDIUM', DOMAIN = 'TELEMETRY', SENSITIVE = 'TRUE';
ALTER TABLE INTERACTIONS SET TAG PII = 'LOW', DOMAIN = 'CONVERSATION';

"""

# ============================================================================
# SAMPLE DATA: 3 PERSONAS
# ============================================================================

def get_driver_profiles() -> List[Dict]:
    """Generate 3 detailed driver personas with realistic history."""
    
    now = datetime.now()
    
    profiles = [
        {
            "user_id": "aldan_creo",
            "name": "Aldan",
            "risk_level": "HIGH",
            "profile": {
                "sleep_pattern": "Erratic. Sleeps 4-6 hours on weekdays, often late night on transit exploration trips",
                "wake_times": ["06:00", "07:30", "23:00"],  # very variable
                "risk_triggers": ["Highway afternoons after 2pm", "after red-eye flights", "driving alone at night"],
                "effective_levers": ["technology_data", "public_transport_connections", "ai_research_angle"],
                "rejection_pattern": "Wants objective data, loves being proven wrong with stats, engages with contrarian angles",
                "intrinsic_interests": ["public_transport_systems", "AI", "data_infrastructure", "routing_algorithms"],
                "creative_prompts": [
                    "If this transit network were an AI model, what would its loss function be?",
                    "What's the most inefficient part of your commute stack?",
                    "Could autonomous routing predict fatigue before you feel it?",
                ],
                "baseline_vocal_energy": 0.72,
                "boundary": "Don't call him drowsy, show him the metrics instead"
            },
            "trip_count": 24,
            "high_risk_trip_count": 6
        },
        {
            "user_id": "ana_campillo",
            "name": "Ana",
            "risk_level": "MODERATE",
            "profile": {
                "sleep_pattern": "Regular sleeper, 7-8 hours typically, but disrupted by thesis work and mentorship commitments",
                "wake_times": ["06:30", "07:00"],
                "risk_triggers": ["After intensive writing sessions (2+ hours)", "When discussing difficult philosophical questions", "late afternoon after teaching"],
                "effective_levers": ["life_lessons", "creative_metaphor", "teaching_analogy", "personal_growth"],
                "rejection_pattern": "Thoughtful, wants to ponder the decision, responds best to open questions rather than directives",
                "intrinsic_interests": ["creative_writing", "education", "philosophy", "literature", "human_development"],
                "creative_prompts": [
                    "What story does your drowsiness want to tell right now?",
                    "If fatigue is a character flaw in your driving narrative, what's the redemption arc?",
                    "Your students trust you to show up well. What's the lesson you're teaching yourself right now?",
                    "Is this exhaustion a boundary you haven't set yet?"
                ],
                "baseline_vocal_energy": 0.68,
                "boundary": "She'll accept breaks if framed as self-care and modeling good habits for students"
            },
            "trip_count": 18,
            "high_risk_trip_count": 2
        },
        {
            "user_id": "marta_sanchez",
            "name": "Marta",
            "risk_level": "LOW",
            "profile": {
                "sleep_pattern": "Excellent. 7.5-8.5 hours nightly, consistent wake time 05:30 for morning runs and farm chores",
                "wake_times": ["05:30"],
                "risk_triggers": ["Very rare. Tends to be driving home from lab around 18:00 on heavy experimental days"],
                "effective_levers": ["discipline_angle", "fitness_analogy", "animal_welfare", "study_continuity"],
                "rejection_pattern": "Almost never resists. Will accept guidance immediately, slightly surprised when system alerts",
                "intrinsic_interests": ["biology", "animals", "farm_life", "tennis", "early_mornings", "athletic_training"],
                "creative_prompts": [
                    "Your focus right now is like a dog off a leash - bring it back.",
                    "Animals depend on consistent routines. Your focus depends on yours. Where's the breakdown?",
                    "You don't skip warm-up before tennis. Why skip recovery before a long drive?",
                    "Your research animals are stressed by changes in rhythm. So is your nervous system."
                ],
                "baseline_vocal_energy": 0.81,
                "boundary": "Almost never needs breaks. Responds to brief, direct language. Honors discipline-based arguments."
            },
            "trip_count": 42,
            "high_risk_trip_count": 0
        }
    ]
    
    return profiles


def generate_realistic_trips(user_profile: Dict, base_date: datetime) -> List[Dict]:
    """Generate realistic trip history for a driver."""
    
    trips = []
    user_id = user_profile["user_id"]
    trip_count = user_profile["trip_count"]
    high_risk_count = user_profile["high_risk_trip_count"]
    
    contexts = ["URBAN_HIGH", "MIXED", "HIGHWAY"]
    
    for i in range(trip_count):
        # Distribute high-risk trips realistically
        is_high_risk = i < high_risk_count
        
        start_time = base_date - timedelta(days=trip_count - i)
        duration = 45 + (i % 120) if is_high_risk else 30 + (i % 90)
        end_time = start_time + timedelta(minutes=duration)
        
        context = "HIGHWAY" if is_high_risk else contexts[i % len(contexts)]
        peak_risk = "WINDOW" if is_high_risk and i < 3 else "CRITICAL" if is_high_risk else "STABLE"
        
        trips.append({
            "trip_id": f"{user_id}_trip_{i:03d}",
            "user_id": user_id,
            "start_time": start_time.isoformat(),
            "end_time": end_time.isoformat(),
            "distance_km": 15.0 + (i * 0.5) % 150,
            "duration_minutes": duration,
            "context": context,
            "peak_risk_state": peak_risk,
            "max_vocal_energy": 0.75 if is_high_risk else 0.82,
            "avg_vocal_energy": 0.65 if is_high_risk else 0.78,
            "breaks_taken": 1 if is_high_risk else 0,
            "break_minutes": 10 if is_high_risk else 0,
            "driver_feedback": "Felt the wall around 70 minutes" if is_high_risk else "Good state throughout"
        })
    
    return trips


def generate_telemetry(trip: Dict) -> List[Dict]:
    """Generate granular telemetry data points for a trip."""
    
    telemetry = []
    start_ms = int(datetime.fromisoformat(trip["start_time"]).timestamp() * 1000)
    duration_ms = trip["duration_minutes"] * 60 * 1000
    
    # Generate 1 data point per minute
    for minute in range(0, trip["duration_minutes"], 1):
        elapsed_ms = minute * 60 * 1000
        progress = elapsed_ms / duration_ms
        
        # Simulate fatigue: vocal energy drops towards end
        base_vocal = trip["avg_vocal_energy"]
        vocal_energy = base_vocal - (progress * 0.15) + (minute % 5) * 0.02
        vocal_energy = max(0.3, min(1.0, vocal_energy))
        
        # Response latency increases with fatigue
        response_latency = 0.5 + (0.002 * minute) + (progress * 1.5)
        
        telemetry.append({
            "user_id": trip["user_id"],
            "trip_id": trip["trip_id"],
            "timestamp_ms": start_ms + elapsed_ms,
            "speed_mph": 45.0 + (minute % 15) * 2,
            "heading_degrees": (minute * 2) % 360,
            "latitude": 47.66 + (minute * 0.0001),
            "longitude": -117.43 + (minute * 0.0001),
            "vocal_energy": round(vocal_energy, 3),
            "response_latency_ms": round(response_latency * 1000, 1),
            "vad_score": 0.7 + (progress * -0.2),
            "risk_state": trip["peak_risk_state"]
        })
    
    return telemetry


def generate_interactions(trip: Dict) -> List[Dict]:
    """Generate realistic conversation interactions for a trip."""
    
    interactions = []
    
    if trip["peak_risk_state"] == "STABLE":
        return interactions  # No interactions needed
    
    base_time = datetime.fromisoformat(trip["start_time"])
    
    # Level 1: Binary check-in at ~45 min if emerging risk
    if trip["peak_risk_state"] in ["WINDOW", "CRITICAL"]:
        interactions.append({
            "trip_id": trip["trip_id"],
            "user_id": trip["user_id"],
            "interaction_level": 1,
            "copilot_message": f"Hey {trip['user_id']}. How are you holding up?",
            "driver_response": "I'm fine",
            "response_latency_ms": 1247,
            "detected_tone": "HESITANT",
            "response_type": "NEUTRAL",
            "timestamp": (base_time + timedelta(minutes=45)).isoformat()
        })
    
    # Level 2: Micro-activation at ~65 min
    if trip["peak_risk_state"] == "CRITICAL":
        interactions.append({
            "trip_id": trip["trip_id"],
            "user_id": trip["user_id"],
            "interaction_level": 2,
            "copilot_message": "Say the color of the car ahead of you.",
            "driver_response": "White",
            "response_latency_ms": 987,
            "detected_tone": "ENGAGED",
            "response_type": "POSITIVE",
            "timestamp": (base_time + timedelta(minutes=65)).isoformat()
        })
        
        # Level 3: Direct activation with argument at ~75 min
        interactions.append({
            "trip_id": trip["trip_id"],
            "user_id": trip["user_id"],
            "interaction_level": 3,
            "copilot_message": f"You've been at this 75 min. The vocal energy has dropped 20%. You've got 40 min left. Pull over for 12 and you get there better.",
            "driver_response": "Yeah, ok. There's a rest area ahead.",
            "response_latency_ms": 3421,
            "detected_tone": "COMPLIANT",
            "response_type": "POSITIVE",
            "effective_lever_used": "objective_data",
            "timestamp": (base_time + timedelta(minutes=75)).isoformat()
        })
    
    return interactions


def generate_driver_memories(user_profile: Dict) -> List[Dict]:
    """Generate long-form driver memory observations."""
    
    user_id = user_profile["user_id"]
    memories = []
    
    if user_id == "aldan_creo":
        memories = [
            {
                "observation_type": "TRIGGER_DISCOVERED",
                "observation_text": "Afternoon highway drives (14:00-17:00) after 5-hour sleep consistently trigger WINDOW risk within 60-75 minutes.",
                "confidence": 0.89
            },
            {
                "observation_type": "EFFECTIVE_ARGUMENT",
                "observation_text": "Responds immediately to objective metric comparisons: 'Your latency doubled from baseline' gets compliance when direct requests don't.",
                "confidence": 0.92
            },
            {
                "observation_type": "PATTERN",
                "observation_text": "Engages most when arguments connect to tech/data infrastructure. Metaphors about routing algorithms and network efficiency land.",
                "confidence": 0.78
            },
            {
                "observation_type": "BOUNDARY",
                "observation_text": "Prefers being shown data than being told he's drowsy. Becomes defensive if phrasing suggests he's impaired.",
                "confidence": 0.85
            }
        ]
    elif user_id == "ana_campillo":
        memories = [
            {
                "observation_type": "TRIGGER_DISCOVERED",
                "observation_text": "Post-writing fatigue is real: 2+ hour creative sessions before driving correlate with vocal energy drops of 0.15-0.22.",
                "confidence": 0.82
            },
            {
                "observation_type": "EFFECTIVE_ARGUMENT",
                "observation_text": "Responds to frame of modeling good boundaries for her students. 'Your students need you present, not just there' gets reflection and acceptance.",
                "confidence": 0.88
            },
            {
                "observation_type": "PATTERN",
                "observation_text": "Thoughtful decision-maker. Needs 10-15 seconds to consider recommendations, but follows through once convinced.",
                "confidence": 0.79
            },
            {
                "observation_type": "BOUNDARY",
                "observation_text": "Dislikes feeling rushed or directives phrased as medical/safety orders. Prefers collaborative 'what if' framing.",
                "confidence": 0.84
            }
        ]
    elif user_id == "marta_sanchez":
        memories = [
            {
                "observation_type": "TRIGGER_DISCOVERED",
                "observation_text": "Rarely fatigued. When it does happen: 3 consecutive 18+ hour biology lab days. Happens ~1x per 40 trips.",
                "confidence": 0.95
            },
            {
                "observation_type": "EFFECTIVE_ARGUMENT",
                "observation_text": "Discipline-and-routine language: 'Animals depend on consistent rhythm so your nervous system does too' lands immediately.",
                "confidence": 0.91
            },
            {
                "observation_type": "PATTERN",
                "observation_text": "Responds best to brief, factual language. Open to recommendations, doesn't need convincing. Early morning baseline is consistently high (0.89+).",
                "confidence": 0.90
            },
            {
                "observation_type": "BOUNDARY",
                "observation_text": "Almost never pushes back. When fatigue does appear, she's pre-aware. Quick to acknowledge and accept breaks.",
                "confidence": 0.93
            }
        ]
    
    for memo in memories:
        memo["user_id"] = user_id
        memo["last_observed_date"] = (datetime.now() - timedelta(days=memo.get("days_ago", 7))).date().isoformat()
    
    return memories


# ============================================================================
# MAIN EXECUTION
# ============================================================================

def connect_snowflake(user: str, password: str, account: str, warehouse: str, database: str = "COGPILOT", schema: str = "PUBLIC") -> snowflake.connector.SnowflakeConnection:
    """Establish Snowflake connection."""
    try:
        conn = snowflake.connector.connect(
            user=user,
            password=password,
            account=account,
            warehouse=warehouse,
            database=database,
            schema=schema
        )
        logger.info(f"✓ Connected to Snowflake: {account}/{database}")
        return conn
    except Exception as e:
        logger.error(f"✗ Snowflake connection failed: {e}")
        raise


def clear_data(cursor: DictCursor):
    """Drop all tables and views."""
    logger.info("Clearing existing data...")
    
    tables = ["INTERACTIONS", "TRIPS", "TELEMETRY", "DRIVER_MEMORY", "USERS"]
    views = ["CURRENT_TRIP_METRICS", "DRIVER_RISK_SUMMARY"]
    
    for view in views:
        try:
            cursor.execute(f"DROP MATERIALIZED VIEW IF EXISTS {view}")
            logger.info(f"  ✓ Dropped view: {view}")
        except:
            pass
    
    for table in tables:
        try:
            cursor.execute(f"DROP TABLE IF EXISTS {table}")
            logger.info(f"  ✓ Dropped table: {table}")
        except:
            pass


def setup_schema(cursor: DictCursor):
    """Execute schema creation DDL."""
    logger.info("Creating advanced Snowflake schema...")
    
    # Split by semicolon and execute each statement
    statements = SCHEMA_SETUP.split(';')
    for stmt in statements:
        # strip comment-only lines + inline comments; lazy parser
        cleaned_lines = []
        for line in stmt.splitlines():
            line = line.strip()
            if not line or line.startswith('--'):
                continue
            if '--' in line:
                line = line.split('--', 1)[0].strip()
            if line:
                cleaned_lines.append(line)
        stmt = " ".join(cleaned_lines).strip()
        if stmt:
            try:
                cursor.execute(stmt)
                logger.info(f"  ✓ {stmt[:60]}...")
            except Exception as e:
                logger.warning(f"  ⚠ Statement skipped: {e}")


def populate_users(cursor: DictCursor, profiles: List[Dict]):
    """Insert driver profiles with VARIANT data."""
    logger.info("Populating USERS table...")
    
    for profile in profiles:
        profile_json = json.dumps(profile["profile"])
        
        sql = """
            INSERT INTO USERS (user_id, name, profile)
            VALUES (%s, %s, %s)
        """
        cursor.execute(sql, (profile["user_id"], profile["name"], profile_json))
        
        logger.info(f"  ✓ {profile['name']} ({profile['user_id']}) - Risk: {profile['risk_level']}")


def populate_trips(cursor: DictCursor, profiles: List[Dict], base_date: datetime):
    """Insert trip history."""
    logger.info("Populating TRIPS table...")
    
    total_trips = 0
    for profile in profiles:
        trips = generate_realistic_trips(profile, base_date)
        
        for trip in trips:
            cursor.execute(f"""
                INSERT INTO TRIPS (
                    trip_id, user_id, start_time, end_time, distance_km, duration_minutes,
                    context, peak_risk_state, max_vocal_energy, avg_vocal_energy,
                    breaks_taken, break_minutes, driver_feedback
                )
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
            """, (
                trip["trip_id"], trip["user_id"], trip["start_time"], trip["end_time"],
                trip["distance_km"], trip["duration_minutes"], trip["context"],
                trip["peak_risk_state"], trip["max_vocal_energy"], trip["avg_vocal_energy"],
                trip["breaks_taken"], trip["break_minutes"], trip["driver_feedback"]
            ))
            total_trips += 1
        
        logger.info(f"  ✓ {profile['name']}: {len(trips)} trips")
    
    logger.info(f"  ✓ Total trips inserted: {total_trips}")


def populate_telemetry(cursor: DictCursor, profiles: List[Dict], base_date: datetime, limit_per_trip: int = 0):
    """Insert granular telemetry with batching.
    limit_per_trip=0 disables cap.
    """
    logger.info("Populating TELEMETRY table (batched)...")
    
    total_points = 0
    for profile in profiles:
        trips = generate_realistic_trips(profile, base_date)
        
        for trip in trips:
            telemetry_points = generate_telemetry(trip)
            if limit_per_trip and len(telemetry_points) > limit_per_trip:
                telemetry_points = telemetry_points[:limit_per_trip]
            
            rows = []
            for point in telemetry_points:
                rows.append((
                    point["user_id"], point["trip_id"], point["timestamp_ms"],
                    point["speed_mph"], point["heading_degrees"],
                    point["latitude"], point["longitude"], point["vocal_energy"],
                    point["response_latency_ms"], point["vad_score"], point["risk_state"]
                ))
            if rows:
                cursor.executemany("""
                    INSERT INTO TELEMETRY (
                        user_id, trip_id, timestamp_ms, speed_mph, heading_degrees,
                        latitude, longitude, vocal_energy, response_latency_ms,
                        vad_score, risk_state
                    )
                    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                """, rows)
                total_points += len(rows)
        
        logger.info(f"  ✓ {profile['name']}: {len(trips) * 60} telemetry points (capped {limit_per_trip})")
    
    logger.info(f"  ✓ Total telemetry points: {total_points}")


def populate_interactions(cursor: DictCursor, profiles: List[Dict], base_date: datetime):
    """Insert conversation interactions."""
    logger.info("Populating INTERACTIONS table...")
    
    total_interactions = 0
    for profile in profiles:
        trips = generate_realistic_trips(profile, base_date)
        
        for trip in trips:
            interactions = generate_interactions(trip)
            
            for interaction in interactions:
                cursor.execute(f"""
                    INSERT INTO INTERACTIONS (
                        trip_id, user_id, interaction_level, copilot_message,
                        driver_response, response_latency_ms, detected_tone,
                        response_type, effective_lever_used, timestamp
                    )
                    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                """, (
                    interaction["trip_id"], interaction["user_id"],
                    interaction["interaction_level"], interaction["copilot_message"],
                    interaction["driver_response"], interaction["response_latency_ms"],
                    interaction["detected_tone"], interaction["response_type"],
                    interaction.get("effective_lever_used"), interaction["timestamp"]
                ))
                total_interactions += 1
        
        logger.info(f"  ✓ {profile['name']}: {len([i for t in trips for i in generate_interactions(t)])} interactions")
    
    logger.info(f"  ✓ Total interactions: {total_interactions}")


def populate_driver_memory(cursor: DictCursor, profiles: List[Dict]):
    """Insert driver memory observations."""
    logger.info("Populating DRIVER_MEMORY table...")
    
    total_memories = 0
    for profile in profiles:
        memories = generate_driver_memories(profile)
        
        for memory in memories:
            cursor.execute(f"""
                INSERT INTO DRIVER_MEMORY (
                    user_id, observation_type, observation_text, confidence, last_observed_date
                )
                VALUES (%s, %s, %s, %s, %s)
            """, (
                profile["user_id"], memory["observation_type"],
                memory["observation_text"], memory["confidence"],
                memory["last_observed_date"]
            ))
            total_memories += 1
        
        logger.info(f"  ✓ {profile['name']}: {len(memories)} memories")
    
    logger.info(f"  ✓ Total memories: {total_memories}")


def main():
    parser = argparse.ArgumentParser(description="CogPilot Snowflake Population Script")
    parser.add_argument("--user", required=False, help="Snowflake username (env: SF_USER)")
    parser.add_argument("--password", required=False, help="Snowflake password (env: SF_PASSWORD)")
    parser.add_argument("--account", required=False, help="Snowflake account (env: SF_ACCOUNT)")
    parser.add_argument("--warehouse", default="COMPUTE_WH", help="Snowflake warehouse (env: SF_WAREHOUSE)")
    parser.add_argument("--database", default="COGPILOT", help="Snowflake database")
    parser.add_argument("--clear", action="store_true", help="Clear existing data before populating")
    parser.add_argument("--skip-telemetry", action="store_true", help="Do not insert telemetry data")
    parser.add_argument("--telemetry-limit", type=int, default=0, help="Max telemetry rows per trip (0=unlimited)")
    
    args = parser.parse_args()
    
    # Load from environment if not provided
    user = args.user or os.getenv("SF_USER")
    password = args.password or os.getenv("SF_PASSWORD")
    account = args.account or os.getenv("SF_ACCOUNT")
    warehouse = args.warehouse or os.getenv("SF_WAREHOUSE", "COMPUTE_WH")
    
    if not all([user, password, account]):
        logger.error("Missing Snowflake credentials. Provide via --user, --password, --account or set SF_USER, SF_PASSWORD, SF_ACCOUNT env vars")
        exit(1)
    
    try:
        conn = connect_snowflake(user, password, account, warehouse, args.database)
        cursor = conn.cursor(DictCursor)

        # ensure database/schema in session
        try:
            cursor.execute(f"USE DATABASE {args.database}")
        except Exception:
            cursor.execute(f"CREATE DATABASE IF NOT EXISTS {args.database}")
            cursor.execute(f"USE DATABASE {args.database}")

        try:
            cursor.execute("USE SCHEMA PUBLIC")
        except Exception:
            cursor.execute("CREATE SCHEMA IF NOT EXISTS PUBLIC")
            cursor.execute("USE SCHEMA PUBLIC")
        
        if args.clear:
            clear_data(cursor)
        
        setup_schema(cursor)
        
        profiles = get_driver_profiles()
        base_date = datetime.now()
        
        populate_users(cursor, profiles)
        populate_trips(cursor, profiles, base_date)
        if not args.skip_telemetry:
            populate_telemetry(cursor, profiles, base_date, limit_per_trip=args.telemetry_limit)
        else:
            logger.info("Skipping telemetry population (--skip-telemetry)")
        populate_interactions(cursor, profiles, base_date)
        populate_driver_memory(cursor, profiles)
        
        cursor.execute("SELECT count(*) as cnt FROM USERS")
        user_count = cursor.fetchone()["CNT"]
        
        cursor.execute("SELECT count(*) as cnt FROM TRIPS")
        trip_count = cursor.fetchone()["CNT"]
        
        logger.info("=" * 70)
        logger.info(f"✓ POPULATION COMPLETE")
        logger.info(f"  Users: {user_count}")
        logger.info(f"  Trips: {trip_count}")
        logger.info(f"  Profiles: Aldan (HIGH risk), Ana (MODERATE), Marta (LOW)")
        logger.info("=" * 70)
        
        conn.commit()
        conn.close()
        
    except Exception as e:
        logger.error(f"Fatal error: {e}", exc_info=True)
        exit(1)


if __name__ == "__main__":
    main()
