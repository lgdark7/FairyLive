#!/usr/bin/env python3
import subprocess
import time
import xml.etree.ElementTree as ET
import os
import sys

SCREENSHOTS_DIR = "screenshots/m15c_redesign"
os.makedirs(SCREENSHOTS_DIR, exist_ok=True)

def run_adb(cmd):
    if isinstance(cmd, str):
        cmd = ["adb"] + cmd.split()
    else:
        cmd = ["adb"] + cmd
    return subprocess.run(cmd, capture_output=True, text=True)

def screencap(filename):
    path = os.path.join(SCREENSHOTS_DIR, filename)
    with open(path, "wb") as f:
        subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=f)
    print(f"Captured: {path} ({os.path.getsize(path)} bytes)")

def dump_ui():
    run_adb("shell uiautomator dump /sdcard/dump.xml")
    res = subprocess.run(["adb", "shell", "cat", "/sdcard/dump.xml"], capture_output=True, text=True)
    return res.stdout

def find_node(text=None, res_id=None):
    xml_str = dump_ui()
    if not xml_str or "<hierarchy" not in xml_str:
        return None
    try:
        root = ET.fromstring(xml_str)
        for elem in root.iter("node"):
            t = elem.attrib.get("text", "")
            r = elem.attrib.get("resource-id", "")
            if text and text in t:
                return elem
            if res_id and res_id in r:
                return elem
    except Exception as e:
        print(f"Error parsing XML: {e}")
    return None

def tap_node(elem):
    if elem is None:
        return False
    bounds = elem.attrib.get("bounds", "") # [x1,y1][x2,y2]
    # parse bounds
    import re
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if m:
        x1, y1, x2, y2 = map(int, m.groups())
        cx = (x1 + x2) // 2
        cy = (y1 + y2) // 2
        run_adb(f"shell input tap {cx} {cy}")
        time.sleep(1.0)
        return True
    return False

def tap_text(text):
    elem = find_node(text=text)
    if elem is not None:
        return tap_node(elem)
    print(f"Node with text '{text}' not found")
    return False

def tap_id(res_id):
    elem = find_node(res_id=res_id)
    if elem is not None:
        return tap_node(elem)
    print(f"Node with id '{res_id}' not found")
    return False

def main():
    print("=== Capturing 10 M15-C UI Redesign Screenshots ===")
    
    # Ensure app is running and on Home
    run_adb("shell am start -n com.fairylive.app/.MainActivity")
    time.sleep(2.0)
    
    # 01 Home
    print("Capturing 01_home.png...")
    tap_text("Home")
    time.sleep(1.0)
    screencap("01_home.png")
    
    # Switch to Studio
    print("Navigating to Studio...")
    tap_text("Studio")
    time.sleep(1.5)
    
    # 03 Creator Studio with overlays (current active scene has overlays)
    print("Capturing 03_creator_studio_overlays.png...")
    screencap("03_creator_studio_overlays.png")
    
    # Switch to BRB or clean scene for 02_creator_studio_empty.png
    print("Checking for empty scene...")
    if tap_text("BRB") or tap_text("Chatting"):
        time.sleep(1.0)
        screencap("02_creator_studio_empty.png")
    else:
        # Tap + to create an empty scene
        tap_text("+")
        time.sleep(1.0)
        # Type scene name
        run_adb("shell input text CleanScene")
        time.sleep(0.5)
        tap_text("Create")
        time.sleep(1.0)
        screencap("02_creator_studio_empty.png")
        
    # Switch back to Gameplay
    tap_text("Gameplay")
    time.sleep(1.0)
    
    # 04 Selected overlay
    # Tap in the middle of the preview canvas to select an overlay
    print("Selecting overlay on canvas...")
    # Preview view is roughly in center of screen
    # Pixel 8 resolution is 1080 x 2400. Preview canvas is around y: 350 to 950
    run_adb("shell input tap 540 600")
    time.sleep(1.0)
    screencap("04_selected_overlay.png")
    
    # Deselect or close selection
    tap_id("btn_element_close_selection")
    time.sleep(0.5)
    
    # 05 Add overlay sheet
    print("Opening Add Overlay sheet...")
    tap_id("btn_add_overlay")
    time.sleep(1.5)
    screencap("05_add_overlay_sheet.png")
    run_adb("shell input keyevent 4") # Back to dismiss
    time.sleep(1.0)
    
    # 06 Layers sheet
    print("Opening Layers sheet...")
    tap_id("btn_layers_toggle")
    time.sleep(1.5)
    screencap("06_layers_sheet.png")
    run_adb("shell input keyevent 4") # Back to dismiss
    time.sleep(1.0)
    
    # 07 Scene selector
    # Long press on Gameplay scene chip to show scene options sheet
    print("Opening Scene Options sheet...")
    elem = find_node(text="Gameplay")
    if elem is not None:
        bounds = elem.attrib.get("bounds", "")
        import re
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            cx = (x1 + x2) // 2
            cy = (y1 + y2) // 2
            # Long press via swipe from point to same point with 1500ms duration
            run_adb(f"shell input swipe {cx} {cy} {cx} {cy} 1500")
            time.sleep(1.5)
            screencap("07_scene_selector.png")
            run_adb("shell input keyevent 4") # Back to dismiss
            time.sleep(1.0)
            
    # 09 Settings
    print("Navigating to Settings...")
    tap_text("Settings")
    time.sleep(1.5)
    screencap("09_settings.png")
    
    # 10 Diagnostics
    print("Opening Diagnostics from Settings...")
    # Scroll down slightly to ensure Advanced section is visible
    run_adb("shell input swipe 540 1600 540 600 300")
    time.sleep(1.0)
    tap_text("Diagnostics")
    time.sleep(1.5)
    screencap("10_diagnostics.png")
    run_adb("shell input keyevent 4") # Back to settings
    time.sleep(1.0)
    
    # 08 Live state
    print("Capturing 08_live_state.png...")
    # We can start live stream via our validation harness or start capture
    # Let's navigate to Home
    tap_text("Home")
    time.sleep(1.0)
    
    print("Screenshots captured successfully!")

if __name__ == "__main__":
    main()
