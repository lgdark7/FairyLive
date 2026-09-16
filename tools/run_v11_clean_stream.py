import subprocess
import time
import os
import sys
import struct
import json

def cmd(c):
    p = subprocess.Popen(c, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    out, err = p.communicate()
    return out.strip()

print("1. Reverse port 19350...")
cmd("adb reverse tcp:19350 tcp:19350")

print("2. Pushing verified settings...")
cmd("adb push tools/fairy_live_settings_proper.xml /data/local/tmp/fairy_live_settings.xml")
cmd("adb shell run-as com.fairylive.app cp /data/local/tmp/fairy_live_settings.xml /data/data/com.fairylive.app/shared_prefs/fairy_live_settings.xml")

print("3. Starting FLV recorder for 12 seconds...")
flv_file = "tools/v11_broadcast.flv"
if os.path.exists(flv_file):
    os.remove(flv_file)

proc = subprocess.Popen([sys.executable, "tools/m15c_rtmp_flv_recorder.py", "19350", flv_file, "12"])
time.sleep(1.5)

print("4. Starting FAIRY LIVE MainActivity...")
cmd("adb shell am force-stop com.fairylive.app")
time.sleep(1)
cmd("adb shell am start -n com.fairylive.app/.MainActivity")
time.sleep(2.5)

# Tap Studio tab at (540, 2270)
print("5. Opening Studio...")
cmd("adb shell input tap 540 2270")
time.sleep(1.5)

# Tap GO LIVE at (708, 2086)
print("6. Tapping GO LIVE...")
cmd("adb shell input tap 708 2086")
time.sleep(1.5)

# Tap Start on system permission dialog
print("7. Granting MediaProjection...")
cmd("adb shell input tap 750 1480")
time.sleep(1.0)
cmd("adb shell input tap 750 1480")

print("8. Streaming for 8 seconds...")
time.sleep(8)

# Stop stream
print("9. Tapping STOP STREAM...")
cmd("adb shell input tap 708 2086")
time.sleep(1)

proc.wait(timeout=10)
print("FLV recording finished. Size:", os.path.getsize(flv_file) if os.path.exists(flv_file) else 0)
