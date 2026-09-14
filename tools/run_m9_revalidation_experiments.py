import os
import sys
import time
import subprocess
import socket
import threading
import json
import re

ADB = "adb"

def run_cmd(cmd, timeout=30):
    p = subprocess.run(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=timeout)
    return p.stdout.strip()

def get_app_pid():
    out = run_cmd(f"{ADB} shell pidof com.fairylive.app")
    return out.split()[0] if out else None

def get_metrics():
    out = run_cmd(f"{ADB} shell dumpsys meminfo com.fairylive.app")
    pss = 0
    rss = 0
    native_heap = 0
    dalvik_heap = 0
    for line in out.splitlines():
        if "TOTAL PSS:" in line:
            parts = line.split()
            pss = int(parts[2]) if len(parts) > 2 else 0
        elif "TOTAL RSS:" in line:
            parts = line.split()
            rss = int(parts[2]) if len(parts) > 2 else 0
        elif "Native Heap" in line and "TOTAL" not in line:
            parts = line.split()
            if len(parts) >= 3 and parts[0] == "Native" and parts[1] == "Heap":
                try:
                    native_heap = int(parts[2])
                except:
                    pass
        elif "Dalvik Heap" in line and "TOTAL" not in line:
            parts = line.split()
            if len(parts) >= 3 and parts[0] == "Dalvik" and parts[1] == "Heap":
                try:
                    dalvik_heap = int(parts[2])
                except:
                    pass
    pid = get_app_pid()
    threads = 0
    sockets = 0
    if pid:
        try:
            t_out = run_cmd(f"{ADB} shell ls /proc/{pid}/task | wc -l")
            threads = int(t_out)
        except:
            pass
        try:
            s_out = run_cmd(f"{ADB} shell cat /proc/{pid}/net/tcp | wc -l")
            sockets = max(0, int(s_out) - 1)
        except:
            pass
    codecs = 0
    try:
        c_out = run_cmd(f"{ADB} shell dumpsys media.player")
        codecs = c_out.count("OMX.") + c_out.count("c2.android.")
    except:
        pass
    return {
        "pss_kb": pss,
        "rss_kb": rss,
        "native_heap_kb": native_heap,
        "dalvik_heap_kb": dalvik_heap,
        "threads": threads,
        "sockets": sockets,
        "codecs": codecs
    }

def start_stream():
    # Tap GO LIVE button
    run_cmd(f"{ADB} shell input tap 540 1166")
    time.sleep(1.2)
    # Tap Start button on MediaProjection dialog
    run_cmd(f"{ADB} shell input tap 893 1703")

def stop_stream():
    # Tap GO LIVE button to toggle stop
    run_cmd(f"{ADB} shell input tap 540 1166")
    time.sleep(1.0)

def force_restart_app():
    run_cmd(f"{ADB} shell am force-stop com.fairylive.app")
    time.sleep(0.5)
    run_cmd(f"{ADB} shell am start -n com.fairylive.app/com.fairylive.app.MainActivity")
    time.sleep(2.0)

def run_experiment_1():
    print("\n--- EXPERIMENT 1: DISCONNECT & RECONNECT TIMELINE AUDIT ---", flush=True)
    force_restart_app()
    run_cmd(f"{ADB} logcat -c")
    
    server_proc = subprocess.Popen(
        [sys.executable, "tools/fault_injection_rtmp_server.py", "--port", "19350", "--disconnect-after", "5", "--disconnect-count", "1", "--max-sessions", "2", "--total-duration", "25"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True
    )
    time.sleep(1.0)
    
    print("[EXP 1] Starting stream...", flush=True)
    start_stream()
    
    # Wait for disconnect and reconnect
    time.sleep(16.0)
    
    stop_stream()
    server_proc.terminate()
    try:
        server_proc.wait(timeout=3)
    except:
        pass
    
    logcat = run_cmd(f"{ADB} logcat -d -v time -s StreamPipeline:V StreamReconnectionManager:V RtmpClient:V AdaptiveBitrateController:V")
    
    # Extract timestamps
    events = {}
    for line in logcat.splitlines():
        if "Streaming connection interrupted" in line:
            events["disconnect"] = line
        elif "Attempting reconnect #1" in line:
            events["attempt_1"] = line
        elif "Reconnected: re-injected metadata and requested sync keyframe" in line:
            events["reconnect_success"] = line
        elif "RtmpClient: Stream published successfully" in line:
            events["publish"] = line
        elif "ABR Decision" in line:
            if "abr_decisions" not in events:
                events["abr_decisions"] = []
            events["abr_decisions"].append(line)
        elif "STAGE-AUDIT" in line:
            if "stage_audits" not in events:
                events["stage_audits"] = []
            events["stage_audits"].append(line)
            
    with open("tools/audit_reconnect_timeline.json", "w") as f:
        json.dump(events, f, indent=2)
    print(f"[EXP 1] Reconnect timeline audit saved to tools/audit_reconnect_timeline.json. Events captured: {len(events)}", flush=True)

def run_experiment_2():
    print("\n--- EXPERIMENT 2: CONTROLLED BANDWIDTH THROTTLE ABR LEDGER ---", flush=True)
    force_restart_app()
    run_cmd(f"{ADB} logcat -c")
    
    # Throttle schedule: 0-5s: 1500 kbps, 5-14s: 320 kbps (severe congestion), 14-25s: 1500 kbps (recovery)
    server_proc = subprocess.Popen(
        [sys.executable, "tools/fault_injection_rtmp_server.py", "--port", "19350", "--throttle-schedule", "0:1500,5:320,14:1500", "--max-sessions", "1", "--total-duration", "28"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True
    )
    time.sleep(1.0)
    
    print("[EXP 2] Starting stream...", flush=True)
    start_stream()
    
    time.sleep(22.0)
    
    stop_stream()
    server_proc.terminate()
    try:
        server_proc.wait(timeout=3)
    except:
        pass
        
    logcat = run_cmd(f"{ADB} logcat -d -v time -s StreamPipeline:V AdaptiveBitrateController:V NetworkQualityMonitor:V")
    
    records = []
    for line in logcat.splitlines():
        if "ABR Decision" in line or "STAGE-AUDIT" in line:
            records.append(line)
            
    with open("tools/audit_bandwidth_ledger.json", "w") as f:
        json.dump(records, f, indent=2)
    print(f"[EXP 2] Bandwidth ledger saved to tools/audit_bandwidth_ledger.json. Records: {len(records)}", flush=True)

def run_experiment_3():
    print("\n--- EXPERIMENT 3: 10-CYCLE RECONNECT RESOURCE AUDIT ---", flush=True)
    force_restart_app()
    
    baseline = get_metrics()
    print(f"[EXP 3] Baseline (Before Cycle 1): PSS={baseline['pss_kb']}KB, NativeHeap={baseline['native_heap_kb']}KB, DalvikHeap={baseline['dalvik_heap_kb']}KB, RSS={baseline['rss_kb']}KB, Threads={baseline['threads']}, Sockets={baseline['sockets']}, Codecs={baseline['codecs']}", flush=True)
    
    cycle_results = [{"cycle": 0, "metrics": baseline}]
    
    for cycle in range(1, 11):
        print(f"[EXP 3] Executing Cycle #{cycle}/10...", flush=True)
        # Run server with forced disconnect after 3s
        server_proc = subprocess.Popen(
            [sys.executable, "tools/fault_injection_rtmp_server.py", "--port", "19350", "--disconnect-after", "3", "--disconnect-count", "1", "--max-sessions", "2", "--total-duration", "12"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True
        )
        time.sleep(0.8)
        
        start_stream()
        # Stream 3s -> server disconnects -> client reconnects within 1s -> stream 3s
        time.sleep(8.0)
        
        stop_stream()
        server_proc.terminate()
        try:
            server_proc.wait(timeout=2)
        except:
            pass
        time.sleep(1.0)
        
        m = get_metrics()
        print(f"[EXP 3] Cycle #{cycle} End: PSS={m['pss_kb']}KB, NativeHeap={m['native_heap_kb']}KB, DalvikHeap={m['dalvik_heap_kb']}KB, RSS={m['rss_kb']}KB, Threads={m['threads']}, Sockets={m['sockets']}, Codecs={m['codecs']}", flush=True)
        cycle_results.append({"cycle": cycle, "metrics": m})
        
    with open("tools/audit_10_cycle_resources.json", "w") as f:
        json.dump(cycle_results, f, indent=2)
    print("[EXP 3] 10-cycle resource audit saved to tools/audit_10_cycle_resources.json", flush=True)

def run_experiment_5():
    print("\n--- EXPERIMENT 5: RECONNECT FAILURE EXHAUSTION TIMELINE ---", flush=True)
    force_restart_app()
    run_cmd(f"{ADB} logcat -c")
    
    # Server disconnects after 3s and fails all subsequent reconnects
    server_proc = subprocess.Popen(
        [sys.executable, "tools/fault_injection_rtmp_server.py", "--port", "19350", "--disconnect-after", "3", "--fail-reconnect", "--max-sessions", "6", "--total-duration", "25"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True
    )
    time.sleep(1.0)
    
    print("[EXP 5] Starting stream...", flush=True)
    start_stream()
    
    # Backoff: 1s + 2s + 4s + 8s = 15s + initial 3s = 18s
    time.sleep(20.0)
    
    stop_stream()
    server_proc.terminate()
    try:
        server_proc.wait(timeout=2)
    except:
        pass
        
    logcat = run_cmd(f"{ADB} logcat -d -v time -s StreamPipeline:V StreamReconnectionManager:V")
    
    failure_records = []
    for line in logcat.splitlines():
        if "Attempting reconnect" in line or "Reconnection limit reached" in line or "Streaming connection interrupted" in line:
            failure_records.append(line)
            
    with open("tools/audit_reconnect_failure.json", "w") as f:
        json.dump(failure_records, f, indent=2)
    print(f"[EXP 5] Failure exhaustion timeline saved to tools/audit_reconnect_failure.json. Records: {len(failure_records)}", flush=True)

if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "all"
    if mode in ("1", "all"):
        run_experiment_1()
    if mode in ("2", "all"):
        run_experiment_2()
    if mode in ("3", "all"):
        run_experiment_3()
    if mode in ("5", "all"):
        run_experiment_5()
