import subprocess
import time
import os
import sys
import struct
import json

def run(c):
    p = subprocess.Popen(c, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    out, err = p.communicate()
    return out.strip()

print("1. Reverse port 19350...")
run("adb reverse tcp:19350 tcp:19350")

print("2. Starting RTMP FLV recorder in background...")
flv_file = "tools/v11_broadcast.flv"
if os.path.exists(flv_file):
    os.remove(flv_file)

recorder = subprocess.Popen([sys.executable, "tools/m15c_rtmp_flv_recorder.py", "19350", flv_file, "16"])
time.sleep(1.5)

print("3. Tapping GO LIVE in FAIRY LIVE Studio (708, 2086)...")
run("adb shell input tap 708 2086")
time.sleep(1.2)

print("4. Tapping Start on Android system consent dialog (893, 1703)...")
run("adb shell input tap 893 1703")
time.sleep(2.5)

print("5. Recording active live stream with composited widgets...")
# Capture Creator Screen showing live streamer HUD
run("adb shell screencap -p /sdcard/v11_creator_screen_active.png")
run("adb pull /sdcard/v11_creator_screen_active.png screenshots/v11_creator_screen_active.png")

# Open creator chat sheet from floating HUD
run("adb shell input tap 490 160")
time.sleep(1.5)
run("adb shell screencap -p /sdcard/v11_creator_chat_open.png")
run("adb pull /sdcard/v11_creator_chat_open.png screenshots/v11_creator_chat_open.png")
run("adb shell input keyevent KEYCODE_BACK") # dismiss chat
time.sleep(1.0)

# Toggle mic on HUD
run("adb shell input tap 530 160")
time.sleep(1.0)

print("6. Waiting for FLV recording to conclude...")
recorder.wait(timeout=22)

# Stop live broadcast
run("adb shell input tap 708 2086")
time.sleep(1)

if os.path.exists(flv_file):
    print("Recorded FLV size:", os.path.getsize(flv_file))
else:
    print("FLV file not found!")
