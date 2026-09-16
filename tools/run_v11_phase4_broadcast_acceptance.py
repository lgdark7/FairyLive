import subprocess
import time
import os
import sys

def run(c):
    p = subprocess.Popen(c, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    out, err = p.communicate()
    return out.strip()

print("1. Reverse port 19350...")
run("adb reverse tcp:19350 tcp:19350")

flv_file = "tools/v11_phase4_broadcast.flv"
if os.path.exists(flv_file):
    os.remove(flv_file)

print("2. Starting RTMP recorder for 12 seconds in background...")
rec = subprocess.Popen([sys.executable, "tools/m15c_rtmp_flv_recorder.py", "19350", flv_file, "12"])
time.sleep(1.2)

print("3. Tapping GO LIVE (708, 2086)...")
run("adb shell input tap 708 2086")
time.sleep(1.2)

print("4. Granting MediaProjection consent (893, 1703)...")
run("adb shell input tap 893 1703")
time.sleep(2.0)

print("5. Screen capture of live streaming broadcast...")
run("adb shell screencap -p /sdcard/live_stream_p4.png")
run("adb pull /sdcard/live_stream_p4.png screenshots/v11_phase4_live_output.png")

print("6. Waiting for recorder completion...")
rec.wait(timeout=20)

print("7. Tapping Stop Broadcast (708, 2086)...")
run("adb shell input tap 708 2086")
time.sleep(1.0)

if os.path.exists(flv_file):
    size = os.path.getsize(flv_file)
    print("SUCCESS: Recorded Phase 4 FLV:", size, "bytes")
else:
    print("FAILED: No FLV recorded")
