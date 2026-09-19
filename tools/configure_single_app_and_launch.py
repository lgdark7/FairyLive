import subprocess
import time

def sh(cmd):
    p = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    return p.stdout.strip()

def tap(x, y, delay=1.5):
    sh(f"adb -s 26081JEGR07015 shell input tap {x} {y}")
    time.sleep(delay)

print("=== SELECTING SINGLE APP CAPTURE IN SETTINGS ===")
# Cancel any existing dialog
tap(660, 1547, delay=1)

# Tap Settings tab (899, 2314)
print("Navigating to Settings...")
tap(899, 2314, delay=2)

# Tap Single App / Game (Android 14+) at (540, 1242)
print("Selecting 'Single App / Game (Android 14+)'...")
tap(540, 1242, delay=1)

# Tap Studio tab (540, 2314)
print("Navigating back to Studio...")
tap(540, 2314, delay=2)

# Tap GO LIVE (682, 2135)
print("Tapping GO LIVE...")
sh("adb -s 26081JEGR07015 shell logcat -c")
tap(682, 2135, delay=2)

# Dump dialog UI
sh("adb -s 26081JEGR07015 shell uiautomator dump /sdcard/pixel6a_single_app_dialog.xml")
sh("adb -s 26081JEGR07015 pull /sdcard/pixel6a_single_app_dialog.xml tools/pixel6a_single_app_dialog.xml")

import xml.etree.ElementTree as ET
root = ET.parse('tools/pixel6a_single_app_dialog.xml').getroot()
for n in root.iter('node'):
    text = n.attrib.get('text', '')
    rid = n.attrib.get('resource-id', '')
    bounds = n.attrib.get('bounds', '')
    if text:
        print(f"text={text!r}, rid={rid!r}, bounds={bounds}")

