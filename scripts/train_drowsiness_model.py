#!/usr/bin/env python3
"""
Drowsiness detector using proxy learning from emotion data
Uses RAVDESS dataset, maps low arousal emotions to sleepy states

Simplified version that works around librosa import issues
"""

import os
import glob
import numpy as np
from scipy.io import wavfile
from scipy import signal
from sklearn.model_selection import train_test_split
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import accuracy_score, classification_report
import joblib
import random

# seed for reproducibility
random.seed(2262)
np.random.seed(2262)

# config
DATA_PATH = "ravdess_data/"  
MODEL_FILE = "sleepiness_detector.pkl"
SCALER_FILE = "feature_scaler.pkl"

def extract_features(file_name):
    """
    Extracts simple acoustic features from audio file using scipy
    Features: mean, std, energy, zero_crossing_rate, spectral properties
    """
    try:
        # load audio with scipy (no librosa dependency)
        sample_rate, audio = wavfile.read(file_name)
        
        # convert to float and normalize
        if audio.dtype == np.int16:
            audio = audio.astype(np.float32) / 32768.0
        elif audio.dtype == np.int32:
            audio = audio.astype(np.float32) / 2147483648.0
        
        # if stereo, take first channel
        if len(audio.shape) > 1:
            audio = audio[:, 0]
        
        # extract simple features
        features = []
        
        # 1. time domain features
        features.append(np.mean(audio))  # mean amplitude
        features.append(np.std(audio))   # standard deviation
        features.append(np.mean(np.abs(audio)))  # mean absolute value
        features.append(np.max(np.abs(audio)))   # peak amplitude
        
        # 2. zero crossing rate (correlates with pitch)
        zero_crossings = np.where(np.diff(np.sign(audio)))[0]
        zcr = len(zero_crossings) / len(audio)
        features.append(zcr)
        
        # 3. energy
        energy = np.sum(audio ** 2) / len(audio)
        features.append(energy)
        
        # 4. spectral features (simple FFT-based)
        fft = np.fft.fft(audio)
        magnitude = np.abs(fft[:len(fft)//2])
        
        # spectral centroid (center of mass of spectrum)
        freqs = np.fft.fftfreq(len(audio), 1/sample_rate)[:len(fft)//2]
        spectral_centroid = np.sum(freqs * magnitude) / (np.sum(magnitude) + 1e-10)
        features.append(spectral_centroid)
        
        # spectral rolloff (frequency below which 85% of energy lies)
        cumsum = np.cumsum(magnitude)
        rolloff_idx = np.where(cumsum >= 0.85 * cumsum[-1])[0]
        spectral_rolloff = freqs[rolloff_idx[0]] if len(rolloff_idx) > 0 else 0
        features.append(spectral_rolloff)
        
        # spectral bandwidth
        spectral_bandwidth = np.sqrt(np.sum(((freqs - spectral_centroid) ** 2) * magnitude) / (np.sum(magnitude) + 1e-10))
        features.append(spectral_bandwidth)
        
        # 5. mel-frequency binning (simplified MFCCs alternative)
        # divide spectrum into 20 mel-scaled bins
        mel_bins = 20
        mel_features = []
        bin_size = len(magnitude) // mel_bins
        for i in range(mel_bins):
            start = i * bin_size
            end = start + bin_size if i < mel_bins - 1 else len(magnitude)
            bin_energy = np.mean(magnitude[start:end])
            mel_features.append(bin_energy)
        
        features.extend(mel_features)
        
        return np.array(features)
        
    except Exception as e:
        # dont print every error, too noisy
        return None

def load_data():
    """
    Loads RAVDESS and applies proxy labels:
    - Low arousal (neutral, calm, sad) = SLEEPY (label 1)
    - High arousal (happy, angry, fearful, surprised) = ALERT (label 0)
    """
    x, y = [], []
    
    print("Scanning audio files from RAVDESS...")
    # RAVDESS naming: Actor_XX/03-01-02-01-01-01-01.wav
    # third part is emotion code
    
    files_found = 0
    for file in glob.glob(DATA_PATH + "/**/*.wav", recursive=True):
        files_found += 1
        file_name = os.path.basename(file)
        parts = file_name.split("-")
        
        if len(parts) < 3:
            continue
            
        emotion = int(parts[2])
        
        # THE PROXY HACK:
        # 01=neutral, 02=calm, 04=sad -> LOW AROUSAL -> SLEEPY
        # 03=happy, 05=angry, 06=fearful, 08=surprised -> HIGH AROUSAL -> ALERT
        if emotion in [1, 2, 4]: 
            label = 1  # sleepy
        elif emotion in [3, 5, 6, 8]: 
            label = 0  # alert
        else:
            continue  # skip disgust (emotion 7), ambiguous
        
        feature = extract_features(file)
        if feature is not None:
            x.append(feature)
            y.append(label)
    
    print(f"Found {files_found} total files, processed {len(x)} samples")
    return np.array(x), np.array(y)

def train_model():
    """Main training pipeline"""
    
    print("\n=== DROWSINESS DETECTOR TRAINING ===\n")
    
    # step 1: load data
    print("1. Loading data & extracting features...")
    X, y = load_data()
    
    if len(X) == 0:
        print(f"ERROR: No data found in {DATA_PATH}")
        print("Download RAVDESS from Kaggle and unzip to ravdess_data/")
        return
    
    print(f"   Loaded {len(X)} samples with {X.shape[1]} features each")
    sleepy_count = np.sum(y == 1)
    alert_count = np.sum(y == 0)
    print(f"   Sleepy samples: {sleepy_count}, Alert samples: {alert_count}")
    
    # step 2: split data
    print("\n2. Splitting data (90/10 train/test - maximizing training data)...")
    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.1, random_state=2262, stratify=y
    )
    print(f"   Train: {len(X_train)}, Test: {len(X_test)}")
    
    # step 3: train model
    print("\n3. Training Random Forest...")
    # RF is fast, robust, works well for small datasets
    model = RandomForestClassifier(
        n_estimators=150,  # decent number of trees
        max_depth=20,      # prevent overfitting
        random_state=2262,
        n_jobs=-1          # use all cores
    )
    model.fit(X_train, y_train)
    print("   Training complete")
    
    # step 4: evaluate
    print("\n4. Evaluating model...")
    y_pred = model.predict(X_test)
    acc = accuracy_score(y_test, y_pred)
    print(f"   Test Accuracy: {acc*100:.2f}%")
    
    print("\n   Classification Report:")
    print(classification_report(y_test, y_pred, 
                                target_names=['Alert', 'Sleepy']))
    
    # feature importance (just for curiousity)
    importances = model.feature_importances_
    top_features = np.argsort(importances)[-5:][::-1]
    print(f"\n   Top 5 feature indices: {top_features}")
    
    # step 5: save model
    print("\n5. Saving model...")
    joblib.dump(model, MODEL_FILE)
    print(f"   Saved to {MODEL_FILE}")
    
    # also save feature stats for normalization later
    feature_stats = {
        'mean': np.mean(X, axis=0),
        'std': np.std(X, axis=0)
    }
    joblib.dump(feature_stats, SCALER_FILE)
    print(f"   Saved feature stats to {SCALER_FILE}")
    
    print("\n=== TRAINING COMPLETE ===")
    print(f"Model ready for inference. Accuracy: {acc*100:.2f}%")
    
    return model, acc

if __name__ == "__main__":
    # check if data exists
    if not os.path.exists(DATA_PATH):
        print(f"ERROR: {DATA_PATH} not found")
        print("\n" + "="*70)
        print("YOU NEED THE REAL RAVDESS DATASET")
        print("="*70)
        print("\nTo get started:")
        print("1. Go to Kaggle: https://www.kaggle.com/datasets/uwrfkaggle/ravdess-emotional-speech-audio")
        print("2. Download 'Audio_Speech_Actors_01-24.zip'")
        print("3. Unzip to ravdess_data/ folder:")
        print("   unzip Audio_Speech_Actors_01-24.zip -d ravdess_data/")
        print("4. Run this script again")
        print("\nNOTE: This dataset is ~200MB and contains REAL human speech")
        print("      with different emotions. No synthetic data!")
        print("="*70)
        exit(1)
    
    trained_model, accuracy = train_model()
