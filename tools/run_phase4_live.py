import subprocess
import time
import os
import sys

print("Configuring adb reverse...")
subprocess.run(["adb", "reverse", "tcp:19350", "tcp:19350"])

flv_file = "tools/v11_phase4_broadcast.flv"
if os.path.exists(flv_file):
    os.remove(flv_file)

print("Spawning RTMP recorder...")
# Duration: 15s
rec = subprocess.Popen([sys.executable, "tools/m15c_rtmp_flv_recorder.py", "15", flv_file, "tools/v11_phase4_metrics.json"])
time.sleep(1.0)

print("Tapping GO LIVE in app...")
subprocess.run(["adb", "shell", "input", "tap", "700", "2080"])
time.sleep(1.2)

# In case consent prompt appears
subprocess.run(["adb", "shell", "input", "tap", "893", "1703"])
time.sleep(2.0)

print("Capturing live composite broadcast screenshot...")
subprocess.run(["adb", "shell", "screencap", "-p", "/sdcard/phase4_live_stream.png"])
subprocess.run(["adb", "pull", "/sdcard/phase4_live_stream.png", "screenshots/v11_phase4_live_output.png"])

print("Waiting for recorder to finish (15s)...")
try:
    rec.wait(timeout=25)
except subprocess.TimeoutExpired:
    print("Recorder wait timed out, terminating...")
    rec.kill()

print("Stopping broadcast in app...")
subprocess.run(["adb", "shell", "input", "tap", "700", "2080"])
time.sleep(1.0)

if os.path.exists(flv_file):
    size = os.path.getsize(flv_file)
    print(f"SUCCESS: Recorded Phase 4 Broadcast FLV: {size} bytes")
else:
    print("WARNING: FLV not captured")
