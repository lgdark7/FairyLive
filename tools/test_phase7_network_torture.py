#!/usr/bin/env python3
"""
Phase 7 Step 4B: Network Disconnect & Torture Test Runner
Simulates mid-stream network interruption:
1. Switch to Home tab
2. Start streaming to RTMP server
3. Abruptly kill the RTMP server process / sever the TCP connection mid-stream
4. Monitor logcat for StreamPipeline reconnect attempts (#1, #2, #3, #4)
5. Verify CreatorErrorNotification display on screen
6. Verify zero crashes and zero ANRs
"""
import subprocess, time, os, json, re

DEVICE = "emulator-5554"
PORT = 19350
OUTPUT_JSON = "tools/phase7/torture_test_results.json"

def run(cmd):
    p = subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, errors='replace')
    out, err = p.communicate()
    return (out + err).strip()

def tap(x, y):
    run(f"adb -s {DEVICE} shell input tap {x} {y}")

def main():
    os.makedirs("tools/phase7", exist_ok=True)
    print("=== FAIRY LIVE: PHASE 7 NETWORK TORTURE TEST ===")

    # 1. Switch to Home Tab
    print("1. Ensuring Home Dashboard is active...")
    tap(180, 2263)
    time.sleep(1)

    # 2. Reverse port
    run(f"adb -s {DEVICE} reverse tcp:{PORT} tcp:{PORT}")

    # 3. Check if error card exists and dismiss
    xml = run(f"adb -s {DEVICE} shell uiautomator dump /sdcard/torture_pre.xml && adb -s {DEVICE} shell cat /sdcard/torture_pre.xml")
    if "card_creator_error" in xml:
        print("   Dismissing pre-existing error card...")
        tap(848, 931)
        time.sleep(1)

    # 4. Clear logcat
    run(f"adb -s {DEVICE} logcat -c")

    # 5. Start RTMP recorder for 25s
    temp_flv = "tools/phase7/temp_torture.flv"
    if os.path.exists(temp_flv):
        try: os.remove(temp_flv)
        except: pass

    print("2. Starting RTMP recorder for 25 seconds...")
    recorder = subprocess.Popen(
        ["python", "tools/m15c_rtmp_flv_recorder.py", "25", temp_flv, "tools/phase7/temp_metrics.json"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True
    )
    time.sleep(2)

    # 6. Tap Go Live / Retry Broadcast
    print("3. Starting broadcast in app...")
    xml = run(f"adb -s {DEVICE} shell uiautomator dump /sdcard/torture_dash.xml && adb -s {DEVICE} shell cat /sdcard/torture_dash.xml")
    if "Retry Broadcast" in xml:
        print("   Tapping Retry Broadcast (540, 1431)...")
        tap(540, 1431)
    elif "Start Live" in xml:
        print("   Tapping Start Live (540, 1431)...")
        tap(540, 1431)
    else:
        tap(540, 1431)
    time.sleep(1.5)

    # Accept consent if prompted
    xml = run(f"adb -s {DEVICE} shell uiautomator dump /sdcard/torture_consent.xml && adb -s {DEVICE} shell cat /sdcard/torture_consent.xml")
    if "screen_share_dialog_title" in xml or "button1" in xml:
        print("   Accepting screen capture consent (893, 1703)...")
        tap(893, 1703)

    # 7. Let it stream healthy for 5 seconds
    print("4. Streaming healthy for 6 seconds...")
    time.sleep(6)

    # 8. SEVER NETWORK CONNECTION MID-STREAM by killing the RTMP server!
    print("5. SEVERING NETWORK CONNECTION MID-STREAM (killing RTMP server)...")
    recorder.kill()
    time.sleep(1)

    # 9. Monitor logcat for auto-reconnect backoff sequence
    print("6. Monitoring StreamPipeline reconnect backoff sequence in logcat (18 seconds)...")
    reconnect_events = []
    start_wait = time.time()
    while time.time() - start_wait < 18:
        time.sleep(2)
        log = run(f"adb -s {DEVICE} logcat -d")
        for line in log.splitlines():
            if "Attempting reconnect #" in line and line not in reconnect_events:
                reconnect_events.append(line)
                print(f"   Detected: {line.strip()}")

    # 10. Verify Creator Error Notification banner appears on screen
    print("7. Inspecting UI for Creator Error Notification card...")
    time.sleep(2)
    xml_after = run(f"adb -s {DEVICE} shell uiautomator dump /sdcard/torture_after.xml && adb -s {DEVICE} shell cat /sdcard/torture_after.xml")
    has_error_card = "card_creator_error" in xml_after
    has_retry_btn = "Retry Broadcast" in xml_after

    # 11. Audit for crashes or ANR
    raw_log = run(f"adb -s {DEVICE} logcat -d")
    fatal_lines = [l for l in raw_log.splitlines() if ("FATAL" in l or "AndroidRuntime" in l or "ANR in" in l) and "com.fairylive.app" in l]

    results = {
        "test": "Phase 7 Mid-Stream Network Interruption & Torture Test",
        "sever_method": "SIGKILL on ingest server during active H.264/AAC mux",
        "reconnect_events_captured": len(reconnect_events),
        "reconnect_log_samples": [l.strip() for l in reconnect_events[:4]],
        "creator_error_banner_displayed": has_error_card,
        "retry_button_ready": has_retry_btn,
        "zero_crashes": len(fatal_lines) == 0,
        "zero_anrs": not any("ANR in com.fairylive.app" in l for l in raw_log.splitlines()),
        "pass": (len(reconnect_events) > 0 and has_error_card and len(fatal_lines) == 0)
    }

    with open(OUTPUT_JSON, "w") as f:
        json.dump(results, f, indent=2)

    print("\n=== TORTURE TEST RESULTS ===")
    print(json.dumps(results, indent=2))

    # Clean up temp FLV if created
    if os.path.exists(temp_flv):
        try: os.remove(temp_flv)
        except: pass

if __name__ == "__main__":
    main()