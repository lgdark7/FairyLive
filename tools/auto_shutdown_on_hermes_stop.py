import time
import sqlite3
import subprocess
import os
import sys

SESSION_ID = '20260913_114916_d1adb4'
DB_PATH = r'C:\Users\benny\AppData\Local\hermes\state.db'
LOG_FILE = r'C:\Users\benny\Desktop\FAIRY LIVE\tools\shutdown_watcher.log'

def log(msg):
    ts = time.strftime('%Y-%m-%d %H:%M:%S')
    entry = f"[{ts}] {msg}"
    print(entry)
    with open(LOG_FILE, 'a', encoding='utf-8') as f:
        f.write(entry + '\n')

log("Starting Hermes completion watcher with auto-shutdown enabled...")

def get_last_message():
    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()
    cur.execute(
        "SELECT id, role, tool_calls, content FROM messages WHERE session_id = ? ORDER BY id DESC LIMIT 1",
        (SESSION_ID,)
    )
    row = cur.fetchone()
    conn.close()
    return row

last_id = None
stable_count = 0
REQUIRED_STABLE = 4  # 4 * 10s = 40s of stable completed assistant state

while True:
    time.sleep(10)
    row = get_last_message()
    if not row:
        continue
    
    m_id, role, tool_calls, content = row
    content_len = len(content) if content else 0
    has_tools = bool(tool_calls and tool_calls.strip() and tool_calls.strip() != '[]')

    if m_id != last_id:
        log(f"Active message change: id={m_id}, role={role}, has_tools={has_tools}, content_len={content_len}")
        last_id = m_id
        stable_count = 0
    else:
        # Check if assistant is truly done
        if role == 'assistant' and not has_tools and content_len > 0:
            stable_count += 1
            log(f"Assistant idle and stable: {stable_count}/{REQUIRED_STABLE} (id={m_id}, len={content_len})")
            if stable_count >= REQUIRED_STABLE:
                log("Hermes has officially finished its turn!")
                # Save the final response text to disk
                summary_path = r'C:\Users\benny\Desktop\FAIRY LIVE\tools\final_hermes_turn_summary.txt'
                with open(summary_path, 'w', encoding='utf-8') as sf:
                    sf.write(f"Hermes Final Turn Response (Message ID {m_id}):\n\n{content}\n")
                log(f"Saved Hermes final summary to {summary_path}")
                break
        else:
            stable_count = 0

log("Stopping background emulator and adb processes...")
try:
    subprocess.run(["adb", "emu", "kill"], capture_output=True, timeout=10)
except Exception as e:
    log(f"adb emu kill: {e}")

time.sleep(3)

# Kill remaining emulator / qemu processes if still lingering
for proc_name in ["qemu-system-x86_64.exe", "emulator.exe", "emulator64-crash-service.exe"]:
    try:
        subprocess.run(["taskkill", "/F", "/IM", proc_name], capture_output=True)
    except Exception as e:
        pass

log("All background emulation processes stopped.")
log("Initiating system shutdown with 60 second delay (run 'shutdown /a' in cmd to abort)...")

try:
    subprocess.run(["shutdown", "/s", "/t", "60", "/c", "FAIRY LIVE: Hermes completed turn. Auto-shutdown in 60s."], check=True)
    log("Shutdown command issued successfully.")
except Exception as e:
    log(f"Failed to issue shutdown: {e}")
