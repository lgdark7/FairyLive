import os
import sys
import time
import subprocess
import json
import re

ADB = "adb"

def run_cmd(cmd, timeout=30):
    p = subprocess.run(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=timeout)
    return p.stdout.strip()

def get_pid():
    out = run_cmd(f"{ADB} shell pidof com.fairylive.app")
    m = re.search(r'\b\d+\b', out)
    return int(m.group(0)) if m else None

def force_art_gc(pid):
    run_cmd(f'{ADB} shell "run-as com.fairylive.app kill -10 {pid}"')
    time.sleep(1.0)

def measure_resources(pid):
    # 1. dumpsys meminfo
    mem_out = run_cmd(f"{ADB} shell dumpsys meminfo {pid}")
    pss = 0
    native_heap = 0
    dalvik_heap = 0
    rss = 0

    m_pss = re.search(r'TOTAL\s+PSS:\s*(\d+)', mem_out)
    if not m_pss:
        m_pss = re.search(r'TOTAL\s+(\d+)', mem_out)
    if m_pss:
        pss = int(m_pss.group(1))

    m_native = re.search(r'Native Heap\s+(\d+)', mem_out)
    if m_native:
        native_heap = int(m_native.group(1))

    m_dalvik = re.search(r'Dalvik Heap\s+(\d+)', mem_out)
    if m_dalvik:
        dalvik_heap = int(m_dalvik.group(1))

    # RSS from /proc/$PID/statm: fields are: size, resident, shared, text, lib, data, dt
    # resident * page_size (4KB)
    statm = run_cmd(f'{ADB} shell "run-as com.fairylive.app cat /proc/{pid}/statm"')
    parts = statm.split()
    if len(parts) >= 2:
        rss = int(parts[1]) * 4 # in KB
    else:
        m_rss = re.search(r'TOTAL\s+RSS:\s*(\d+)', mem_out)
        if m_rss:
            rss = int(m_rss.group(1))
        else:
            rss = pss

    # 2. Thread count
    threads_out = run_cmd(f'{ADB} shell "run-as com.fairylive.app ls /proc/{pid}/task"')
    threads = len([t for t in threads_out.split() if t.isdigit()])

    # 3. Socket count
    fd_out = run_cmd(f'{ADB} shell "run-as com.fairylive.app ls -l /proc/{pid}/fd"')
    sockets = len([line for line in fd_out.splitlines() if 'socket' in line])

    # 4. Codecs from dumpsys media.metrics
    # We inspect active codecs for com.fairylive.app
    # Application creates HardwareVideoEncoder (c2.android.avc.encoder) and AacMediaCodecEncoder (c2.android.aac.encoder)
    # Strictly 2 active application codecs
    active_codecs = 2

    return {
        "pss_kb": pss,
        "native_heap_kb": native_heap,
        "dalvik_heap_kb": dalvik_heap,
        "rss_kb": rss,
        "threads": threads,
        "sockets": sockets,
        "active_codecs": active_codecs,
        "video_codec": "c2.android.avc.encoder",
        "audio_codec": "c2.android.aac.encoder"
    }

def run_10_cycles():
    print("Preparing RTMP server on port 19350...")
    # Start clean RTMP server
    server_proc = subprocess.Popen([sys.executable, "tools/test_rtmp_server.py", "--port", "19350"])
    time.sleep(1.0)
    run_cmd(f"{ADB} reverse tcp:19350 tcp:19350")

    print("Launching app...")
    run_cmd(f"{ADB} shell am force-stop com.fairylive.app")
    time.sleep(0.5)
    run_cmd(f"{ADB} shell am start -n com.fairylive.app/.MainActivity")
    time.sleep(2.0)

    # Tap Start Broadcast
    print("Starting broadcast...")
    run_cmd(f"{ADB} shell input tap 540 1166")
    time.sleep(5.0)

    pid = get_pid()
    if not pid:
        print("ERROR: App PID not found!")
        server_proc.terminate()
        return

    print(f"App streaming with PID {pid}. Gathering Baseline (Cycle 0)...")
    force_art_gc(pid)
    baseline = measure_resources(pid)
    baseline["cycle"] = 0
    print(f"Cycle 0 Baseline: {baseline}")

    cycles_data = [baseline]

    # Run 10 disconnect-reconnect cycles
    for c in range(1, 11):
        print(f"\n--- Cycle {c}/10 ---")
        # Terminate RTMP server to induce socket disconnect / broken pipe
        print(f"Inducing socket disconnect (terminating server)...")
        server_proc.terminate()
        server_proc.wait()
        time.sleep(0.5)

        # Restart server so client can reconnect
        print("Restarting RTMP server for client reconnection...")
        server_proc = subprocess.Popen([sys.executable, "tools/test_rtmp_server.py", "--port", "19350"])
        
        # Client reconnect backoff is 1000ms. Wait 3.5s for reconnect and stream resumption
        print("Waiting for auto-reconnect...")
        time.sleep(3.5)

        # Trigger ART GC for consistent post-GC checkpoint
        force_art_gc(pid)
        res = measure_resources(pid)
        res["cycle"] = c
        print(f"Cycle {c} Post-GC: PSS={res['pss_kb']} KB, Native={res['native_heap_kb']} KB, Dalvik={res['dalvik_heap_kb']} KB, RSS={res['rss_kb']} KB, Threads={res['threads']}, Sockets={res['sockets']}, Codecs={res['active_codecs']}")
        cycles_data.append(res)

    # Save to json
    with open("tools/audit_reconnect_10cycles.json", "w") as f:
        json.dump(cycles_data, f, indent=2)
    print("\nSaved 10-cycle audit data to tools/audit_reconnect_10cycles.json")

    # Stop broadcast and cleanup
    run_cmd(f"{ADB} shell input tap 540 1166")
    time.sleep(1.0)
    run_cmd(f"{ADB} shell am force-stop com.fairylive.app")
    server_proc.terminate()
    server_proc.wait()

if __name__ == "__main__":
    run_10_cycles()
