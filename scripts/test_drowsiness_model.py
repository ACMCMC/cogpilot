#!/usr/bin/env python3
"""
Test the trained drowsiness model on new audio files
Also demonstrates real-time inference pipeline
"""

import numpy as np
from scipy.io import wavfile
import joblib
import sys
import os

MODEL_FILE = "sleepiness_detector.pkl"
SCALER_FILE = "feature_scaler.pkl"

def extract_features(file_name):
    """
    Same feature extraction as training (scipy-based)
    """
    try:
        # load audio with scipy
        sample_rate, audio = wavfile.read(file_name)
        
        # convert to float and normalize
        if audio.dtype == np.int16:
            audio = audio.astype(np.float32) / 32768.0
        elif audio.dtype == np.int32:
            audio = audio.astype(np.float32) / 2147483648.0
        
        # if stereo, take first channel
        if len(audio.shape) > 1:
            audio = audio[:, 0]
        
        # extract simple features (matching training)
        features = []
        
        # 1. time domain features
        features.append(np.mean(audio))
        features.append(np.std(audio))
        features.append(np.mean(np.abs(audio)))
        features.append(np.max(np.abs(audio)))
        
        # 2. zero crossing rate
        zero_crossings = np.where(np.diff(np.sign(audio)))[0]
        zcr = len(zero_crossings) / len(audio)
        features.append(zcr)
        
        # 3. energy
        energy = np.sum(audio ** 2) / len(audio)
        features.append(energy)
        
        # 4. spectral features
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
        
        # 5. mel-frequency binning
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
        print(f"Error: {e}")
        return None

def predict_drowsiness(audio_file):
    """
    Predict if speaker sounds drowsy
    Returns: (prediction, confidence)
    """
    # load model
    if not os.path.exists(MODEL_FILE):
        print(f"ERROR: {MODEL_FILE} not found. Run train_drowsiness_model.py first")
        return None, None
    
    model = joblib.load(MODEL_FILE)
    
    # optional: load scaler for normalization
    # feature_stats = joblib.load(SCALER_FILE)
    
    # extract features
    features = extract_features(audio_file)
    if features is None:
        return None, None
    
    # predict
    features_reshaped = features.reshape(1, -1)
    prediction = model.predict(features_reshaped)[0]
    
    # get confidence (probability)
    proba = model.predict_proba(features_reshaped)[0]
    confidence = proba[prediction]
    
    return prediction, confidence

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python test_drowsiness_model.py <audio_file.wav>")
        print("Example: python test_drowsiness_model.py sample_audio.wav")
        exit(1)
    
    audio_file = sys.argv[1]
    
    if not os.path.exists(audio_file):
        print(f"ERROR: {audio_file} not found")
        exit(1)
    
    print(f"Analyzing: {audio_file}")
    
    pred, conf = predict_drowsiness(audio_file)
    
    if pred is None:
        print("Failed to analyze audio")
        exit(1)
    
    state = "SLEEPY" if pred == 1 else "ALERT"
    print(f"\nPrediction: {state}")
    print(f"Confidence: {conf*100:.2f}%")
    
    # interpret confidence
    if conf > 0.8:
        certainty = "Very confident"
    elif conf > 0.6:
        certainty = "Confident"
    else:
        certainty = "Uncertain"
    
    print(f"Model is: {certainty}")
