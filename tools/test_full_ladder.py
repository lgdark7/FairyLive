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

print("--- RUNNING CONTROLLED STEPPED THROTTLE TEST (1200 -> 800 -> 500 -> 300) ---")
run_cmd(f"{ADB} reverse tcp:19350 tcp:19350")
run_cmd(f"{ADB} logcat -c")

# Stepped throttle: 0-6s: 1500k, 6-12s: 680k, 12-18s: 420k, 18-26s: 220k, 26-36s: 1500k
sched = "0:1500,6:680,12:420,18:220,26:1500"
server_cmd = f"python tools/fault_injection_rtmp_server.py --port 19350 --throttle-schedule {sched} --max-sessions 1 --total-duration 40"
sproc = subprocess.Popen(server_cmd, shell=True)
time.sleep(1.2)

# Start broadcast
run_cmd(f"{ADB} shell input tap 540 1166")
time.sleep(1.2)
run_cmd(f"{ADB} shell input tap 893 1703")

# Monitor logcat for 35 seconds
time.sleep(33)

# Stop broadcast
run_cmd(f"{ADB} shell input tap 540 1166")
time.sleep(1.0)
sproc.terminate()

logs = run_cmd(f"{ADB} logcat -d -v time")
entries = []
for line in logs.splitlines():
    if "AdaptiveBitrateController" in line and "ABR Decision:" in line:
        entries.append(line)
    elif "STAGE-AUDIT" in line:
        entries.append(line)

print(f"Captured {len(entries)} ABR and Stage Audit entries:")
for e in entries:
    print(e)

with open("tools/audit_full_ladder.txt", "w", encoding="utf-8") as f:
    f.write("\n".join(entries))
