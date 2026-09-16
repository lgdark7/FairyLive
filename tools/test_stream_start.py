import os
import sys
import time
import subprocess

ADB = "adb"

def run_cmd(cmd):
    p = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    return p.stdout.strip()

print("Setting up RTMP reverse port...")
run_cmd(f"{ADB} reverse tcp:19350 tcp:19350")

print("Starting test RTMP server...")
server_proc = subprocess.Popen([sys.executable, "tools/test_rtmp_server.py", "--port", "19350", "--duration", "15"])
time.sleep(1.5)

print("Tapping GO LIVE...")
run_cmd(f"{ADB} shell input tap 540 1166")
time.sleep(1.2)
print("Accepting MediaProjection...")
run_cmd(f"{ADB} shell input tap 893 1703")

print("Streaming for 8 seconds...")
time.sleep(8.0)

print("Checking logcat for RTMP and MediaCodec...")
log = run_cmd(f"{ADB} logcat -d -s FairyStreamService RtmpTransport MediaCodec")
print("Log snippet:\n" + "\n".join(log.splitlines()[-15:]))

print("Stopping broadcast...")
run_cmd(f"{ADB} shell input tap 540 1166")
time.sleep(1.0)

server_proc.wait(timeout=5)
print("Done!")
