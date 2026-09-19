import subprocess
import time

def sh(cmd):
    p = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    return p.stdout.strip()

def tap(x, y, delay=1.5):
    sh(f"adb shell input tap {x} {y}")
    time.sleep(delay)

print("=== STARTING FULL V1.2.1 VERIFICATION CYCLE ===")
sh("adb shell logcat -c")

# 1. Bring MainActivity to foreground
sh("adb shell am start -n com.fairylive.app/.MainActivity")
time.sleep(2)

# If any previous dialog is showing, tap Dismiss (556, 1528)
tap(556, 1528, delay=1)

# Ensure Creator Studio tab is selected
tap(540, 2260, delay=1)

# Tap GO LIVE (708, 2086)
print("[1] Tapping GO LIVE...")
tap(708, 2086, delay=2)

# Tap Consent Start (893, 1703)
print("[2] Tapping Consent 'Start'...")
tap(893, 1703, delay=2)

# Tap Settings in App Picker (674, 1510)
print("[3] Selecting Settings app in App Picker...")
tap(674, 1510, delay=5)

# Verify Active Stream
logs_start = sh("adb shell logcat -d | grep FairyLifecycle")
print("--- ACTIVE STREAM LIFECYCLE ---")
print(logs_start)
print("-------------------------------")

# Wait 5 seconds
time.sleep(5)

# Bring back to foreground and Stop
print("[4] Bringing FAIRY LIVE to foreground and stopping...")
sh("adb shell am start -n com.fairylive.app/.MainActivity")
time.sleep(2)
tap(708, 2086, delay=3)

# Verify Stop
logs_stop = sh("adb shell logcat -d | grep FairyLifecycle | tail -n 15")
print("--- STOP LIFECYCLE ---")
print(logs_stop)
print("----------------------")

# Verify UI state
sh("adb shell uiautomator dump /sdcard/ui_cycle.xml")
ui_dump = sh("adb shell cat /sdcard/ui_cycle.xml")
if "btn_go_live" in ui_dump and "GO LIVE" in ui_dump:
    print("UI SUCCESS: Creator Studio returned cleanly to IDLE (GO LIVE visible)!")
else:
    print("UI State:", ui_dump[:300])

print("=== CYCLE COMPLETE ===")
