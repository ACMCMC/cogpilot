#!/bin/bash
# Helper script to setup RAVDESS after manual download

echo "=== RAVDESS Setup Helper ==="
echo ""
echo "Looking for downloaded RAVDESS file..."
echo ""

# Check common download locations
DOWNLOAD_LOCATIONS=(
    "$HOME/Downloads/ravdess-emotional-speech-audio.zip"
    "$HOME/Downloads/Audio_Speech_Actors_01-24.zip"
    "$HOME/Downloads/archive.zip"
)

FOUND=""
for loc in "${DOWNLOAD_LOCATIONS[@]}"; do
    if [ -f "$loc" ]; then
        FOUND="$loc"
        echo "✓ Found: $loc"
        break
    fi
done

if [ -z "$FOUND" ]; then
    echo "❌ No RAVDESS download found in ~/Downloads/"
    echo ""
    echo "Please:"
    echo "1. Go to the opened Kaggle page (or visit manually)"
    echo "2. Click 'Download' button"
    echo "3. Wait for download to complete"
    echo "4. Run this script again: ./setup_downloaded_ravdess.sh"
    exit 1
fi

echo ""
echo "Extracting dataset..."
unzip -q "$FOUND" -d /tmp/ravdess_extract

# Find the actual audio folders
if [ -d "/tmp/ravdess_extract/Audio_Speech_Actors_01-24" ]; then
    mv /tmp/ravdess_extract/Audio_Speech_Actors_01-24 ravdess_data
elif [ -d "/tmp/ravdess_extract/ravdess-emotional-speech-audio" ]; then
    mv /tmp/ravdess_extract/ravdess-emotional-speech-audio/* ravdess_data/
else
    # Find Actor folders
    mkdir -p ravdess_data
    find /tmp/ravdess_extract -type d -name "Actor_*" -exec mv {} ravdess_data/ \;
fi

rm -rf /tmp/ravdess_extract

echo "✓ Dataset extracted to ravdess_data/"
echo ""

# Verify
ACTOR_COUNT=$(ls -d ravdess_data/Actor_* 2>/dev/null | wc -l | tr -d ' ')
echo "Found $ACTOR_COUNT actor folders"

if [ "$ACTOR_COUNT" -ge 24 ]; then
    echo "✓ Dataset complete!"
    echo ""
    echo "Ready to train:"
    echo "  python train_drowsiness_model.py"
else
    echo "⚠️  Warning: Expected 24 actors, found $ACTOR_COUNT"
fi
