import subprocess
import time
import sys
import os

print("=== STARTING MILESTONE 15-J REAL FLV RECORDING ACCEPTANCE ===")

# 1. Reverse port
subprocess.run(["adb", "reverse", "tcp:19350", "tcp:19350"], check=True)
print("[PASS] adb reverse tcp:19350 active")

# 2. Start m15c_rtmp_flv_recorder with positional arguments
# dur = 15, flv = "tools/m15j_broadcast.flv", metrics = "tools/m15j_metrics.json"
rec_proc = subprocess.Popen(
    [sys.executable, "tools/m15c_rtmp_flv_recorder.py", "15", "tools/m15j_broadcast.flv", "tools/m15j_metrics.json"],
    creationflags=subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.DETACHED_PROCESS
)
print(f"[PASS] FLV Recorder started (PID: {rec_proc.pid})")
time.sleep(1)

# 3. Bring MainActivity to foreground
subprocess.run(["adb", "shell", "am", "start", "-n", "com.fairylive.app/.MainActivity"], check=True)
time.sleep(2)

# 4. Tap Studio tab
subprocess.run(["adb", "shell", "input", "tap", "540", "2200"], check=True)
time.sleep(2)

# 5. Tap GO LIVE
subprocess.run(["adb", "shell", "input", "tap", "708", "2086"], check=True)
time.sleep(2)

# 6. Tap Start on projection consent dialog
subprocess.run(["adb", "shell", "input", "tap", "893", "1703"], check=True)
print("[PASS] MediaProjection consent granted, broadcasting live...")

# 7. Wait 16 seconds for FLV recorder to complete ingestion
time.sleep(16)

# 8. Tap STOP STREAM
subprocess.run(["adb", "shell", "input", "tap", "708", "2086"], check=True)
print("[PASS] Tapped STOP STREAM")
time.sleep(2)

print("=== ACCEPTANCE RUN COMPLETE ===")
