import subprocess
import time
import xml.etree.ElementTree as ET

def sh(cmd):
    p = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    return p.stdout.strip()

def tap(x, y):
    sh(f"adb shell input tap {x} {y}")
    time.sleep(1)

def find_node(text_match=None, rid_match=None):
    sh("adb shell uiautomator dump /sdcard/temp_ui.xml")
    xml_data = sh("adb shell cat /sdcard/temp_ui.xml")
    if not xml_data.startswith("<?xml"):
        return None
    try:
        root = ET.fromstring(xml_data)
        for n in root.iter("node"):
            t = n.attrib.get("text", "")
            r = n.attrib.get("resource-id", "")
            b = n.attrib.get("bounds", "")
            if text_match and text_match.lower() in t.lower():
                return b
            if rid_match and rid_match.lower() in r.lower():
                return b
    except Exception as e:
        print("Parse error:", e)
    return None

def bounds_center(b):
    # [x1,y1][x2,y2]
    import re
    m = re.findall(r'\d+', b)
    if len(m) == 4:
        return (int(m[0]) + int(m[2])) // 2, (int(m[1]) + int(m[3])) // 2
    return (540, 1200)

print("=== STARTING COMPLETE V1.2.1 VERIFICATION ===")
# Clear logcat
sh("adb shell logcat -c")

# Launch FAIRY LIVE
sh("adb shell am start -n com.fairylive.app/.MainActivity")
time.sleep(2)

# Tap Studio tab
tap(540, 2260)
time.sleep(1)

# Find and tap GO LIVE
b_golive = find_node(text_match="GO LIVE")
if b_golive:
    cx, cy = bounds_center(b_golive)
    print(f"Tapping GO LIVE at ({cx}, {cy})...")
    tap(cx, cy)
    time.sleep(2)

# Check for MediaProjection Consent Dialog (Start)
b_start = find_node(text_match="Start", rid_match="button1")
if b_start:
    cx, cy = bounds_center(b_start)
    print(f"Tapping Consent Dialog 'Start' at ({cx}, {cy})...")
    tap(cx, cy)
    time.sleep(2)

# Check for App Picker (Settings)
b_settings = find_node(text_match="Settings")
if b_settings:
    cx, cy = bounds_center(b_settings)
    print(f"Selecting Settings in App Picker at ({cx}, {cy})...")
    tap(cx, cy)
    time.sleep(5)
else:
    print("Settings app not directly visible, tapping default picker slot (674, 1510)...")
    tap(674, 1510)
    time.sleep(5)

# Wait 10 seconds for streaming to run
print("Streaming live for 10 seconds...")
time.sleep(10)

# Verify logcat during active stream
logs_active = sh("adb shell logcat -d | grep FairyLifecycle")
print("--- ACTIVE STREAM LIFECYCLE LOGS ---")
print(logs_active)
print("------------------------------------")

# Bring FAIRY LIVE back to foreground to tap STOP
sh("adb shell am start -n com.fairylive.app/.MainActivity")
time.sleep(2)
b_stop = find_node(text_match="STOP")
if b_stop:
    cx, cy = bounds_center(b_stop)
    print(f"Tapping STOP STREAM at ({cx}, {cy})...")
    tap(cx, cy)
    time.sleep(3)

logs_teardown = sh("adb shell logcat -d | grep FairyLifecycle | tail -n 25")
print("--- TEARDOWN LIFECYCLE LOGS ---")
print(logs_teardown)
print("-------------------------------")

print("=== VERIFICATION COMPLETE ===")
