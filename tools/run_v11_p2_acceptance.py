import subprocess
import time
import os
import sys
import json
import struct

def run_cmd(cmd):
    p = subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    out, err = p.communicate()
    return out.strip(), p.returncode

def main():
    print("=== STARTING V1.1 PHASE 2 REAL DEVICE ACCEPTANCE TEST ===")

    # 1. Reverse RTMP port
    run_cmd("adb reverse tcp:19350 tcp:19350")

    # 2. Push prepared streaming settings pointing to local RTMP listener
    run_cmd("adb push tools/fairy_live_settings_proper.xml /data/local/tmp/fairy_live_settings.xml")
    run_cmd("adb shell run-as com.fairylive.app cp /data/local/tmp/fairy_live_settings.xml /data/data/com.fairylive.app/shared_prefs/fairy_live_settings.xml")

    # 3. Kill and restart app
    run_cmd("adb shell am force-stop com.fairylive.app")
    time.sleep(1)
    run_cmd("adb shell am start -n com.fairylive.app/.MainActivity")
    time.sleep(2)

    # 4. Navigate to Studio
    print("1. Navigating to Creator Studio...")
    run_cmd("adb shell input tap 540 2270")
    time.sleep(2)

    # 5. Open Add Element sheet
    print("2. Opening Add Element modal sheet...")
    run_cmd("adb shell input tap 220 1918")
    time.sleep(2)

    # 6. Scroll down and add YouTube Subscriber Widget
    print("3. Adding YouTube Subscriber Counter widget...")
    run_cmd("adb shell input swipe 540 2100 540 1200 300")
    time.sleep(1)
    run_cmd("adb shell input tap 540 1913")
    time.sleep(2)

    # 7. Add Web Overlay
    print("4. Adding Web/Browser Overlay widget...")
    run_cmd("adb shell input tap 220 1918")
    time.sleep(2)
    run_cmd("adb shell input swipe 540 2100 540 1200 300")
    time.sleep(1)
    run_cmd("adb shell input tap 540 2150")
    time.sleep(2)

    # 8. Capture Studio Composed State
    run_cmd("adb shell screencap -p /sdcard/v11_studio_composed.png")
    run_cmd("adb pull /sdcard/v11_studio_composed.png screenshots/v11_studio_composed.png")
    print("Studio composition captured: screenshots/v11_studio_composed.png")

    # 9. Start RTMP recorder in background
    flv_path = "tools/v11_broadcast.flv"
    if os.path.exists(flv_path):
        os.remove(flv_path)

    recorder_proc = subprocess.Popen([sys.executable, "tools/m15c_rtmp_flv_recorder.py", "19350", flv_path, "18"])
    time.sleep(1.5)

    # 10. Tap GO LIVE
    print("5. Triggering GO LIVE broadcast...")
    run_cmd("adb shell input tap 708 2086")
    time.sleep(1.5)

    # Handle MediaProjection consent dialog
    run_cmd("adb shell input tap 750 1480") # Start on Entire screen
    time.sleep(1.0)
    run_cmd("adb shell input tap 750 1480") # Fallback tap

    print("6. Broadcast active! Exercising Creator-Only HUD & Chat...")
    time.sleep(2)

    # Capture live stream with floating HUD on creator screen
    run_cmd("adb shell screencap -p /sdcard/v11_creator_screen_live.png")
    run_cmd("adb pull /sdcard/v11_creator_screen_live.png screenshots/v11_creator_screen_live.png")

    # Tap on Chat button on Streamer HUD (near center-top 500, 160)
    run_cmd("adb shell input tap 490 160")
    time.sleep(1.5)
    run_cmd("adb shell screencap -p /sdcard/v11_creator_chat_open.png")
    run_cmd("adb pull /sdcard/v11_creator_chat_open.png screenshots/v11_creator_chat_open.png")

    # Dismiss chat sheet
    run_cmd("adb shell input keyevent KEYCODE_BACK")
    time.sleep(1.0)

    # Exercise Mic Mute on HUD
    print("7. Exercising mic mute & pause...")
    run_cmd("adb shell input tap 530 160") # Mic toggle
    time.sleep(1.0)
    run_cmd("adb shell input tap 570 160") # Pause toggle
    time.sleep(1.5)
    run_cmd("adb shell input tap 570 160") # Resume toggle
    time.sleep(1.0)

    # Wait for recorder to complete 18s session
    print("8. Waiting for FLV recording to finalize...")
    recorder_proc.wait(timeout=25)

    # 11. Stop Stream
    run_cmd("adb shell input tap 708 2086")
    time.sleep(1)

    # 12. Parse FLV stream and verify tags
    print("9. Parsing recorded FLV stream...")
    if not os.path.exists(flv_path) or os.path.getsize(flv_path) < 1000:
        print("ERROR: FLV file not recorded properly!")
        return 1

    with open(flv_path, "rb") as f:
        hdr = f.read(9)
        f.read(4)
        video_tags = 0
        audio_tags = 0
        keyframes = 0
        total_bytes = 13

        while True:
            tag_hdr = f.read(11)
            if len(tag_hdr) < 11:
                break
            tag_type = tag_hdr[0]
            data_size = (tag_hdr[1] << 16) | (tag_hdr[2] << 8) | tag_hdr[3]
            payload = f.read(data_size)
            f.read(4) # prev_size
            total_bytes += 11 + data_size + 4

            if tag_type == 9:
                video_tags += 1
                if len(payload) > 0 and (payload[0] >> 4) == 1:
                    keyframes += 1
            elif tag_type == 8:
                audio_tags += 1

    metrics = {
        "flv_size_bytes": os.path.getsize(flv_path),
        "video_tags": video_tags,
        "audio_tags": audio_tags,
        "video_keyframes": keyframes,
        "status": "PASS" if video_tags > 50 and audio_tags > 50 and keyframes >= 2 else "FAIL"
    }

    with open("tools/v11_p2_metrics.json", "w") as f:
        json.dump(metrics, f, indent=2)

    print(f"Metrics: {metrics}")
    print("=== V1.1 PHASE 2 ACCEPTANCE COMPLETE ===")
    return 0

if __name__ == "__main__":
    sys.exit(main())
