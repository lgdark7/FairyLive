#!/usr/bin/env python3
import time
import subprocess
import xml.etree.ElementTree as ET

ADB = "adb -s emulator-5554"

def sh(cmd):
    p = subprocess.run(f"{ADB} {cmd}", shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    return p.stdout.strip()

def dump_ui(name):
    sh(f"shell uiautomator dump /sdcard/{name}")
    sh(f"pull /sdcard/{name} tools/{name}")
    try:
        return ET.parse(f"tools/{name}").getroot()
    except Exception as e:
        print(f"Failed to parse {name}: {e}")
        return None

def find_node(root, text_sub=None, rid_sub=None, cls_sub=None):
    if root is None: return None
    for n in root.iter('node'):
        t = n.attrib.get('text', '')
        r = n.attrib.get('resource-id', '')
        c = n.attrib.get('class', '')
        if text_sub and text_sub.lower() not in t.lower(): continue
        if rid_sub and rid_sub.lower() not in r.lower(): continue
        if cls_sub and cls_sub.lower() not in c.lower(): continue
        return n
    return None

def tap_bounds(bounds_str):
    import re
    m = re.findall(r'\[(\d+),(\d+)\]', bounds_str)
    if len(m) == 2:
        cx = (int(m[0][0]) + int(m[1][0])) // 2
        cy = (int(m[0][1]) + int(m[1][1])) // 2
        sh(f"shell input tap {cx} {cy}")
        print(f"Tapped {bounds_str} -> {cx},{cy}")
        return True
    return False

print("1. Launch Settings app so it's in recents")
sh("shell am start -n com.android.settings/.Settings")
time.sleep(1.5)

print("2. Launch FAIRY LIVE")
sh("shell am start -n com.fairylive.app/.MainActivity")
time.sleep(2)

print("3. Tap GO LIVE in Studio")
# Studio tab
sh("shell input tap 540 2260")
time.sleep(1)
# GO LIVE button
sh("shell input tap 708 2086")
time.sleep(2)

print("4. Inspect consent dialog")
root = dump_ui("consent.xml")
for n in root.iter('node'):
    t = n.attrib.get('text', '')
    r = n.attrib.get('resource-id', '')
    c = n.attrib.get('class', '')
    b = n.attrib.get('bounds', '')
    if t or 'spinner' in r or 'button' in r or 'button' in c.lower():
        print(f"  {c} | {r} | {t} | {b}")

spinner = find_node(root, rid_sub="spinner")
if spinner is not None:
    print("Opening spinner...")
    tap_bounds(spinner.attrib['bounds'])
    time.sleep(1)
    root_sp = dump_ui("spinner_open.xml")
    opt = find_node(root_sp, text_sub="single app")
    if opt is not None:
        print("Selecting 'A single app'...")
        tap_bounds(opt.attrib['bounds'])
        time.sleep(1)

root_confirm = dump_ui("confirm.xml")
btn_start = find_node(root_confirm, rid_sub="button1")
if btn_start is not None:
    print(f"Tapping Start button: {btn_start.attrib.get('bounds')}")
    tap_bounds(btn_start.attrib['bounds'])
    time.sleep(2)

print("5. Inspecting post-start screen (App Picker or Stream)")
root_after = dump_ui("post_start.xml")
for n in root_after.iter('node'):
    t = n.attrib.get('text', '')
    r = n.attrib.get('resource-id', '')
    c = n.attrib.get('class', '')
    b = n.attrib.get('bounds', '')
    if t or 'app' in r.lower():
        print(f"  {c} | {r} | {t} | {b}")

print("Done driving.")
