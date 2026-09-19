#!/usr/bin/env python3
"""
Phase 7: 60-Second Broadcast Qualification & Bitstream Verification
"""
import subprocess, time, os, json, re

DEVICE = "emulator-5554"
PORT = 19350
FLV_OUTPUT = "tools/phase7/phase7_rc_broadcast_60s.flv"
METRICS_OUTPUT = "tools/phase7/phase7_rc_broadcast_metrics.json"

def run(cmd):
    p = subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, errors='replace')
    out, err = p.communicate()
    return (out + err).strip()

def tap(x, y):
    run(f"adb -s {DEVICE} shell input tap {x} {y}")

def main():
    os.makedirs("tools/phase7", exist_ok=True)
    print("=== FAIRY LIVE: PHASE 7 BROADCAST QUALIFICATION ===")

    # 1. Reverse port
    print("1. Reversing TCP port 19350...")
    run(f"adb -s {DEVICE} reverse tcp:{PORT} tcp:{PORT}")

    # 2. Check if error card is showing and dismiss it
    xml = run(f"adb -s {DEVICE} shell uiautomator dump /sdcard/pre_stream.xml && adb -s {DEVICE} shell cat /sdcard/pre_stream.xml")
    if "card_creator_error" in xml:
        print("   Dismissing existing error card...")
        tap(848, 931)
        time.sleep(1)

    # 3. Launch RTMP Server in background
    print("2. Starting RTMP FLV recorder on port 19350 (65s limit)...")
    if os.path.exists(FLV_OUTPUT):
        os.remove(FLV_OUTPUT)
    recorder_proc = subprocess.Popen(
        ["python", "tools/m15c_rtmp_flv_recorder.py", str(PORT), FLV_OUTPUT, "65"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True
    )
    time.sleep(2)

    # 4. Tap Go Live / Retry Broadcast
    print("3. Triggering broadcast in app...")
    xml = run(f"adb -s {DEVICE} shell uiautomator dump /sdcard/stream_btn.xml && adb -s {DEVICE} shell cat /sdcard/stream_btn.xml")
    if "Retry Broadcast" in xml:
        tap(540, 2132)
    elif "Start Live" in xml:
        tap(540, 1431)
    else:
        # Tap default Start Live location
        tap(540, 1431)
    time.sleep(1.5)

    # 5. Check if MediaProjection consent dialog is open and tap Start
    xml = run(f"adb -s {DEVICE} shell uiautomator dump /sdcard/consent_check.xml && adb -s {DEVICE} shell cat /sdcard/consent_check.xml")
    if "screen_share_dialog_title" in xml or "button1" in xml:
        print("   Accepting screen recording consent dialog...")
        tap(893, 1703)

    # 6. Stream for 62 seconds and record performance telemetry
    print("4. Streaming and monitoring telemetry for 62 seconds...")
    snapshots = []
    start_t = time.time()
    for i in range(12):
        time.sleep(5)
        elapsed = time.time() - start_t
        # Sample memory
        mem_dump = run(f"adb -s {DEVICE} shell dumpsys meminfo com.fairylive.app")
        pss = 0
        for line in mem_dump.splitlines():
            if "TOTAL PSS:" in line:
                m = re.search(r"(\d+)", line)
                if m: pss = int(m.group(1)) / 1024.0
                break
            elif "TOTAL:" in line and "TOTAL PSS:" not in line:
                parts = line.split()
                if len(parts) >= 2 and parts[1].isdigit():
                    pss = int(parts[1]) / 1024.0
                    break
        # Sample codecs
        codec_dump = run(f"adb -s {DEVICE} shell dumpsys media.player")
        codecs = [l.strip() for l in codec_dump.splitlines() if "c2." in l and "encoder" in l]
        snapshots.append({"elapsed_s": round(elapsed, 1), "pss_mb": round(pss, 1), "codecs": len(codecs)})
        print(f"   [{round(elapsed, 1)}s] Memory: {round(pss, 1)} MB | Active Codecs: {len(codecs)}")

    # 7. Stop stream
    print("5. Stopping broadcast...")
    tap(540, 1431) # Stop button on dashboard
    time.sleep(3)

    # 8. Wait for recorder process
    recorder_proc.wait(timeout=15)
    rec_out, rec_err = recorder_proc.communicate()
    print("   Recorder stdout:", rec_out[-500:] if len(rec_out) > 500 else rec_out)

    # 9. Read recorder metrics
    base_metrics = FLV_OUTPUT + "_metrics.json"
    flv_metrics = {}
    if os.path.exists(base_metrics):
        with open(base_metrics) as f:
            flv_metrics = json.load(f)

    # 10. Compute comprehensive report
    peak_pss = max([s["pss_mb"] for s in snapshots]) if snapshots else 0
    max_codecs = max([s["codecs"] for s in snapshots]) if snapshots else 0
    flv_size = os.path.getsize(FLV_OUTPUT) if os.path.exists(FLV_OUTPUT) else 0

    report = {
        "qualification_target": "Milestone V1.2 Phase 7 Release Candidate",
        "duration_s": flv_metrics.get("duration_s", 0),
        "flv_file_bytes": flv_size,
        "video_tags": flv_metrics.get("video_tags", 0),
        "audio_tags": flv_metrics.get("audio_tags", 0),
        "keyframes": flv_metrics.get("keyframes", 0),
        "avg_fps": flv_metrics.get("avg_fps", 0),
        "pts_regressions": flv_metrics.get("pts_regressions", 0),
        "peak_pss_mb": peak_pss,
        "active_codec_count": max_codecs,
        "low_end_budget_passed": (peak_pss < 90.0 and max_codecs <= 2 and flv_metrics.get("pts_regressions", 0) == 0),
        "snapshots": snapshots
    }

    with open(METRICS_OUTPUT, "w") as f:
        json.dump(report, f, indent=2)

    print("\n=== PHASE 7 QUALIFICATION RESULTS ===")
    print(json.dumps(report, indent=2))

if __name__ == "__main__":
    main()