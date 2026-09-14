import subprocess
import time
import re
import statistics
import json

PACKAGE = "com.fairylive.app"
COMPONENT = "com.fairylive.app/.MainActivity"

def run_cmd(cmd):
    res = subprocess.run(cmd, capture_output=True, text=True, check=True)
    return res.stdout.strip()

print("--- FAIRY LIVE CONTROLLED STARTUP REVALIDATION BENCHMARK ---")

# Step 0: Ensure package is installed and do 1 warmup run
print("[Step 0] Initial pre-warm launch to ensure package dexopt is completed...")
run_cmd(["adb", "shell", "am", "start-activity", "-W", "-n", COMPONENT])
time.sleep(2.0)
run_cmd(["adb", "shell", "am", "force-stop", PACKAGE])
time.sleep(2.0)

runs = 5
total_times = []
wait_times = []

print(f"[Step 1] Executing {runs} controlled cold-start measurements...")
for i in range(1, runs + 1):
    # Ensure cold state
    run_cmd(["adb", "shell", "am", "force-stop", PACKAGE])
    # Drop caches / wait for kernel cleanup
    time.sleep(2.0)
    
    out = run_cmd(["adb", "shell", "am", "start-activity", "-W", "-n", COMPONENT])
    
    total_match = re.search(r'TotalTime:\s*(\d+)', out)
    wait_match = re.search(r'WaitTime:\s*(\d+)', out)
    
    t_val = int(total_match.group(1)) if total_match else None
    w_val = int(wait_match.group(1)) if wait_match else None
    
    if t_val is not None:
        total_times.append(t_val)
    if w_val is not None:
        wait_times.append(w_val)
        
    print(f"  Run {i}: TotalTime = {t_val} ms, WaitTime = {w_val} ms")
    time.sleep(1.0)

summary = {
    "protocol": "adb am force-stop -> 2s settle -> adb am start-activity -W -n com.fairylive.app/.MainActivity",
    "target": "Google Pixel 8 AVD (Android 15, API 35)",
    "runs": runs,
    "total_times_ms": total_times,
    "wait_times_ms": wait_times,
    "total_mean_ms": round(statistics.mean(total_times), 2),
    "total_median_ms": round(statistics.median(total_times), 2),
    "total_min_ms": min(total_times),
    "total_max_ms": max(total_times),
    "total_stdev_ms": round(statistics.stdev(total_times), 2),
    "wait_mean_ms": round(statistics.mean(wait_times), 2),
    "wait_median_ms": round(statistics.median(wait_times), 2)
}

print("\n--- RESULTS ---")
print(json.dumps(summary, indent=2))

with open("tools/startup_revalidation_ledger.json", "w") as f:
    json.dump(summary, f, indent=2)

print("\nSaved to tools/startup_revalidation_ledger.json")
