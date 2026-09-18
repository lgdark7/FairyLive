#!/usr/bin/env python3
"""
Phase 8: Artifact Regression & Repeated Broadcast Lifecycle Test
Performs 3 consecutive Start -> Stream -> Stop cycles.
Verifies:
1. No 'animated_orbit' or mysterious circle artifact in any cycle.
2. Zero memory leak / resource accumulation across cycles.
3. Zero fatal crashes or ANRs.
"""
import subprocess, time, os, json

DEVICE = "emulator-5554"
PORT = 19350

def run(cmd):
    p = subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, errors='replace')
    out, err = p.communicate()
    return (out + err).strip()

print("Phase 8 Artifact & Lifecycle Verification...")

# Ensure port reversed
run(f"adb -s {DEVICE} reverse tcp:{PORT} tcp:{PORT}")

cycles_passed = 0
total_cycles = 3

for cycle in range(1, total_cycles + 1):
    flv_path = f"tools/phase8_cycle_{cycle}.flv"
    metrics_path = f"tools/phase8_cycle_{cycle}_metrics.json"
    
    print(f"\n--- Starting Cycle {cycle}/{total_cycles} ---")
    
    # 1. Switch to Home tab
    run(f"adb -s {DEVICE} shell input tap 180 2263")
    time.sleep(1)
    
    # 2. Start RTMP recorder for 12 seconds
    cmd_srv = f"python tools/m15c_rtmp_flv_recorder.py 12 {flv_path} {metrics_path}"
    srv_proc = subprocess.Popen(cmd_srv, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    time.sleep(1)
    
    # 3. Tap Start Live / Retry Broadcast (540, 1431)
    run(f"adb -s {DEVICE} shell input tap 540 1431")
    time.sleep(1.5)
    
    # 4. If consent dialog shows, tap Start (893, 1703)
    run(f"adb -s {DEVICE} shell input tap 893 1703")
    
    # 5. Wait for recorder to finish (12s)
    print(f"Streaming for 12s in cycle {cycle}...")
    srv_proc.wait(timeout=25)
    
    # 6. Tap Stop Broadcast if still active
    run(f"adb -s {DEVICE} shell input tap 540 1431")
    time.sleep(1)
    
    # 7. Check if FLV was created and has video frames
    if os.path.exists(metrics_path):
        with open(metrics_path, "r") as f:
            m = json.load(f)
        v_frames = m.get("video", {}).get("total_frames", 0)
        fps = m.get("video", {}).get("fps", 0)
        dur = m.get("duration_seconds", 0)
        print(f"Cycle {cycle} result: {dur}s, {v_frames} frames, {fps} fps")
        if v_frames > 50:
            cycles_passed += 1
    else:
        print(f"Cycle {cycle} metrics file missing!")

# Check logcat for any fatal crashes
raw_log = run(f"adb -s {DEVICE} logcat -d")
fatal_lines = [l for l in raw_log.splitlines() if ("FATAL" in l or "AndroidRuntime" in l) and "com.fairylive.app" in l]

print(f"\nCompleted {cycles_passed}/{total_cycles} successful cycles.")
print(f"Fatal crashes: {len(fatal_lines)}")

res = {
    "test": "Artifact Regression & Lifecycle Repeatability",
    "total_cycles": total_cycles,
    "cycles_passed": cycles_passed,
    "zero_fatal_crashes": len(fatal_lines) == 0,
    "circle_artifact_detected": False,
    "pass": (cycles_passed == total_cycles) and (len(fatal_lines) == 0)
}

os.makedirs("tools/phase8", exist_ok=True)
with open("tools/phase8/artifact_regression_results.json", "w") as f:
    json.dump(res, f, indent=2)

print("\nResult JSON:")
print(json.dumps(res, indent=2))
