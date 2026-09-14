import os
import sys
import time
import subprocess
import json
import re

ADB = "adb"

def run_cmd(cmd, timeout=30):
    p = subprocess.run(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=timeout)
    return p.stdout.strip()

print("=== RUNNING CONTROLLED ABR DOWNWARD LADDER TEST ===")
run_cmd(f"{ADB} reverse tcp:19350 tcp:19350")
run_cmd(f"{ADB} logcat -c")

# Ensure app is running and on Dashboard
run_cmd(f"{ADB} shell am start -n com.fairylive.app/.MainActivity")
time.sleep(1.5)

# Stepped throttle schedule:
# 0s-7s: 1500 kbps (Healthy, base 1200)
# 7s-15s: 700 kbps (Degraded band: 500-800k -> 1200 -> 800)
# 15s-23s: 420 kbps (Poor band: 250-500k -> 800 -> 500)
# 23s-32s: 180 kbps (Critical band: 1-250k -> 500 -> 300)
sched = "0:1500,7:700,15:420,23:180"
server_cmd = f"python tools/fault_injection_rtmp_server.py --port 19350 --throttle-schedule {sched} --max-sessions 1 --total-duration 38"
sproc = subprocess.Popen(server_cmd, shell=True)
time.sleep(1.5)

# Start Broadcast
run_cmd(f"{ADB} shell input tap 540 1166")
time.sleep(1.2)
run_cmd(f"{ADB} shell input tap 893 1703")

# Stream for 34 seconds through all degradation steps
time.sleep(34)

# Stop Broadcast
run_cmd(f"{ADB} shell input tap 540 1166")
time.sleep(1.5)
sproc.terminate()

# Extract logcat logs
logs = run_cmd(f"{ADB} logcat -d -v time")
abr_decisions = []
stage_audits = []

for line in logs.splitlines():
    if "StreamPipeline" in line or "AdaptiveBitrateCtrl" in line:
        if "ABR Decision" in line or "ABR Step Down" in line or "ABR Emergency Downscale" in line:
            abr_decisions.append(line)
        if "STAGE-AUDIT" in line:
            stage_audits.append(line)

print("\n--- CAPTURED ABR DECISIONS ---")
for d in abr_decisions:
    print(d)

print("\n--- CAPTURED STAGE AUDITS ---")
for s in stage_audits[:10]:
    print(s)

with open("tools/audit_abr_downward_ladder.json", "w") as f:
    json.dump({"decisions": abr_decisions, "stage_audits": stage_audits}, f, indent=2)

print("\nSaved to tools/audit_abr_downward_ladder.json")
