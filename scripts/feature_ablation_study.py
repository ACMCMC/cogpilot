#!/usr/bin/env python3
"""
Feature ablation study: test which features matter most
"""

import numpy as np
import os
import glob
from scipy.io import wavfile
from sklearn.model_selection import train_test_split
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import accuracy_score
import random

random.seed(2262)
np.random.seed(2262)

DATA_PATH = "ravdess_data/"

def extract_features(file_name, feature_groups='all'):
    """
    Extract features with option to include/exclude groups
    feature_groups: 'all', 'time', 'spectral', 'mel', or list of group names
    """
    try:
        sample_rate, audio = wavfile.read(file_name)
        
        if audio.dtype == np.int16:
            audio = audio.astype(np.float32) / 32768.0
        elif audio.dtype == np.int32:
            audio = audio.astype(np.float32) / 2147483648.0
        
        if len(audio.shape) > 1:
            audio = audio[:, 0]
        
        features = []
        feature_names = []
        
        # time domain features
        if feature_groups == 'all' or 'time' in feature_groups:
            features.extend([
                np.mean(audio),
                np.std(audio),
                np.mean(np.abs(audio)),
                np.max(np.abs(audio)),
            ])
            feature_names.extend(['mean', 'std', 'mean_abs', 'peak'])
            
            # zero crossing
            zero_crossings = np.where(np.diff(np.sign(audio)))[0]
            zcr = len(zero_crossings) / len(audio)
            features.append(zcr)
            feature_names.append('zcr')
            
            # energy
            energy = np.sum(audio ** 2) / len(audio)
            features.append(energy)
            feature_names.append('energy')
        
        # spectral features
        if feature_groups == 'all' or 'spectral' in feature_groups:
            fft = np.fft.fft(audio)
            magnitude = np.abs(fft[:len(fft)//2])
            freqs = np.fft.fftfreq(len(audio), 1/sample_rate)[:len(fft)//2]
            
            spectral_centroid = np.sum(freqs * magnitude) / (np.sum(magnitude) + 1e-10)
            features.append(spectral_centroid)
            feature_names.append('spectral_centroid')
            
            cumsum = np.cumsum(magnitude)
            rolloff_idx = np.where(cumsum >= 0.85 * cumsum[-1])[0]
            spectral_rolloff = freqs[rolloff_idx[0]] if len(rolloff_idx) > 0 else 0
            features.append(spectral_rolloff)
            feature_names.append('spectral_rolloff')
            
            spectral_bandwidth = np.sqrt(np.sum(((freqs - spectral_centroid) ** 2) * magnitude) / (np.sum(magnitude) + 1e-10))
            features.append(spectral_bandwidth)
            feature_names.append('spectral_bandwidth')
        
        # mel features
        if feature_groups == 'all' or 'mel' in feature_groups:
            fft = np.fft.fft(audio)
            magnitude = np.abs(fft[:len(fft)//2])
            
            mel_bins = 20
            bin_size = len(magnitude) // mel_bins
            for i in range(mel_bins):
                start = i * bin_size
                end = start + bin_size if i < mel_bins - 1 else len(magnitude)
                bin_energy = np.mean(magnitude[start:end])
                features.append(bin_energy)
                feature_names.append(f'mel_bin_{i}')
        
        return np.array(features), feature_names
        
    except Exception as e:
        return None, None

def load_data(feature_groups='all'):
    """Load data with specific feature groups"""
    x, y = [], []
    feature_names = None
    
    print(f"Loading data with feature groups: {feature_groups}")
    
    low_arousal_emotions = [1, 2, 4]
    high_arousal_emotions = [3, 5, 6, 8]
    
    for file in glob.glob(DATA_PATH + "/**/*.wav", recursive=True):
        file_name = os.path.basename(file)
        parts = file_name.split("-")
        
        if len(parts) < 3:
            continue
            
        emotion = int(parts[2])
        
        if emotion in [1, 2, 4]: 
            label = 1  # sleepy
        elif emotion in [3, 5, 6, 8]: 
            label = 0  # alert
        else:
            continue
        
        feature, names = extract_features(file, feature_groups)
        if feature is not None:
            x.append(feature)
            y.append(label)
            if feature_names is None:
                feature_names = names
    
    return np.array(x), np.array(y), feature_names

def evaluate_feature_group(feature_groups, name):
    """Train and evaluate with specific feature groups"""
    print(f"\n{'='*60}")
    print(f"Testing: {name}")
    print(f"{'='*60}")
    
    X, y, feature_names = load_data(feature_groups)
    
    if len(X) == 0:
        print("No data loaded!")
        return None
    
    print(f"Features: {len(feature_names)} total")
    print(f"Samples: {len(X)}")
    
    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.1, random_state=2262, stratify=y
    )
    
    model = RandomForestClassifier(
        n_estimators=150,
        max_depth=20,
        random_state=2262,
        n_jobs=-1
    )
    model.fit(X_train, y_train)
    
    y_pred = model.predict(X_test)
    acc = accuracy_score(y_test, y_pred)
    
    print(f"Accuracy: {acc*100:.2f}%")
    
    # feature importance
    importances = model.feature_importances_
    top_features_idx = np.argsort(importances)[-5:][::-1]
    print(f"\nTop 5 features:")
    for idx in top_features_idx:
        print(f"  {feature_names[idx]}: {importances[idx]:.4f}")
    
    return {
        'name': name,
        'accuracy': acc,
        'num_features': len(feature_names),
        'top_features': [(feature_names[idx], importances[idx]) for idx in top_features_idx]
    }

if __name__ == "__main__":
    print("\n" + "="*60)
    print("FEATURE ABLATION STUDY")
    print("="*60)
    
    results = []
    
    # baseline: all features
    results.append(evaluate_feature_group('all', "Baseline (All Features)"))
    
    # individual groups
    results.append(evaluate_feature_group(['time'], "Time Domain Only"))
    results.append(evaluate_feature_group(['spectral'], "Spectral Only"))
    results.append(evaluate_feature_group(['mel'], "Mel Bins Only"))
    
    # combinations
    results.append(evaluate_feature_group(['time', 'spectral'], "Time + Spectral"))
    results.append(evaluate_feature_group(['time', 'mel'], "Time + Mel"))
    results.append(evaluate_feature_group(['spectral', 'mel'], "Spectral + Mel"))
    
    # summary
    print("\n" + "="*60)
    print("SUMMARY")
    print("="*60)
    print(f"{'Configuration':<30} {'Features':<12} {'Accuracy':<12}")
    print("-"*60)
    
    results = [r for r in results if r is not None]
    results_sorted = sorted(results, key=lambda x: x['accuracy'], reverse=True)
    
    for r in results_sorted:
        print(f"{r['name']:<30} {r['num_features']:<12} {r['accuracy']*100:<12.2f}%")
    
    print("\n" + "="*60)
    print(f"Best configuration: {results_sorted[0]['name']}")
    print(f"Achieves {results_sorted[0]['accuracy']*100:.2f}% with {results_sorted[0]['num_features']} features")
    print("="*60)
