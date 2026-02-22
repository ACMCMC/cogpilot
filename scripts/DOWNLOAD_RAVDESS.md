# How to Get RAVDESS Dataset

## Quick Download Guide

### Step 1: Go to Kaggle
Link: https://www.kaggle.com/datasets/uwrfkaggle/ravdess-emotional-speech-audio

### Step 2: Download the Dataset
- Look for **"Audio_Speech_Actors_01-24.zip"**
- Size: ~200MB
- Contains: 24 actors, 7 emotions, real human speech

### Step 3: Unzip to Correct Location
```bash
cd /Users/acmc/cogpilot/scripts
unzip ~/Downloads/Audio_Speech_Actors_01-24.zip -d ravdess_data/
```

### Step 4: Verify Structure
```bash
ls ravdess_data/
# Should show: Actor_01/ Actor_02/ ... Actor_24/

ls ravdess_data/Actor_01/ | head -5
# Should show .wav files like: 03-01-01-01-01-01-01.wav
```

### Step 5: Train Model
```bash
python train_drowsiness_model.py
```

## Why RAVDESS?

- **Real human speech**: Not synthetic/generated
- **Multiple emotions**: Calm, neutral, sad, happy, angry, fearful, surprised
- **Clean recordings**: Professional quality
- **Well-documented**: Standard naming convention
- **Publicly available**: Free for research

## What You Get

- 1440 audio files (24 actors × 60 recordings each)
- Professional voice actors
- Multiple takes per emotion
- North American accents
- Balanced male/female speakers

## Alternative: TESS Dataset

If RAVDESS doesn't work, try TESS (Toronto Emotional Speech Set):
https://www.kaggle.com/datasets/ejlok1/toronto-emotional-speech-set-tess

Similar structure, also works with our training script.
