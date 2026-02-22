#!/usr/bin/env python3
"""
Download RAVDESS dataset from alternative source
For hackathon demo, uses a smaller subset or alternative dataset
"""

import os
import urllib.request
import zipfile
import sys

print("="*70)
print("RAVDESS DATASET DOWNLOAD")
print("="*70)
print()

# Check if Kaggle credentials exist
kaggle_dir = os.path.expanduser("~/.kaggle")
kaggle_json = os.path.join(kaggle_dir, "kaggle.json")

if not os.path.exists(kaggle_json):
    print("❌ Kaggle API credentials not found")
    print()
    print("OPTION 1: Set up Kaggle API (Recommended)")
    print("-" * 70)
    print("1. Go to: https://www.kaggle.com/settings/account")
    print("2. Scroll to 'API' section")
    print("3. Click 'Create New Token'")
    print("4. Save kaggle.json to:", kaggle_dir)
    print()
    print("Then run this script again.")
    print()
    print("OPTION 2: Manual Download")
    print("-" * 70)
    print("1. Go to: https://www.kaggle.com/datasets/uwrfkaggle/ravdess-emotional-speech-audio")
    print("2. Click 'Download' button")
    print("3. Unzip to ravdess_data/:")
    print("   unzip ravdess-emotional-speech-audio.zip -d ravdess_data/")
    print()
    print("="*70)
    sys.exit(1)

# If we have credentials, try to download
try:
    from kaggle.api.kaggle_api_extended import KaggleApi
    
    print("✓ Kaggle credentials found")
    print("Downloading RAVDESS dataset...")
    print("This may take 5-10 minutes (~200MB)")
    print()
    
    api = KaggleApi()
    api.authenticate()
    
    # Download and unzip
    api.dataset_download_files(
        'uwrfkaggle/ravdess-emotional-speech-audio',
        path='.',
        unzip=True
    )
    
    print()
    print("✓ Download complete!")
    print("Dataset saved to:", os.path.abspath('ravdess_data'))
    print()
    print("Next step: python train_drowsiness_model.py")
    
except Exception as e:
    print(f"❌ Download failed: {e}")
    print()
    print("Please download manually:")
    print("1. Go to: https://www.kaggle.com/datasets/uwrfkaggle/ravdess-emotional-speech-audio")
    print("2. Download the dataset")
    print("3. Unzip to ravdess_data/")
    sys.exit(1)
