import subprocess
import time
import os
import sys

def run(cmd):
    p = subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    out, err = p.communicate()
    return out.strip()

print("Step 1: Setup reverse port 19350...")
run("adb reverse tcp:19350 tcp:19350")

print("Step 2: Start background FLV recorder for 12 seconds...")
flv_file = "tools/v11_phase3_live_broadcast.flv"
if os.path.exists(flv_file):
    os.remove(flv_file)

recorder = subprocess.Popen([sys.executable, "tools/m15c_rtmp_flv_recorder.py", "19350", flv_file, "12"])
time.sleep(1.2)

print("Step 3: Tapping GO LIVE (bounds: [390,2018][1027,2155] -> tap 700, 2080)...")
run("adb shell input tap 700 2080")
time.sleep(1.2)

print("Step 4: Grant MediaProjection consent (tap 893, 1703)...")
run("adb shell input tap 893 1703")
time.sleep(2.0)

print("Step 5: Capture live studio & broadcast screen...")
run("adb shell screencap -p /sdcard/phase3_broadcast_screen.png")
run("adb pull /sdcard/phase3_broadcast_screen.png screenshots/phase3_broadcast_screen.png")

print("Step 6: Streaming for 12 seconds...")
time.sleep(11.0)

print("Step 7: Tapping Stop Broadcast (tap 700, 2080)...")
run("adb shell input tap 700 2080")
time.sleep(1.5)

print("Step 8: Checking captured FLV recording...")
if os.path.exists(flv_file):
    size = os.path.getsize(flv_file)
    print(f"SUCCESS: Captured Phase 3 Live FLV ({size} bytes)!")
else:
    print("FAILED: FLV file not found!")
