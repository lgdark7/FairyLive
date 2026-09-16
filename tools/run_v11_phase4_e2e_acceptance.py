import subprocess
import time
import os
import sys

def run(cmd):
    p = subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    out, err = p.communicate()
    return out.strip()

print("Step 1: Setting up reverse port forwarding 19350...")
run("adb reverse tcp:19350 tcp:19350")

print("Step 2: Starting RTMP recorder for 15s...")
flv_file = "tools/v11_phase4_broadcast.flv"
if os.path.exists(flv_file):
    os.remove(flv_file)

recorder = subprocess.Popen([sys.executable, "tools/m15c_rtmp_flv_recorder.py", "19350", flv_file, "15"])
time.sleep(1.2)

print("Step 3: Tapping GO LIVE (700, 2080)...")
run("adb shell input tap 700 2080")
time.sleep(1.2)

print("Step 4: Granting MediaProjection consent (893, 1703)...")
run("adb shell input tap 893 1703")
time.sleep(2.0)

print("Step 5: Capturing live broadcast screen with active HUD...")
run("adb shell screencap -p /sdcard/phase4_live_screen.png")
run("adb pull /sdcard/phase4_live_screen.png screenshots/v11_phase4_live_output.png")

print("Step 6: Streaming for 12 seconds...")
time.sleep(11.0)

print("Step 7: Tapping Stop Broadcast (700, 2080)...")
run("adb shell input tap 700 2080")
time.sleep(1.5)

if os.path.exists(flv_file):
    size = os.path.getsize(flv_file)
    print(f"SUCCESS: Captured Phase 4 Live Broadcast FLV ({size} bytes)!")
else:
    print("FAILED: FLV file not found!")
