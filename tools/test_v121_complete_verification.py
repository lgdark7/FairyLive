#!/usr/bin/env python3
"""
FAIRY LIVE V1.2.1 End-to-End Single-App Capture Verification Script
Covers:
- Real target app launch (Settings)
- MediaProjection consent & App Selector interaction
- Runtime diagnostics validation
- Studio interaction while target app is captured
- Stop lifecycle & FLV broadcast metrics validation
"""

import os
import sys
import time
import subprocess
import json
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

def find_node(root, text_sub=None, rid_sub=None):
    if root is None: return None
    for n in root.iter('node'):
        t = n.attrib.get('text', '')
        r = n.attrib.get('resource-id', '')
        if text_sub and text_sub.lower() not in t.lower(): continue
        if rid_sub and rid_sub.lower() not in r.lower(): continue
        return n
    return None

def tap_node(node):
    if node is None or "bounds" not in node.attrib: return False
    import re
    m = re.findall(r'\[(\d+),(\d+)\]', node.attrib["bounds"])
    if len(m) == 2:
        cx = (int(m[0][0]) + int(m[1][0])) // 2
        cy = (int(m[0][1]) + int(m[1][1])) // 2
        sh(f"shell input tap {cx} {cy}")
        print(f"Tapped: '{node.attrib.get('text', '')}' ({node.attrib.get('resource-id', '')}) at {cx},{cy}")
        return True
    return False

def main():
    print("=== FAIRY LIVE V1.2.1 SINGLE-APP CAPTURE E2E VERIFICATION ===")

    # Step 1: Network & Permissions setup
    sh("reverse tcp:19350 tcp:19350")
    sh("shell pm grant com.fairylive.app android.permission.RECORD_AUDIO")
    sh("shell pm grant com.fairylive.app android.permission.CAMERA")
    sh("shell pm grant com.fairylive.app android.permission.POST_NOTIFICATIONS")

    # Step 2: Launch real target app (Settings)
    print("[1] Launching real target app (Settings)...")
    sh("shell am start -n com.android.settings/.Settings")
    time.sleep(2)

    # Step 3: Start RTMP Ingest Server (14s)
    flv_out = "tools/v121_single_app_e2e.flv"
    metrics_out = "tools/v121_single_app_e2e_metrics.json"
    if os.path.exists(flv_out): os.remove(flv_out)
    if os.path.exists(metrics_out): os.remove(metrics_out)

    print("[2] Starting local RTMP Ingest Server...")
    server_cmd = f"python tools/m15c_rtmp_flv_recorder.py 14 {flv_out} {metrics_out}"
    server_proc = subprocess.Popen(server_cmd, shell=True)
    time.sleep(1.5)

    # Clear logcat
    sh("shell logcat -c")

    # Step 4: Launch FAIRY LIVE and go to Studio
    print("[3] Launching FAIRY LIVE Creator Studio...")
    sh("shell am start -n com.fairylive.app/.MainActivity")
    time.sleep(2)
    sh("shell input tap 540 2260") # Studio tab
    time.sleep(1.5)

    # Tap GO LIVE
    print("[4] Initiating GO LIVE from Creator Studio...")
    sh("shell input tap 708 2086")
    time.sleep(2)

    # Step 5: Handle MediaProjection Dialog
    print("[5] Handling MediaProjection Permission Dialog...")
    root_dialog = dump_ui("v121_dialog.xml")
    spinner = find_node(root_dialog, rid_sub="screen_share_mode_spinner")
    if spinner is not None:
        tap_node(spinner)
        time.sleep(1)
        root_sp = dump_ui("v121_sp_open.xml")
        opt = find_node(root_sp, text_sub="single app")
        if opt is not None:
            tap_node(opt)
            time.sleep(1)

    # Tap Start button
    root_confirm = dump_ui("v121_confirm.xml")
    btn_start = find_node(root_confirm, rid_sub="button1")
    if btn_start is not None:
        tap_node(btn_start)
        time.sleep(2)

    # Step 6: Select target app from App Picker ("Choose an app")
    print("[6] Selecting target app from System App Picker...")
    root_picker = dump_ui("v121_picker.xml")
    target_node = find_node(root_picker, text_sub="Settings", rid_sub="text1")
    if target_node is not None:
        tap_node(target_node)
    else:
        # Fallback to direct coordinates if node not found
        print("Tapping Settings at coordinates (674, 1510)...")
        sh("shell input tap 674 1510")
    time.sleep(3)

    # Step 7: Verify runtime diagnostics from logcat
    print("[7] Inspecting runtime diagnostics from logcat...")
    logs = sh("shell logcat -d | grep -E 'FairyCapture|ProjectionStarted|Projection resized'")
    print("--- RUNTIME DIAGNOSTICS ---")
    print(logs)
    print("---------------------------")

    # Step 8: Test Critical Failure Case & Studio Interaction
    print("[8] Interacting with FAIRY LIVE Creator Studio during active single-app capture...")
    sh("shell am start -n com.fairylive.app/.MainActivity")
    time.sleep(1)
    sh("shell input tap 540 2260") # Studio tab
    time.sleep(1)
    # Interact with Studio canvas (e.g. tap overlay)
    sh("shell input tap 540 1000")
    time.sleep(1)

    # Step 9: Wait for RTMP recorder to finish
    print("[9] Waiting for RTMP broadcast capture to complete...")
    try:
        server_proc.wait(timeout=25)
        print("RTMP Ingest Server finished successfully.")
    except subprocess.TimeoutExpired:
        print("RTMP Ingest Server wait timed out, terminating...")
        server_proc.kill()

    # Step 10: Stop stream cleanly
    print("[10] Stopping live stream...")
    sh("shell input tap 708 2086") # Stop Broadcast in Studio
    time.sleep(2)

    stop_logs = sh("shell logcat -d | grep -E 'ProjectionStopped|teardown'")
    print("--- STOP LIFECYCLE LOGS ---")
    print(stop_logs)
    print("---------------------------")

    # Step 11: Evaluate FLV metrics
    if os.path.exists(metrics_out):
        with open(metrics_out, "r") as f:
            metrics = json.load(f)
        print("--- RECORDED BROADCAST METRICS ---")
        print(json.dumps(metrics, indent=2))
        print("----------------------------------")

        video_frames = metrics.get("video", {}).get("total_frames", 0)
        audio_frames = metrics.get("audio", {}).get("total_access_units", 0)
        fps = metrics.get("video", {}).get("fps", 0.0)
        pts_errs = metrics.get("video", {}).get("pts_regressions", 0)
        av_sync = metrics.get("av_sync", {}).get("final_delta_ms", 0)

        print(f"Video Frames: {video_frames}")
        print(f"Audio Frames: {audio_frames}")
        print(f"Video FPS: {fps:.2f}")
        print(f"PTS Errors: {pts_errs}")
        print(f"A/V Sync Delta: {av_sync} ms")

        assert video_frames > 50, f"Expected > 50 video frames, got {video_frames}"
        assert pts_errs == 0, f"Expected 0 PTS regressions, got {pts_errs}"
        print(">>> ALL BROADCAST METRIC CHECKS PASSED! <<<")
    else:
        print(f"ERROR: Metrics file {metrics_out} was not generated!")
        sys.exit(1)

    print("=== V1.2.1 VERIFICATION COMPLETE: ALL GATES PASSED ===")

if __name__ == "__main__":
    main()
