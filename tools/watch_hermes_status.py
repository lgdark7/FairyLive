import os
import sys
import time
import json
import sqlite3
import subprocess

PID = 13748
SESSION_ID = "20260913_114916_d1adb4"
LEDGER_PATH = r"C:\Users\benny\Desktop\FAIRY LIVE\tools\audit_m13_30min_stream_ledger.json"
DB_PATH = r"C:\Users\benny\AppData\Local\hermes\state.db"

def is_pid_running(pid):
    try:
        out = subprocess.check_output(
            ["powershell.exe", "-NoProfile", "-Command", f"Get-Process -Id {pid} -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id"],
            text=True
        ).strip()
        return bool(out)
    except Exception:
        return False

def get_latest_message_id():
    try:
        conn = sqlite3.connect(DB_PATH)
        cur = conn.cursor()
        cur.execute("SELECT MAX(id) FROM messages WHERE session_id = ?", (SESSION_ID,))
        row = cur.fetchone()
        conn.close()
        return row[0] if row else 0
    except Exception:
        return 0

def get_latest_assistant_text():
    try:
        conn = sqlite3.connect(DB_PATH)
        cur = conn.cursor()
        cur.execute("SELECT content FROM messages WHERE session_id = ? AND role = 'assistant' ORDER BY id DESC LIMIT 1", (SESSION_ID,))
        row = cur.fetchone()
        conn.close()
        return row[0] if row else ""
    except Exception:
        return ""

def get_ledger_status():
    if not os.path.exists(LEDGER_PATH):
        return None, 0
    try:
        with open(LEDGER_PATH, "r", encoding="utf-8") as f:
            data = json.load(f)
        return data.get("status"), len(data.get("samples", []))
    except Exception:
        return None, 0

print(f"[WATCHER] Monitoring started. Watching PID {PID} and Hermes Desktop session {SESSION_ID}...")
initial_msg_id = get_latest_message_id()
soak_was_running = True

while True:
    time.sleep(15)
    running = is_pid_running(PID)
    status, samples = get_ledger_status()
    latest_msg_id = get_latest_message_id()

    # Check if soak test just finished or stopped
    if soak_was_running and not running:
        soak_was_running = False
        print(f"\n[ALERT] Soak test PID {PID} has stopped running! Total samples collected: {samples}/30. Status: {status}")
        
        # Give Hermes Desktop up to 3 minutes to run its post-soak analysis and compile the dossier
        print("[WATCHER] Waiting for Hermes Desktop to process final dossier...")
        for _ in range(36): # up to 6 minutes
            time.sleep(10)
            current_msg_id = get_latest_message_id()
            if current_msg_id > initial_msg_id + 2: # At least a tool call and assistant response completed
                # Check if assistant has completed its turn
                asst_text = get_latest_assistant_text()
                if asst_text and ("dossier" in asst_text.lower() or "checkpoint" in asst_text.lower() or "waiting for chatgpt" in asst_text.lower() or "milestone 13" in asst_text.lower()):
                    print(f"\n[ALERT] Hermes Desktop has finished processing and stopped! Message ID: {current_msg_id}")
                    print("\n--- LATEST HERMES DESKTOP OUTPUT PREVIEW ---")
                    print(asst_text[-1200:])
                    sys.exit(0)

        # If Hermes Desktop hasn't generated anything after 6 minutes
        asst_text = get_latest_assistant_text()
        print(f"\n[ALERT] Hermes Desktop turn concluded or idle after soak completion. Samples: {samples}/30.")
        print(asst_text[-1000:])
        sys.exit(0)

    # Periodic progress print
    if running:
        print(f"[WATCHER] Soak running... Samples: {samples}/30 (T+{samples}m)")
