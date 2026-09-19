#!/usr/bin/env python3
"""
Phase 7 Step 4A: 60-Second Broadcast Qualification & Bitstream Analysis
1. Port reverse tcp:19350
2. Launch RTMP FLV recorder for 60s
3. Tap Start Live on Home Dashboard
4. Grant MediaProjection permission
5. Monitor streaming telemetry & codecs
6. Wait for 60s capture
7. Tap Stop Live
8. Analyze FLV metrics (video frames, audio access units, PTS continuity, A/V sync)
"""
import subprocess, sys, time, os, re, json

DEVICE = "emulator-5554"
PORT = 19350
FLV_PATH = "tools/phase7/v12_phase7_rc_broadcast.flv"
DURATION_S = 60

os.makedirs("tools/phase7", exist_ok=True)
os.makedirs("screenshots/phase7", exist_ok=True)
if os.path.exists(FLV_PATH):
    os.remove(FLV_PATH)

def run(cmd):
    p = subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, errors='replace')
    out, err = p.communicate()
    return (out + err).strip()

print("1. Reversing port 19350...")
run(f"adb -s {DEVICE} reverse tcp:{PORT} tcp:{PORT}")

print("2. Starting RTMP FLV recorder...")
rec = subprocess.Popen(
    [sys.executable, "tools/m15c_rtmp_flv_recorder.py", str(PORT), FLV_PATH, str(DURATION_S)],
    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1
)
time.sleep(2)

print("3. Tapping 'Start Live' button on Home dashboard...")
run(f"adb -s {DEVICE} shell input tap 540 1431")
time.sleep(2)

print("4. Handling consent dialog...")
# Check if consent dialog is shown
run(f"adb -s {DEVICE} shell uiautomator dump /sdcard/consent.xml")
run(f"adb -s {DEVICE} pull /sdcard/consent.xml C:/Users/benny/AppData/Local/Temp/consent.xml")
try:
    c_xml = open("C:/Users/benny/AppData/Local/Temp/consent.xml", encoding='utf-8').read()
    m = re.search(r'text="Start"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', c_xml)
    if m:
        cx = (int(m.group(1)) + int(m.group(3))) // 2
        cy = (int(m.group(2)) + int(m.group(4))) // 2
        print(f"   Found 'Start' button at ({cx}, {cy}), tapping...")
        run(f"adb -s {DEVICE} shell input tap {cx} {cy}")
    else:
        print("   Tapping default fallback at (893, 1703)...")
        run(f"adb -s {DEVICE} shell input tap 893 1703")
except Exception as e:
    print(f"   Error parsing consent dialog: {e}, tapping fallback...")
    run(f"adb -s {DEVICE} shell input tap 893 1703")

time.sleep(3)

print("5. Monitoring live stream...")
# Take on-air screenshot
run(f"adb -s {DEVICE} exec-out screencap -p > screenshots/phase7/rc_broadcast_on_air.png")
print("   Screenshot saved: screenshots/phase7/rc_broadcast_on_air.png")

# Check codec count
codecs = run(f"adb -s {DEVICE} shell dumpsys media.player | grep -i codec | grep -i fairylive")
print(f"   Active codecs info: {codecs[:200] if codecs else 'Codec query executed'}")

# Memory check
mem_raw = run(f"adb -s {DEVICE} shell dumpsys meminfo com.fairylive.app")
pss_lines = [l for l in mem_raw.splitlines() if "TOTAL PSS:" in l or "TOTAL RSS:" in l or "TOTAL:" in l]
print(f"   Memory snapshot:\n   " + "\n   ".join(pss_lines))

# Wait for recorder to complete
print(f"6. Recording for {DURATION_S}s...")
try:
    out, _ = rec.communicate(timeout=DURATION_S + 15)
    print("=== Recorder Output ===")
    for line in out.splitlines()[-15:]:
        print(f"   {line}")
except subprocess.TimeoutExpired:
    print("   Recorder timed out, terminating...")
    rec.kill()

print("7. Stopping stream...")
# Tap Stop Live if visible or send broadcast stop
run(f"adb -s {DEVICE} shell input tap 540 1431")
time.sleep(2)

print("8. Checking FLV output...")
if os.path.exists(FLV_PATH):
    sz = os.path.getsize(FLV_PATH)
    print(f"   FLV recorded: {sz} bytes ({sz / (1024*1024):.2f} MB)")
else:
    print("   FLV file was not generated!")

metrics_json = FLV_PATH + "_metrics.json"
if os.path.exists(metrics_json):
    with open(metrics_json) as f:
        m_data = json.load(f)
    print(f"   Bitstream Metrics Summary:")
    print(f"     Duration: {m_data.get('duration_seconds')}s")
    print(f"     Video Frames: {m_data.get('video', {}).get('total_frames')}")
    print(f"     FPS: {m_data.get('video', {}).get('fps')}")
    print(f"     Audio AUs: {m_data.get('audio', {}).get('total_access_units')}")
    print(f"     A/V Delta: {m_data.get('av_sync', {}).get('final_delta_ms')} ms")
    print(f"     A/V Status: {m_data.get('av_sync', {}).get('status')}")
