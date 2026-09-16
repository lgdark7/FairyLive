import time
import sqlite3
import os

SESSION_ID = '20260913_114916_d1adb4'
DB_PATH = r'C:\Users\benny\AppData\Local\hermes\state.db'
LOG_FILE = r'C:\Users\benny\Desktop\FAIRY LIVE\tools\notify_watcher.log'

def log(msg):
    ts = time.strftime('%Y-%m-%d %H:%M:%S')
    entry = f"[{ts}] {msg}"
    print(entry)
    with open(LOG_FILE, 'a', encoding='utf-8') as f:
        f.write(entry + '\n')

log("Starting notification-only watcher for Hermes completion...")

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
REQUIRED_STABLE = 3  # 3 * 10s = 30s stable

while True:
    time.sleep(10)
    row = get_last_message()
    if not row:
        continue
    
    m_id, role, tool_calls, content = row
    content_len = len(content) if content else 0
    has_tools = bool(tool_calls and tool_calls.strip() and tool_calls.strip() != '[]')

    if m_id != last_id:
        log(f"Active turn: id={m_id}, role={role}, has_tools={has_tools}, len={content_len}")
        last_id = m_id
        stable_count = 0
    else:
        if role == 'assistant' and not has_tools and content_len > 0:
            stable_count += 1
            log(f"Hermes turn idle: {stable_count}/{REQUIRED_STABLE}")
            if stable_count >= REQUIRED_STABLE:
                log("Hermes has completely stopped and finished its turn!")
                summary_path = r'C:\Users\benny\Desktop\FAIRY LIVE\tools\final_hermes_turn_summary.txt'
                with open(summary_path, 'w', encoding='utf-8') as sf:
                    sf.write(f"Hermes Final Response (ID {m_id}):\n\n{content}\n")
                log(f"Saved response to {summary_path}")
                break
        else:
            stable_count = 0

log("Watcher exiting - ready to inform user.")
