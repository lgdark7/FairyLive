#!/usr/bin/env python3
"""
Direct capture test: Start RTMP recorder, then tap Start (893, 1703) on consent dialog!
"""
import subprocess, time, os, json, re

DEVICE = "emulator-5554"
PORT = 19350
FLV_PATH = "tools/phase7/phase7_rc_broadcast_60s.flv"
METRICS_PATH = "tools/phase7/phase7_rc_broadcast_metrics.json"

def run(cmd):
    p = subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, errors='replace')
    out, err = p.communicate()
    return (out + err).strip()

def main():
    os.makedirs("tools/phase7", exist_ok=True)
    if os.path.exists(FLV_PATH):
        try: os.remove(FLV_PATH)
        except: pass

    print("1. Reverse port 19350...")
    run(f"adb -s {DEVICE} reverse tcp:{PORT} tcp:{PORT}")

    print("2. Starting RTMP recorder for 65s...")
    recorder = subprocess.Popen(
        ["python", "tools/m15c_rtmp_flv_recorder.py", str(PORT), FLV_PATH, "65"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True
    )
    time.sleep(1.5)

    print("3. Tapping Start on consent dialog (893, 1703)...")
    run(f"adb -s {DEVICE} shell input tap 893 1703")

    print("4. Streaming for 62 seconds...")
    snapshots = []
    start_t = time.time()
    for i in range(12):
        time.sleep(5)
        elapsed = round(time.time() - start_t, 1)
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
        codec_dump = run(f"adb -s {DEVICE} shell dumpsys media.player")
        codecs = [l.strip() for l in codec_dump.splitlines() if "c2." in l and "encoder" in l]
        snapshots.append({"elapsed_s": elapsed, "pss_mb": round(pss, 1), "codecs": len(codecs)})
        print(f"   [{elapsed}s] PSS: {round(pss, 1)} MB | Codecs: {len(codecs)}")

    print("5. Stopping stream...")
    run(f"adb -s {DEVICE} shell input tap 540 1431") # Stop broadcast button
    time.sleep(2)

    print("6. Waiting for recorder...")
    try:
        rec_out, _ = recorder.communicate(timeout=10)
        print("   Recorder stdout:", rec_out[-500:] if len(rec_out) > 500 else rec_out)
    except Exception as e:
        recorder.kill()

    if os.path.exists(FLV_PATH):
        sz = os.path.getsize(FLV_PATH)
        print(f"SUCCESS: FLV recorded! {sz} bytes ({round(sz/(1024*1024), 2)} MB)")
    else:
        print("ERROR: FLV not recorded")

    base_metrics = FLV_PATH + "_metrics.json"
    if os.path.exists(base_metrics):
        with open(base_metrics) as f:
            m = json.load(f)
        peak_pss = max([s["pss_mb"] for s in snapshots]) if snapshots else 0
        max_codecs = max([s["codecs"] for s in snapshots]) if snapshots else 0
        m["peak_pss_mb"] = peak_pss
        m["active_codec_count"] = max_codecs
        m["low_end_budget_passed"] = (peak_pss < 90.0 and max_codecs <= 2 and m.get("pts_regressions", 0) == 0)
        m["snapshots"] = snapshots
        with open(METRICS_PATH, "w") as f:
            json.dump(m, f, indent=2)
        print("\n=== PHASE 7 METRICS ===")
        print(json.dumps(m, indent=2))

if __name__ == "__main__":
    main()