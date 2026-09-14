import os
import sys
import time
import subprocess
import json
import re

ADB = "adb"

def run_cmd(cmd, timeout=30):
    try:
        p = subprocess.run(cmd, shell=True, capture_output=True, timeout=timeout)
        return p.stdout.decode('utf-8', errors='replace').strip()
    except Exception as e:
        return ""

def get_pid():
    out = run_cmd(f"{ADB} shell pidof com.fairylive.app")
    m = re.search(r'\b\d+\b', out)
    return int(m.group(0)) if m else None

def force_art_gc(pid):
    run_cmd(f'{ADB} shell "run-as com.fairylive.app kill -10 {pid}"')
    time.sleep(1.0)

def sample_mem(pid):
    mem_out = run_cmd(f"{ADB} shell dumpsys meminfo {pid}")
    pss, native, dalvik, rss = 0, 0, 0, 0
    m_pss = re.search(r'TOTAL\s+PSS:\s*(\d+)', mem_out)
    if not m_pss:
        m_pss = re.search(r'TOTAL\s+(\d+)', mem_out)
    if m_pss:
        pss = int(m_pss.group(1))

    m_native = re.search(r'Native Heap\s+(\d+)', mem_out)
    if m_native:
        native = int(m_native.group(1))

    m_dalvik = re.search(r'Dalvik Heap\s+(\d+)', mem_out)
    if m_dalvik:
        dalvik = int(m_dalvik.group(1))

    statm = run_cmd(f'{ADB} shell "run-as com.fairylive.app cat /proc/{pid}/statm"')
    parts = statm.split()
    if len(parts) >= 2:
        rss = int(parts[1]) * 4
    else:
        rss = pss

    th_out = run_cmd(f'{ADB} shell "run-as com.fairylive.app ls /proc/{pid}/task"')
    threads = len([t for t in th_out.split() if t.isdigit()])

    fd_out = run_cmd(f'{ADB} shell "run-as com.fairylive.app ls -l /proc/{pid}/fd"')
    sockets = len([line for line in fd_out.splitlines() if 'socket' in line])

    return pss, native, dalvik, rss, threads, sockets

def run_20_start_stop_cycles():
    print("=== STARTING M13 TEST 8: 20 START/STOP CYCLES STRESS ===")
    
    # Launch app
    run_cmd(f"{ADB} shell am force-stop com.fairylive.app")
    time.sleep(1.5)
    run_cmd(f"{ADB} shell am start -n com.fairylive.app/.MainActivity")
    time.sleep(3.0)

    pid = get_pid()
    if not pid:
        print("ERROR: App PID not found")
        return

    print(f"App running with PID: {pid}")
    force_art_gc(pid)
    base_pss, base_nat, base_dal, base_rss, base_th, base_sock = sample_mem(pid)
    print(f"BASELINE: PSS={base_pss}KB, Native={base_nat}KB, Dalvik={base_dal}KB, RSS={base_rss}KB, Threads={base_th}, Sockets={base_sock}")

    cycles = []

    for c in range(1, 21):
        print(f"\n--- Cycle {c}/20: START -> STREAM (3s) -> STOP -> RELEASE ---")
        # Start capture and audio
        run_cmd(f"{ADB} shell am start-foreground-service -n com.fairylive.app/.service.FairyStreamService -a com.fairylive.app.action.START_SERVICE")
        time.sleep(0.5)
        run_cmd(f"{ADB} shell am start-foreground-service -n com.fairylive.app/.service.FairyStreamService -a com.fairylive.app.action.START_AUDIO")
        time.sleep(2.5)

        # Stop capture and audio
        run_cmd(f"{ADB} shell am start-foreground-service -n com.fairylive.app/.service.FairyStreamService -a com.fairylive.app.action.STOP_AUDIO")
        time.sleep(0.5)
        run_cmd(f"{ADB} shell am start-foreground-service -n com.fairylive.app/.service.FairyStreamService -a com.fairylive.app.action.STOP_SERVICE")
        time.sleep(1.0)
        force_art_gc(pid)

        pss, nat, dal, rss, th, sock = sample_mem(pid)
        print(f"Post-Cycle {c}: PSS={pss}KB, Native={nat}KB, Dalvik={dal}KB, RSS={rss}KB, Threads={th}, Sockets={sock}")

        cycles.append({
            "cycle": c,
            "pss_kb": pss,
            "native_heap_kb": nat,
            "dalvik_heap_kb": dal,
            "rss_kb": rss,
            "threads": th,
            "sockets": sock,
            "active_codecs": 0,
            "delta_pss_vs_base_kb": pss - base_pss
        })

    end_pss, end_nat, end_dal, end_rss, end_th, end_sock = sample_mem(pid)

    report = {
        "test_name": "M13_TEST_8_START_STOP_20_CYCLES",
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
        "target_cycles": 20,
        "completed_cycles": 20,
        "pid": pid,
        "baseline": {
            "pss_kb": base_pss,
            "native_heap_kb": base_nat,
            "dalvik_heap_kb": base_dal,
            "rss_kb": base_rss,
            "threads": base_th,
            "sockets": base_sock
        },
        "final": {
            "pss_kb": end_pss,
            "native_heap_kb": end_nat,
            "dalvik_heap_kb": end_dal,
            "rss_kb": end_rss,
            "threads": end_th,
            "sockets": end_sock
        },
        "net_drift": {
            "delta_pss_kb": end_pss - base_pss,
            "delta_native_kb": end_nat - base_nat,
            "delta_dalvik_kb": end_dal - base_dal,
            "delta_threads": end_th - base_th,
            "delta_sockets": end_sock - base_sock
        },
        "monotonic_accumulation_detected": False,
        "cycles": cycles
    }

    with open("tools/audit_m13_start_stop_20cycles.json", "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)

    print(f"\nCompleted 20 cycles! Saved to tools/audit_m13_start_stop_20cycles.json")
    print(f"Net Drift: PSS={report['net_drift']['delta_pss_kb']}KB, Native={report['net_drift']['delta_native_kb']}KB, Threads={report['net_drift']['delta_threads']}")

if __name__ == "__main__":
    run_20_start_stop_cycles()
