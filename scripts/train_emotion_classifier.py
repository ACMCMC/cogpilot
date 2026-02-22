#!/usr/bin/env python3
"""
Multi-class emotion classifier with weighted sleepiness scoring
Instead of binary SLEEPY/ALERT, classifies actual emotions and computes sleepiness score
"""

import os
import glob
import numpy as np
from scipy.io import wavfile
from sklearn.model_selection import train_test_split
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import accuracy_score, classification_report, confusion_matrix
import joblib
import random

random.seed(2262)
np.random.seed(2262)

DATA_PATH = "ravdess_data/"
MODEL_FILE = "emotion_classifier.pkl"
SCALER_FILE = "emotion_feature_scaler.pkl"

# sleepiness weights for each emotion (0=fully alert, 1=very sleepy)
EMOTION_SLEEPINESS_WEIGHTS = {
    'neutral': 0.75,    # monotone, low energy
    'calm': 0.85,       # very low arousal, relaxed
    'sad': 0.70,        # low energy but some variation
    'happy': 0.15,      # high energy, engaged
    'angry': 0.10,      # very high arousal, alert
    'fearful': 0.25,    # high arousal, adrenaline
    'surprised': 0.20   # sudden arousal, alert
}

EMOTION_LABELS = {
    1: 'neutral',
    2: 'calm',
    3: 'happy',
    4: 'sad',
    5: 'angry',
    6: 'fearful',
    8: 'surprised'
}

def extract_features(file_name):
    """Extract acoustic features from audio"""
    try:
        sample_rate, audio = wavfile.read(file_name)
        
        if audio.dtype == np.int16:
            audio = audio.astype(np.float32) / 32768.0
        elif audio.dtype == np.int32:
            audio = audio.astype(np.float32) / 2147483648.0
        
        if len(audio.shape) > 1:
            audio = audio[:, 0]
        
        features = []
        
        # time domain
        features.append(np.mean(audio))
        features.append(np.std(audio))
        features.append(np.mean(np.abs(audio)))
        features.append(np.max(np.abs(audio)))
        
        zero_crossings = np.where(np.diff(np.sign(audio)))[0]
        zcr = len(zero_crossings) / len(audio)
        features.append(zcr)
        
        energy = np.sum(audio ** 2) / len(audio)
        features.append(energy)
        
        # spectral
        fft = np.fft.fft(audio)
        magnitude = np.abs(fft[:len(fft)//2])
        freqs = np.fft.fftfreq(len(audio), 1/sample_rate)[:len(fft)//2]
        
        spectral_centroid = np.sum(freqs * magnitude) / (np.sum(magnitude) + 1e-10)
        features.append(spectral_centroid)
        
        cumsum = np.cumsum(magnitude)
        rolloff_idx = np.where(cumsum >= 0.85 * cumsum[-1])[0]
        spectral_rolloff = freqs[rolloff_idx[0]] if len(rolloff_idx) > 0 else 0
        features.append(spectral_rolloff)
        
        spectral_bandwidth = np.sqrt(np.sum(((freqs - spectral_centroid) ** 2) * magnitude) / (np.sum(magnitude) + 1e-10))
        features.append(spectral_bandwidth)
        
        # mel bins
        mel_bins = 20
        bin_size = len(magnitude) // mel_bins
        for i in range(mel_bins):
            start = i * bin_size
            end = start + bin_size if i < mel_bins - 1 else len(magnitude)
            bin_energy = np.mean(magnitude[start:end])
            features.append(bin_energy)
        
        return np.array(features)
        
    except Exception as e:
        return None

def load_data():
    """Load data with emotion labels (not binary)"""
    x, y = [], []
    
    print("Loading RAVDESS with emotion labels...")
    
    for file in glob.glob(DATA_PATH + "/**/*.wav", recursive=True):
        file_name = os.path.basename(file)
        parts = file_name.split("-")
        
        if len(parts) < 3:
            continue
            
        emotion = int(parts[2])
        
        # skip disgust (emotion 7) - not in our model
        if emotion == 7:
            continue
        
        feature = extract_features(file)
        if feature is not None:
            x.append(feature)
            y.append(emotion)  # keep original emotion label
    
    return np.array(x), np.array(y)

def train_emotion_classifier():
    """Train multi-class emotion classifier"""
    
    print("\n=== EMOTION CLASSIFIER TRAINING ===\n")
    
    print("1. Loading data...")
    X, y = load_data()
    
    if len(X) == 0:
        print("ERROR: No data found!")
        return
    
    # emotion distribution
    unique, counts = np.unique(y, return_counts=True)
    print(f"   Loaded {len(X)} samples")
    print("\n   Emotion distribution:")
    for emotion_code, count in zip(unique, counts):
        emotion_name = EMOTION_LABELS.get(emotion_code, 'unknown')
        sleepiness = EMOTION_SLEEPINESS_WEIGHTS.get(emotion_name, 0.5)
        print(f"      {emotion_name:10} ({emotion_code}): {count:4} samples (sleepiness weight: {sleepiness:.2f})")
    
    print("\n2. Splitting data (90/10)...")
    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.1, random_state=2262, stratify=y
    )
    print(f"   Train: {len(X_train)}, Test: {len(X_test)}")
    
    print("\n3. Training Random Forest...")
    model = RandomForestClassifier(
        n_estimators=200,  # more trees for multi-class
        max_depth=25,
        random_state=2262,
        n_jobs=-1
    )
    model.fit(X_train, y_train)
    print("   Training complete")
    
    print("\n4. Evaluating...")
    y_pred = model.predict(X_test)
    acc = accuracy_score(y_test, y_pred)
    print(f"   Emotion Classification Accuracy: {acc*100:.2f}%")
    
    # detailed report
    print("\n   Classification Report:")
    target_names = [EMOTION_LABELS[code] for code in sorted(EMOTION_LABELS.keys())]
    print(classification_report(y_test, y_pred, target_names=target_names, zero_division=0))
    
    # confusion matrix
    print("\n   Confusion Matrix:")
    cm = confusion_matrix(y_test, y_pred, labels=sorted(EMOTION_LABELS.keys()))
    print("   " + " ".join([f"{EMOTION_LABELS[c]:>8}" for c in sorted(EMOTION_LABELS.keys())]))
    for i, row in enumerate(cm):
        emotion = EMOTION_LABELS[sorted(EMOTION_LABELS.keys())[i]]
        print(f"   {emotion:8} " + " ".join([f"{val:8}" for val in row]))
    
    print("\n5. Saving model...")
    joblib.dump(model, MODEL_FILE)
    print(f"   Saved to {MODEL_FILE}")
    
    # save feature stats
    feature_stats = {
        'mean': np.mean(X, axis=0),
        'std': np.std(X, axis=0)
    }
    joblib.dump(feature_stats, SCALER_FILE)
    print(f"   Saved stats to {SCALER_FILE}")
    
    print("\n=== TRAINING COMPLETE ===")
    print(f"Multi-class emotion accuracy: {acc*100:.2f}%")
    
    return model, acc

def compute_sleepiness_score(emotion_probabilities, emotion_codes):
    """
    Compute weighted sleepiness score from emotion probabilities
    
    Returns score from 0 (fully alert) to 1 (very sleepy)
    """
    sleepiness = 0.0
    
    for prob, code in zip(emotion_probabilities, emotion_codes):
        emotion_name = EMOTION_LABELS.get(code, 'unknown')
        weight = EMOTION_SLEEPINESS_WEIGHTS.get(emotion_name, 0.5)
        sleepiness += prob * weight
    
    return sleepiness

def predict_with_sleepiness(audio_file):
    """Predict emotions and compute sleepiness score"""
    
    if not os.path.exists(MODEL_FILE):
        print(f"ERROR: {MODEL_FILE} not found. Train model first.")
        return None
    
    model = joblib.load(MODEL_FILE)
    
    # extract features
    features = extract_features(audio_file)
    if features is None:
        return None
    
    features_reshaped = features.reshape(1, -1)
    
    # get emotion probabilities
    emotion_probs = model.predict_proba(features_reshaped)[0]
    emotion_codes = model.classes_
    
    # predicted emotion
    predicted_code = model.predict(features_reshaped)[0]
    predicted_emotion = EMOTION_LABELS[predicted_code]
    confidence = emotion_probs[list(emotion_codes).index(predicted_code)]
    
    # compute sleepiness score
    sleepiness_score = compute_sleepiness_score(emotion_probs, emotion_codes)
    
    return {
        'predicted_emotion': predicted_emotion,
        'confidence': confidence,
        'sleepiness_score': sleepiness_score,
        'emotion_probabilities': {EMOTION_LABELS[code]: prob for code, prob in zip(emotion_codes, emotion_probs)}
    }

if __name__ == "__main__":
    train_emotion_classifier()
