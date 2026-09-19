#!/usr/bin/env python3
"""
FAIRY LIVE V1.2 Phase 9: Overlay Reality & Creator Studio Polish Validation Script
"""
import os
import sys
import time
import subprocess
import json

DEVICE = "emulator-5554"
PORT = 19350

print("FAIRY LIVE Phase 9 Reality & Polish Validation...")

# 1. Reverse port for RTMP ingest
subprocess.run(f"adb -s {DEVICE} reverse tcp:{PORT} tcp:{PORT}", shell=True, check=True)

# 2. Run FLV broadcast recorder for 12s
os.makedirs("tools/phase9", exist_ok=True)
flv_out = "tools/phase9/phase9_reality_broadcast.flv"
metrics_out = "tools/phase9/phase9_broadcast_metrics.json"

cmd_srv = f"python tools/m15c_rtmp_flv_recorder.py 12 {flv_out} {metrics_out}"
server_proc = subprocess.Popen(cmd_srv, shell=True)
time.sleep(1.5)

# 3. Tap Start Live directly from Studio tab
print("Tapping GO LIVE in Studio (708, 2086)...")
subprocess.run(f"adb -s {DEVICE} shell input tap 708 2086", shell=True)
time.sleep(2.0)

# 4. Consent dialog tap (Start now: 893, 1703)
print("Tapping Consent Start (893, 1703)...")
subprocess.run(f"adb -s {DEVICE} shell input tap 893 1703", shell=True)

# 5. Wait for broadcast duration (12s)
print("Broadcasting live for 12s...")
try:
    server_proc.wait(timeout=25)
except subprocess.TimeoutExpired:
    print("Warning: server_proc timed out")
    server_proc.kill()

# 6. Stop broadcast in Studio
print("Stopping live broadcast (708, 2086)...")
subprocess.run(f"adb -s {DEVICE} shell input tap 708 2086", shell=True)
time.sleep(1)

if os.path.exists(metrics_out):
    with open(metrics_out, "r") as f:
        metrics = json.load(f)
    print("\n--- PHASE 9 BROADCAST FLV METRICS ---")
    print(json.dumps(metrics, indent=2))
else:
    print("Metrics file not generated!")
