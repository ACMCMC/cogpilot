#!/usr/bin/env python3
"""
Combined drowsiness + sentiment detector
Uses audio proxy model + text sentiment for higher confidence detection
"""

import numpy as np
import sys
import os

# import the drowsiness detector
from test_drowsiness_model import predict_drowsiness

try:
    from transformers import pipeline
    SENTIMENT_AVAILABLE = True
except ImportError:
    SENTIMENT_AVAILABLE = False
    print("WARNING: transformers not installed. Sentiment analysis disabled.")
    print("Install with: pip install transformers torch")

def analyze_combined(audio_file, text=None):
    """
    Combined analysis: audio + text sentiment
    Returns more confident detection when both signals agree
    """
    
    # audio analysis
    print(f"Analyzing audio: {audio_file}")
    audio_pred, audio_conf = predict_drowsiness(audio_file)
    
    if audio_pred is None:
        print("Audio analysis failed")
        return None
    
    audio_state = "SLEEPY" if audio_pred == 1 else "ALERT"
    print(f"Audio: {audio_state} (confidence: {audio_conf*100:.2f}%)")
    
    # text sentiment (if available)
    text_state = None
    text_score = 0.5
    
    if text and SENTIMENT_AVAILABLE:
        print(f"\nAnalyzing text: '{text}'")
        classifier = pipeline("sentiment-analysis", model="distilbert-base-uncased-finetuned-sst-2-english")
        result = classifier(text)[0]
        
        sentiment = result['label']
        text_score = result['score']
        
        # map sentiment to sleepy/alert
        # NEGATIVE sentiment often correlates with tiredness
        if sentiment == 'NEGATIVE':
            text_state = "SLEEPY"
        else:
            text_state = "ALERT"
        
        print(f"Text: {text_state} ({sentiment}, score: {text_score*100:.2f}%)")
    
    # combined decision
    print("\n=== COMBINED ANALYSIS ===")
    
    if text_state and text_state == audio_state:
        # both agree, high confidence
        combined_conf = (audio_conf + text_score) / 2
        print(f"✓ Audio and text AGREE: {audio_state}")
        print(f"Combined confidence: {combined_conf*100:.2f}%")
        
        if combined_conf > 0.75:
            certainty = "VERY HIGH confidence"
        else:
            certainty = "HIGH confidence"
        
        print(f"Result: {certainty} - speaker is {audio_state}")
        
    elif text_state and text_state != audio_state:
        # disagree, medium confidence
        print(f"⚠ Audio says {audio_state}, text says {text_state}")
        print(f"Result: UNCERTAIN - mixed signals")
        
    else:
        # only audio available
        print(f"Audio only: {audio_state}")
        
        if audio_conf > 0.8:
            certainty = "High confidence"
        elif audio_conf > 0.6:
            certainty = "Medium confidence"
        else:
            certainty = "Low confidence"
        
        print(f"Result: {certainty} (no text sentiment available)")
    
    return {
        'audio_state': audio_state,
        'audio_confidence': audio_conf,
        'text_state': text_state,
        'text_score': text_score if text else None,
        'combined': audio_state if not text_state else (audio_state if text_state == audio_state else 'UNCERTAIN')
    }

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python combined_detector.py <audio_file.wav> [\"text transcript\"]")
        print("\nExample 1 (audio only):")
        print("  python combined_detector.py sample.wav")
        print("\nExample 2 (audio + text):")
        print("  python combined_detector.py sample.wav \"I'm so tired, can't focus anymore\"")
        exit(1)
    
    audio_file = sys.argv[1]
    text = sys.argv[2] if len(sys.argv) > 2 else None
    
    if not os.path.exists(audio_file):
        print(f"ERROR: {audio_file} not found")
        exit(1)
    
    result = analyze_combined(audio_file, text)
