#!/usr/bin/env bash
# Collects OEM Audio Diagnostics and dumpsys from a connected target device
set -e

DEVICE_SERIAL="$1"
if [ -z "$DEVICE_SERIAL" ]; then
    echo "Usage: ./tools/collect_device_diagnostics.sh <device_serial>"
    echo "Current connected devices:"
    adb devices
    exit 1
fi

OUT_FILE="OEM_REDBMI_AUDIO_RAW.txt"
echo "Collecting diagnostics from $DEVICE_SERIAL into $OUT_FILE..."

{
    echo "==================================================="
    echo "DEVICE INVENTORY: $DEVICE_SERIAL"
    echo "Timestamp: $(date)"
    echo "==================================================="
    echo "--- BUILD PROPERTIES ---"
    adb -s "$DEVICE_SERIAL" shell getprop ro.product.manufacturer
    adb -s "$DEVICE_SERIAL" shell getprop ro.product.model
    adb -s "$DEVICE_SERIAL" shell getprop ro.build.version.release
    adb -s "$DEVICE_SERIAL" shell getprop ro.build.version.sdk
    adb -s "$DEVICE_SERIAL" shell getprop ro.build.version.incremental
    adb -s "$DEVICE_SERIAL" shell getprop ro.hardware
    adb -s "$DEVICE_SERIAL" shell getprop ro.board.platform

    echo "--- DUMPSYS AUDIO_FLINGER ---"
    adb -s "$DEVICE_SERIAL" shell dumpsys media.audio_flinger

    echo "--- DUMPSYS AUDIO ---"
    adb -s "$DEVICE_SERIAL" shell dumpsys audio

    echo "--- DUMPSYS MEDIA_PROJECTION ---"
    adb -s "$DEVICE_SERIAL" shell dumpsys media_projection
} > "$OUT_FILE" 2>&1

echo "Done. Saved $(wc -l < "$OUT_FILE") lines to $OUT_FILE."
