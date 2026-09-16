import time
import sqlite3

session_id = '20260913_114916_d1adb4'
db_path = r'C:\Users\benny\AppData\Local\hermes\state.db'

print("Monitoring Hermes Desktop completion...")

def get_last():
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    cur.execute("SELECT id, role, length(content), substr(content, 1, 100) FROM messages WHERE session_id = ? ORDER BY id DESC LIMIT 1", (session_id,))
    row = cur.fetchone()
    conn.close()
    return row

last = get_last()
last_id = last[0] if last else 0
stable = 0

while True:
    time.sleep(10)
    cur = get_last()
    if not cur:
        continue
    c_id, c_role, c_len, c_prev = cur
    if c_id != last_id:
        print(f"[ACTIVE] msg {c_id} ({c_role}, len={c_len})")
        last_id = c_id
        stable = 0
    else:
        if c_role == 'assistant' and c_len > 0:
            stable += 1
            print(f"[ASSISTANT_FINISHED] len={c_len}, stable={stable*10}s")
            if stable >= 3:
                print("Hermes turn finished!")
                break

print("Ready to paste directive.")
