#!/usr/bin/env bash
#
# test_concat_artifacts.sh — Detect format mismatches and click/pop artifacts
# in TTS chunk directories.
#
# Usage: ./test_concat_artifacts.sh /path/to/chunk_directory
#
# Requires: ffmpeg, ffprobe
#
set -euo pipefail

CHUNK_DIR="${1:?Usage: $0 /path/to/chunk_directory}"

if [[ ! -d "$CHUNK_DIR" ]]; then
    echo "ERROR: Directory not found: $CHUNK_DIR"
    exit 1
fi

if ! command -v ffprobe &>/dev/null || ! command -v ffmpeg &>/dev/null; then
    echo "ERROR: ffmpeg and ffprobe are required"
    exit 1
fi

echo "=== TTS Concat Artifact Detector ==="
echo "Chunk directory: $CHUNK_DIR"
echo

# --- Step 1: Detect formats of all files ---
echo "--- Step 1: Format Detection ---"
declare -A FORMAT_COUNTS
CHUNK_FORMAT=""
GAP_FORMAT=""

for f in "$CHUNK_DIR"/*.mp3 "$CHUNK_DIR"/*.wav 2>/dev/null; do
    [[ -f "$f" ]] || continue
    basename=$(basename "$f")
    # Read first 12 bytes to detect format
    header=$(xxd -l 12 -p "$f" 2>/dev/null || true)
    if [[ "$header" == 52494646"'"*"'"57415645* ]] || [[ "$header" == 52494646*57415645* ]]; then
        fmt="WAV"
    elif [[ "$header" == 494433* ]] || [[ "${header:0:4}" == "ffff" ]] || [[ "${header:0:4}" == "fffb" ]] || [[ "${header:0:4}" == "fff3" ]]; then
        fmt="MP3"
    else
        fmt="UNKNOWN"
    fi

    if [[ "$basename" == chunk_gap* ]] || [[ "$basename" == skip_gap* ]]; then
        GAP_FORMAT="$fmt"
        echo "  GAP   $basename -> $fmt"
    else
        CHUNK_FORMAT="$fmt"
        echo "  CHUNK $basename -> $fmt"
    fi
done

echo
if [[ -n "$CHUNK_FORMAT" && -n "$GAP_FORMAT" ]]; then
    if [[ "$CHUNK_FORMAT" != "$GAP_FORMAT" ]]; then
        echo "!! FORMAT MISMATCH DETECTED !!"
        echo "   Chunks are: $CHUNK_FORMAT"
        echo "   Gaps are:   $GAP_FORMAT"
        echo "   This WILL cause click/pop artifacts at junctions."
        MISMATCH=1
    else
        echo "Formats are uniform ($CHUNK_FORMAT). No mismatch."
        MISMATCH=0
    fi
elif [[ -z "$GAP_FORMAT" ]]; then
    echo "No gap files found (chunkGap may be 0). No mismatch possible."
    MISMATCH=0
else
    echo "Could not determine formats."
    MISMATCH=0
fi

echo

# --- Step 2: Concatenate with ffmpeg and detect amplitude spikes ---
echo "--- Step 2: Artifact Detection via Concatenation ---"

TMPDIR=$(mktemp -d)
trap "rm -rf $TMPDIR" EXIT

# Build file list in chunk order
FILELIST="$TMPDIR/filelist.txt"
: > "$FILELIST"
for f in $(ls "$CHUNK_DIR"/chunk_*.mp3 "$CHUNK_DIR"/chunk_*.wav "$CHUNK_DIR"/chunk_gap.* "$CHUNK_DIR"/skip_gap.* 2>/dev/null | sort -t_ -k2 -n); do
    [[ -f "$f" ]] || continue
    echo "file '$f'" >> "$FILELIST"
done

# Check we have files
if [[ ! -s "$FILELIST" ]]; then
    echo "No chunk files found to concatenate."
    exit 0
fi

# Try to concatenate using the same method as the app (concat demuxer)
CONCAT_OUT="$TMPDIR/concat_test.wav"
echo "Concatenating via ffmpeg concat demuxer..."
if ffmpeg -y -f concat -safe 0 -i "$FILELIST" -c:a pcm_s16le "$CONCAT_OUT" 2>"$TMPDIR/ffmpeg.log"; then
    echo "Concatenation successful."

    # Analyze for amplitude spikes using astats
    echo
    echo "--- Step 3: Amplitude Analysis ---"

    # Get duration of first chunk to know where junctions are
    FIRST_CHUNK=$(ls "$CHUNK_DIR"/chunk_0.* 2>/dev/null | head -1)
    if [[ -n "$FIRST_CHUNK" ]]; then
        FIRST_DUR=$(ffprobe -v quiet -show_entries format=duration -of csv=p=0 "$FIRST_CHUNK" 2>/dev/null || echo "0")
        echo "First chunk duration: ${FIRST_DUR}s"

        # Analyze a short window around the first junction
        JUNCTION_START=$(echo "$FIRST_DUR - 0.01" | bc 2>/dev/null || echo "0")
        echo "Analyzing 20ms window around first junction (at ~${FIRST_DUR}s)..."

        # Extract junction region and get peak amplitude
        JUNCTION_WAV="$TMPDIR/junction.wav"
        if ffmpeg -y -ss "$JUNCTION_START" -t 0.02 -i "$CONCAT_OUT" "$JUNCTION_WAV" 2>/dev/null; then
            PEAK=$(ffmpeg -i "$JUNCTION_WAV" -af "volumedetect" -f null - 2>&1 | grep "max_volume" | awk '{print $5}')
            echo "Peak volume at junction: ${PEAK:-N/A} dB"

            if [[ -n "$PEAK" ]]; then
                # If peak is above -3dB, likely an artifact (silence should be very quiet)
                PEAK_NUM=$(echo "$PEAK" | tr -d '-')
                if (( $(echo "$PEAK_NUM < 3" | bc -l 2>/dev/null || echo 0) )); then
                    echo "!! ARTIFACT LIKELY: Peak amplitude at junction is very high (${PEAK} dB)"
                else
                    echo "Junction appears clean (peak: ${PEAK} dB)"
                fi
            fi
        fi
    fi
else
    echo "Concatenation failed. See: $TMPDIR/ffmpeg.log"
    cat "$TMPDIR/ffmpeg.log" | tail -5
fi

echo
echo "--- Step 4: Generate Correct WAV Silence (Fix Verification) ---"

if [[ "$MISMATCH" == "1" ]] && [[ "$CHUNK_FORMAT" == "WAV" ]]; then
    # Generate WAV silence matching chunk format
    FIRST_CHUNK=$(ls "$CHUNK_DIR"/chunk_0.* 2>/dev/null | head -1)
    if [[ -n "$FIRST_CHUNK" ]]; then
        # Get sample rate and channels from reference chunk
        SAMPLE_RATE=$(ffprobe -v quiet -show_entries stream=sample_rate -of csv=p=0 "$FIRST_CHUNK" 2>/dev/null || echo "24000")
        CHANNELS=$(ffprobe -v quiet -show_entries stream=channels -of csv=p=0 "$FIRST_CHUNK" 2>/dev/null || echo "1")
        echo "Reference format: ${SAMPLE_RATE}Hz, ${CHANNELS}ch"

        # Generate WAV silence
        WAV_GAP="$TMPDIR/correct_gap.wav"
        ffmpeg -y -f lavfi -i "anullsrc=r=${SAMPLE_RATE}:cl=mono" -t 0.3 -c:a pcm_s16le "$WAV_GAP" 2>/dev/null

        # Re-concat with correct gap format
        FIXED_LIST="$TMPDIR/fixed_filelist.txt"
        : > "$FIXED_LIST"
        for f in $(ls "$CHUNK_DIR"/chunk_[0-9]*.* 2>/dev/null | grep -v "gap" | sort -t_ -k2 -n); do
            [[ -f "$f" ]] || continue
            if [[ -s "$FIXED_LIST" ]]; then
                echo "file '$WAV_GAP'" >> "$FIXED_LIST"
            fi
            echo "file '$f'" >> "$FIXED_LIST"
        done

        FIXED_OUT="$TMPDIR/fixed_concat.wav"
        if ffmpeg -y -f concat -safe 0 -i "$FIXED_LIST" -c:a pcm_s16le "$FIXED_OUT" 2>/dev/null; then
            echo "Fixed concatenation successful."

            # Analyze junction in fixed version
            JUNCTION_WAV2="$TMPDIR/junction_fixed.wav"
            if ffmpeg -y -ss "$JUNCTION_START" -t 0.02 -i "$FIXED_OUT" "$JUNCTION_WAV2" 2>/dev/null; then
                PEAK2=$(ffmpeg -i "$JUNCTION_WAV2" -af "volumedetect" -f null - 2>&1 | grep "max_volume" | awk '{print $5}')
                echo "Peak volume at junction (fixed): ${PEAK2:-N/A} dB"
                echo
                if [[ -n "$PEAK2" ]]; then
                    echo "COMPARISON:"
                    echo "  Before fix: ${PEAK:-N/A} dB"
                    echo "  After fix:  ${PEAK2} dB"
                fi
            fi
        fi
    fi
else
    echo "No mismatch to fix, or chunks are not WAV. Skipping."
fi

echo
echo "=== Done ==="
