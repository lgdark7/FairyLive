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

print("2. Starting RTMP FLV recorder for 15s...")
flv_file = "tools/v11_phase3_broadcast.flv"
if os.path.exists(flv_file):
    os.remove(flv_file)

recorder = subprocess.Popen([sys.executable, "tools/m15c_rtmp_flv_recorder.py", "19350", flv_file, "15"])
time.sleep(1.5)

print("3. Tapping GO LIVE (708, 2086)...")
run("adb shell input tap 708 2086")
time.sleep(1.2)

print("4. MediaProjection consent tap (893, 1703)...")
run("adb shell input tap 893 1703")
time.sleep(2.5)

print("5. Live streaming broadcast with Rotated & Cropped Web Overlay...")
run("adb shell screencap -p /sdcard/v11p3_live_screen.png")
run("adb pull /sdcard/v11p3_live_screen.png screenshots/v11p3_live_screen.png")

time.sleep(14)
print("6. Stopping broadcast in app (708, 2086)...")
run("adb shell input tap 708 2086")
time.sleep(2)

print("7. Checking FLV file...")
if os.path.exists(flv_file):
    print("Recorded Phase 3 FLV size:", os.path.getsize(flv_file))
else:
    print("FLV recording missing!")
