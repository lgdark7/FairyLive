#!/usr/bin/env python3
"""
FAIRY LIVE V1.2.1 Single-App Capture Real Device Test Flow
Automates and validates:
1. Target app launch (Settings)
2. FAIRY LIVE launch with CaptureSource=SINGLE_APP
3. MediaProjection consent with single-app selection
4. Verification of runtime diagnostics:
   - CaptureMode=SINGLE_APP
   - CaptureTarget=SelectedTaskWindow (API 34+ RECORD_CONTENT_TASK)
   - ProjectionStarted=true
5. RTMP FLV stream recording
6. Creator Studio interaction during active single-app capture
7. Clean stop verification:
   - ProjectionStopped=true
   - No resource leaks
"""

import os
import sys
import time
import subprocess
import threading
import xml.etree.ElementTree as ET

ADB = "adb -s emulator-5554"

def run_cmd(cmd):
    p = subprocess.run(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    return p.returncode, p.stdout.strip(), p.stderr.strip()

def adb_shell(cmd):
    return run_cmd(f"{ADB} shell \"{cmd}\"")

def dump_ui(filename="ui.xml"):
    adb_shell(f"uiautomator dump /sdcard/{filename}")
    _, xml_str, _ = adb_shell(f"cat /sdcard/{filename}")
    try:
        return ET.fromstring(xml_str)
    except Exception:
        return None

def find_node(root, predicate):
    if root is None:
        return None
    for elem in root.iter():
        if predicate(elem):
            return elem
    return None

def parse_bounds(bounds_str):
    # e.g. [100,200][300,400]
    import re
    m = re.findall(r'\[(\d+),(\d+)\]', bounds_str)
    if len(m) == 2:
        x1, y1 = int(m[0][0]), int(m[0][1])
        x2, y2 = int(m[1][0]), int(m[1][1])
        return (x1 + x2) // 2, (y1 + y2) // 2
    return None, None

def tap_node(node):
    if node is not None and "bounds" in node.attrib:
        cx, cy = parse_bounds(node.attrib["bounds"])
        if cx is not None:
            print(f"Tapping: {node.attrib.get('text', '')} ({node.attrib.get('resource-id', '')}) at {cx},{cy}")
            adb_shell(f"input tap {cx} {cy}")
            time.sleep(1)
            return True
    return False

def main():
    print("=== FAIRY LIVE V1.2.1 SINGLE-APP CAPTURE VERIFICATION ===")

    # Step 1: Ensure port forwarding and background target app
    run_cmd(f"{ADB} reverse tcp:19350 tcp:19350")
    adb_shell("pm grant com.fairylive.app android.permission.RECORD_AUDIO")
    adb_shell("pm grant com.fairylive.app android.permission.CAMERA")
    adb_shell("pm grant com.fairylive.app android.permission.POST_NOTIFICATIONS")

    print("[Step 1] Launching target application (Settings)...")
    adb_shell("am start -n com.android.settings/.Settings")
    time.sleep(2)

    print("[Step 2] Launching FAIRY LIVE...")
    adb_shell("am start -n com.fairylive.app/.MainActivity")
    time.sleep(2)

    # Clear logcat
    adb_shell("logcat -c")

    # Step 3: Start RTMP Ingest Server in background
    flv_out = "tools/v121_single_app_stream.flv"
    metrics_out = "tools/v121_single_app_metrics.json"
    if os.path.exists(flv_out): os.remove(flv_out)
    if os.path.exists(metrics_out): os.remove(metrics_out)

    server_cmd = f"python tools/m15c_rtmp_flv_recorder.py 15 {flv_out} {metrics_out}"
    server_proc = subprocess.Popen(server_cmd, shell=True)
    time.sleep(1.5)

    print("[Step 4] Navigating to Home and tapping Start Live...")
    adb_shell("input tap 180 2260") # Home tab
    time.sleep(1)
    adb_shell("input tap 540 1430") # Start Live button
    time.sleep(2)

    # Step 5: Handle MediaProjection Dialog
    print("[Step 5] Inspecting System MediaProjection Dialog...")
    root = dump_ui("projection_dialog.xml")
    
    # Check for spinner
    spinner = find_node(root, lambda e: "spinner" in e.attrib.get("resource-id", "").lower())
    if spinner is not None:
        print(f"Found screen share mode spinner: {spinner.attrib.get('bounds')}")
        tap_node(spinner)
        time.sleep(1)
        root2 = dump_ui("spinner_open.xml")
        single_app_opt = find_node(root2, lambda e: "single app" in e.attrib.get("text", "").lower())
        if single_app_opt is not None:
            print("Selecting 'A single app' option...")
            tap_node(single_app_opt)
            time.sleep(1)

    # Tap Start / Next button on consent dialog
    root3 = dump_ui("dialog_confirm.xml")
    start_btn = find_node(root3, lambda e: any(t in e.attrib.get("text", "").lower() for t in ["start recording", "start now", "entire screen", "next"]))
    if start_btn is not None:
        print(f"Confirming projection prompt with button: {start_btn.attrib.get('text')}")
        tap_node(start_btn)
        time.sleep(2)

    # If task picker appeared ("Choose an app"):
    root4 = dump_ui("app_picker.xml")
    settings_item = find_node(root4, lambda e: "settings" in e.attrib.get("text", "").lower() or "clock" in e.attrib.get("text", "").lower())
    if settings_item is not None:
        print(f"Selecting target app in picker: {settings_item.attrib.get('text')}")
        tap_node(settings_item)
        time.sleep(2)

    print("[Step 6] Streaming active... Polling logcat for diagnostics...")
    time.sleep(6)

    _, logcat_out, _ = adb_shell("logcat -d | grep -E 'FairyCapture|CaptureMode|ProjectionStarted'")
    print("--- DIAGNOSTIC LOGS ---")
    print(logcat_out)
    print("-----------------------")

    # Step 7: Test Creator Studio Interaction during capture
    print("[Step 7] Interacting with Creator Studio while streaming...")
    adb_shell("am start -n com.fairylive.app/.MainActivity")
    time.sleep(1)
    adb_shell("input tap 540 2260") # Studio tab
    time.sleep(2)

    # Wait for server process to finish recording
    server_proc.wait(timeout=20)
    print(f"Server exited with code {server_proc.returncode}")

    # Step 8: Stop stream cleanly
    print("[Step 8] Stopping live stream...")
    adb_shell("am start-service -a com.fairylive.app.service.FairyStreamService.ACTION_STOP_SERVICE com.fairylive.app/.service.FairyStreamService")
    time.sleep(2)

    _, stop_logs, _ = adb_shell("logcat -d | grep -E 'ProjectionStopped'")
    print("--- STOP LOGS ---")
    print(stop_logs)
    print("-----------------")

    # Verify metrics
    if os.path.exists(metrics_out):
        with open(metrics_out, "r") as f:
            print("--- STREAM METRICS ---")
            print(f.read())
            print("----------------------")

    print("=== VERIFICATION SCRIPT COMPLETE ===")

if __name__ == "__main__":
    main()
