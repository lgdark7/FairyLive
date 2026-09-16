import subprocess
import time
import os
import sys

print("Setting reverse port...")
subprocess.run(["adb", "reverse", "tcp:19350", "tcp:19350"])

flv_file = "tools/v11_phase4_broadcast.flv"
if os.path.exists(flv_file):
    os.remove(flv_file)

print("Starting FLV recorder for 12 seconds...")
rec = subprocess.Popen([sys.executable, "tools/m15c_rtmp_flv_recorder.py", "12", flv_file, "tools/v11_phase4_metrics.json"])
time.sleep(1.2)

print("Tapping Start Live on Home tab (540, 1430)...")
subprocess.run(["adb", "shell", "input", "tap", "540", "1430"])
time.sleep(1.2)

print("Granting MediaProjection consent (893, 1703)...")
subprocess.run(["adb", "shell", "input", "tap", "893", "1703"])
time.sleep(2.0)

print("Capturing live stream screen...")
subprocess.run(["adb", "shell", "screencap", "-p", "/sdcard/phase4_live_screen.png"])
subprocess.run(["adb", "pull", "/sdcard/phase4_live_screen.png", "screenshots/v11_phase4_live_output.png"])

print("Waiting for recorder to finish...")
try:
    rec.wait(timeout=20)
except subprocess.TimeoutExpired:
    print("Timed out, killing recorder")
    rec.kill()

print("Stopping broadcast (540, 1430)...")
subprocess.run(["adb", "shell", "input", "tap", "540", "1430"])
time.sleep(1.0)

if os.path.exists(flv_file):
    print(f"SUCCESS: Recorded Phase 4 FLV ({os.path.getsize(flv_file)} bytes)!")
else:
    print("FAILED: FLV not captured.")
