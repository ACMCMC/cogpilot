#!/bin/bash
# Quick setup script for drowsiness model training

echo "=== CogPilot Drowsiness Model Setup ==="
echo ""

# check if in scripts dir
if [ ! -f "train_drowsiness_model.py" ]; then
    echo "ERROR: Run this from the scripts/ directory"
    exit 1
fi

# check python
if ! command -v python3 &> /dev/null; then
    echo "ERROR: python3 not found. Install Python 3.8+"
    exit 1
fi

echo "Step 1: Installing dependencies..."
pip install -r requirements_ml.txt

echo ""
echo "Step 2: Checking for RAVDESS dataset..."
if [ ! -d "ravdess_data" ]; then
    echo "WARNING: ravdess_data/ not found"
    echo ""
    echo "==================================================================="
    echo "YOU NEED TO DOWNLOAD THE REAL RAVDESS DATASET"
    echo "==================================================================="
    echo ""
    echo "1. Go to: https://www.kaggle.com/datasets/uwrfkaggle/ravdess-emotional-speech-audio"
    echo "2. Download Audio_Speech_Actors_01-24.zip (~200MB)"
    echo "3. Run: unzip Audio_Speech_Actors_01-24.zip -d ravdess_data/"
    echo ""
    echo "This is REAL human speech data, NOT synthetic!"
    echo "==================================================================="
    exit 1
fi

echo "Found ravdess_data/"
echo "Verifying structure..."
actor_count=$(ls -d ravdess_data/Actor_* 2>/dev/null | wc -l)
if [ "$actor_count" -lt 24 ]; then
    echo "ERROR: Expected 24 Actor folders, found $actor_count"
    echo "Please download and unzip complete RAVDESS dataset"
    exit 1
fi
echo "✓ Found $actor_count actor folders"

echo ""
echo "Step 3: Training model..."
python3 train_drowsiness_model.py

if [ $? -eq 0 ]; then
    echo ""
    echo "=== SUCCESS ==="
    echo "Model saved to sleepiness_detector.pkl"
    echo ""
    echo "Test it with:"
    echo "  python3 test_drowsiness_model.py ravdess_data/Actor_01/03-01-02-01-01-01-01.wav"
else
    echo "Training failed. Check errors above."
    exit 1
fi
