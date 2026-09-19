import subprocess
import time

def sh(cmd):
    p = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    return p.stdout.strip()

def tap(x, y, delay=1.5):
    sh(f"adb shell input tap {x} {y}")
    time.sleep(delay)

print("=== IMMEDIATE RESTART TEST ===")
sh("adb shell logcat -c")

# Tap GO LIVE (708, 2086)
print("[1] Tapping GO LIVE for Session 2...")
tap(708, 2086, delay=2)

# Tap Consent Start (893, 1703)
print("[2] Tapping Consent 'Start'...")
tap(893, 1703, delay=2)

# Tap Settings in App Picker (674, 1510)
print("[3] Selecting Settings app...")
tap(674, 1510, delay=5)

logs_session2 = sh("adb shell logcat -d | grep FairyLifecycle")
print("--- SESSION 2 ACTIVE STREAM LIFECYCLE ---")
print(logs_session2)
print("-----------------------------------------")

# Wait 5 seconds
time.sleep(5)

# Bring FAIRY LIVE back to foreground and Stop
print("[4] Stopping Session 2...")
sh("adb shell am start -n com.fairylive.app/.MainActivity")
time.sleep(2)
tap(708, 2086, delay=3)

logs_session2_stop = sh("adb shell logcat -d | grep FairyLifecycle | tail -n 12")
print("--- SESSION 2 STOP LIFECYCLE ---")
print(logs_session2_stop)
print("--------------------------------")

print("=== IMMEDIATE RESTART TEST COMPLETE ===")
