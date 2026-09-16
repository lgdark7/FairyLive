import os
import sys
import time
import subprocess
import re

ADB = "adb"

def run_cmd(cmd):
    p = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    return p.stdout.strip()

print("1. Reverse port...")
run_cmd(f"{ADB} reverse tcp:19350 tcp:19350")

print("2. Launching RTMP server...")
sproc = subprocess.Popen([sys.executable, "-u", "tools/test_rtmp_server.py", "--port", "19350"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
time.sleep(1.0)

print("3. Launching app...")
run_cmd(f"{ADB} shell am force-stop com.fairylive.app")
time.sleep(1.0)
run_cmd(f"{ADB} shell am start -n com.fairylive.app/.MainActivity")
time.sleep(2.0)
run_cmd(f"{ADB} shell input tap 180 2250") # Dashboard
time.sleep(0.5)

print("4. Tapping GO LIVE...")
run_cmd(f"{ADB} shell input tap 540 1166")
time.sleep(1.2)
run_cmd(f"{ADB} shell input tap 893 1703")

print("5. Streaming for 12 seconds...")
for i in range(12):
    time.sleep(1.0)
    pid = run_cmd(f"{ADB} shell pidof com.fairylive.app")
    print(f"  Sec {i+1}/12: PID={pid}")

print("6. Stopping broadcast...")
run_cmd(f"{ADB} shell input tap 540 1166")
time.sleep(1.5)

sproc.terminate()
out, _ = sproc.communicate(timeout=5)
print("=== RTMP SERVER OUTPUT ===")
print(out)
