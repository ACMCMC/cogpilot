#!/usr/bin/env python3
"""
Test emotion classifier with sleepiness scoring
"""

import sys
import os
from train_emotion_classifier import predict_with_sleepiness

def interpret_sleepiness(score):
    """Convert sleepiness score to human-readable description"""
    if score >= 0.8:
        return "VERY SLEEPY", "🔴 CRITICAL"
    elif score >= 0.6:
        return "SLEEPY", "🟠 WARNING"
    elif score >= 0.4:
        return "DROWSY", "🟡 CAUTION"
    elif score >= 0.2:
        return "ALERT", "🟢 GOOD"
    else:
        return "VERY ALERT", "🟢 EXCELLENT"

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python test_emotion_classifier.py <audio_file.wav>")
        print("Example: python test_emotion_classifier.py ravdess_data/Actor_01/03-01-02-01-01-01-15.wav")
        exit(1)
    
    audio_file = sys.argv[1]
    
    if not os.path.exists(audio_file):
        print(f"ERROR: {audio_file} not found")
        exit(1)
    
    print(f"Analyzing: {audio_file}")
    print()
    
    result = predict_with_sleepiness(audio_file)
    
    if result is None:
        print("Failed to analyze audio")
        exit(1)
    
    # display results
    print("="*60)
    print("EMOTION CLASSIFICATION")
    print("="*60)
    print(f"Predicted Emotion: {result['predicted_emotion'].upper()}")
    print(f"Confidence: {result['confidence']*100:.2f}%")
    
    print()
    print("="*60)
    print("EMOTION PROBABILITIES")
    print("="*60)
    for emotion, prob in sorted(result['emotion_probabilities'].items(), key=lambda x: x[1], reverse=True):
        bar_length = int(prob * 30)
        bar = "█" * bar_length + "░" * (30 - bar_length)
        print(f"{emotion:10} | {bar} {prob*100:5.1f}%")
    
    print()
    print("="*60)
    print("SLEEPINESS SCORE")
    print("="*60)
    score = result['sleepiness_score']
    state, level = interpret_sleepiness(score)
    
    # visual bar
    bar_length = int(score * 50)
    bar = "█" * bar_length + "░" * (50 - bar_length)
    
    print(f"Score: {score:.3f} / 1.000")
    print(f"[{bar}]")
    print(f"\nState: {state}")
    print(f"Level: {level}")
    
    print()
    print("="*60)
    print("INTERPRETATION")
    print("="*60)
    
    if score >= 0.7:
        print("⚠️  HIGH DROWSINESS DETECTED")
        print("   Driver shows signs of significant fatigue.")
        print("   Recommend immediate break or rest stop.")
    elif score >= 0.5:
        print("⚠️  MODERATE DROWSINESS")
        print("   Driver may be experiencing fatigue.")
        print("   Consider taking a break soon.")
    elif score >= 0.3:
        print("✓  MILD ALERTNESS CONCERNS")
        print("   Driver appears somewhat tired but functional.")
        print("   Monitor and plan break within 30-60 mins.")
    else:
        print("✓  DRIVER APPEARS ALERT")
        print("   No immediate drowsiness concerns detected.")
        print("   Continue monitoring during trip.")
    
    print("="*60)
