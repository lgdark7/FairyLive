import subprocess
import time
import os
import sys

def run(cmd):
    p = subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    out, err = p.communicate()
    return out.strip()

run("adb reverse tcp:19350 tcp:19350")
flv_file = "tools/v11_phase4_broadcast.flv"
if os.path.exists(flv_file):
    os.remove(flv_file)

print("Starting recorder...")
rec = subprocess.Popen([sys.executable, "tools/m15c_rtmp_flv_recorder.py", "19350", flv_file, "10"])
time.sleep(1.2)

print("Tapping Go Live...")
run("adb shell input tap 700 2080")
time.sleep(1.0)
run("adb shell input tap 893 1703")
time.sleep(2.0)

run("adb shell screencap -p /sdcard/live_out.png")
run("adb pull /sdcard/live_out.png screenshots/v11_phase4_live_output.png")

print("Waiting for recorder to finish (10s)...")
rec.wait()

print("Stopping broadcast...")
run("adb shell input tap 700 2080")
time.sleep(1.0)

if os.path.exists(flv_file):
    print("CAPTURED FLV:", os.path.getsize(flv_file), "bytes")
