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

print("2. Starting RTMP recorder for 18 seconds...")
flv_file = "tools/v11_web_broadcast.flv"
if os.path.exists(flv_file):
    os.remove(flv_file)

recorder = subprocess.Popen([sys.executable, "tools/m15c_rtmp_flv_recorder.py", "19350", flv_file, "15"])
time.sleep(1.5)

print("3. Launching FAIRY LIVE...")
run("adb shell am force-stop com.fairylive.app")
time.sleep(0.8)
run("adb shell am start -n com.fairylive.app/.MainActivity")
time.sleep(2.5)

print("4. Switching to Creator Studio...")
run("adb shell input tap 540 2270")
time.sleep(1.5)

# Open Add Overlay Sheet
print("5. Opening Add Overlay Sheet...")
run("adb shell input tap 980 1835") # Layers or Add element
time.sleep(1.0)
# Dump UI to locate Add element button
run("adb shell uiautomator dump /sdcard/dump.xml")
run("adb pull /sdcard/dump.xml tools/dump_studio_web.xml")
