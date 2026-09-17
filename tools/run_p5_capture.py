#!/usr/bin/env python3
"""
Phase 5 coordinated capture: starts recorder, then launches app + triggers stream.
Designed to work exactly like the Phase 4 acceptance script.
"""
import subprocess, sys, time, os, re

DEVICE = "emulator-5554"
PORT   = 19350
FLV    = "tools/v11_phase5_broadcast.flv"
SECS   = 20

def run(cmd):
    p = subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    out, err = p.communicate()
    return (out + err).strip()

# Cleanup
if os.path.exists(FLV): os.remove(FLV)

print("1. Reverse port...")
run(f"adb -s {DEVICE} reverse tcp:{PORT} tcp:{PORT}")

print(f"2. Starting recorder ({SECS}s)...")
rec = subprocess.Popen(
    [sys.executable, "tools/m15c_rtmp_flv_recorder.py", str(PORT), FLV, str(SECS)],
    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1
)
time.sleep(1.5)

print("3. Force-stop & launch app...")
run(f"adb -s {DEVICE} shell am force-stop com.fairylive.app")
time.sleep(0.5)
run(f"adb -s {DEVICE} shell am start -n com.fairylive.app/.MainActivity")
time.sleep(4)

# Get UI
def ui():
    run(f"adb -s {DEVICE} shell uiautomator dump /sdcard/ux.xml")
    run(f'adb -s {DEVICE} pull /sdcard/ux.xml C:/Users/benny/AppData/Local/Temp/ux.xml')
    txt = open('C:/Users/benny/AppData/Local/Temp/ux.xml', encoding='utf-8').read()
    return {t: b for t, b in re.findall(r'text="([^"]+)"[^>]*bounds="(\[[0-9,\[\]]+\])"', txt)}

def tap(bs):
    ns = list(map(int, re.findall(r'\d+', bs)))
    cx, cy = (ns[0]+ns[2])//2, (ns[1]+ns[3])//2
    run(f"adb -s {DEVICE} shell input tap {cx} {cy}")
    return cx, cy

print("4. Finding Start Live...")
e = ui()
sk = next((k for k in e if "Start Live" in k), None)
if sk:
    cx, cy = tap(e[sk]); print(f"   Tapped '{sk}' @ ({cx},{cy})")
else:
    # Try bottom 3rd of screen
    run(f"adb -s {DEVICE} shell input tap 540 1817"); print("   Fallback tap 540,1817")
time.sleep(2)

print("5. Consent dialog?")
e2 = ui()
ck = next((k for k in e2 if k.strip() == "Start"), None)
if ck:
    cx, cy = tap(e2[ck]); print(f"   Granted @ ({cx},{cy})")
    time.sleep(3)
else:
    print("   No dialog")

print("6. LIVE check...")
e3 = ui()
live = any("LIVE" in k or "Stop Broadcast" in k for k in e3)
print(f"   ● LIVE = {live}")
for k in e3:
    if any(x in k for x in ["LIVE","Stop","Broadcast","On Air"]): print(f"     {k}")

print(f"7. Waiting {SECS}s for recorder...")
try:
    out, _ = rec.communicate(timeout=SECS + 15)
    print(out[-500:] if out else "(no output)")
except subprocess.TimeoutExpired:
    rec.kill(); print("   Recorder timed out — killing")

print("8. Stop broadcast...")
e4 = ui()
sk2 = next((k for k in e4 if "Stop" in k and "Broadcast" in k), None)
if sk2: tap(e4[sk2]); print(f"   Tapped '{sk2}'")
else: run(f"adb -s {DEVICE} shell input tap 540 1430"); print("   Fallback stop")

sz = os.path.getsize(FLV) if os.path.exists(FLV) else 0
print(f"\nResult: {FLV}  {sz:,} bytes")
sys.exit(0 if sz > 5000 else 1)
