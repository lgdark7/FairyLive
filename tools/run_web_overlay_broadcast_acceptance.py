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

print("2. Starting RTMP FLV recorder in background for 18 seconds...")
flv_file = "tools/v11_web_broadcast.flv"
if os.path.exists(flv_file):
    os.remove(flv_file)

recorder = subprocess.Popen([sys.executable, "tools/m15c_rtmp_flv_recorder.py", "19350", flv_file, "16"])
time.sleep(1.5)

print("3. Tapping GO LIVE in Creator Studio (708, 2086)...")
run("adb shell input tap 708 2086")
time.sleep(1.2)

print("4. Granting MediaProjection consent (893, 1703)...")
run("adb shell input tap 893 1703")
time.sleep(2.5)

print("5. Live streaming broadcast with Web Overlay and YouTube sub counter active...")
# Screencap active broadcast
run("adb shell screencap -p /sdcard/v11_web_live_screen.png")
run("adb pull /sdcard/v11_web_live_screen.png screenshots/v11_web_live_screen.png")

print("6. Waiting for recorder...")
recorder.wait(timeout=22)

print("7. Stopping stream...")
run("adb shell input tap 708 2086")
time.sleep(1)

if os.path.exists(flv_file):
    print("Recorded broadcast FLV size:", os.path.getsize(flv_file))
else:
    print("FLV recording not found!")
