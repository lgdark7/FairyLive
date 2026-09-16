import time
import sqlite3
import subprocess

session_id = '20260913_114916_d1adb4'
db_path = r'C:\Users\benny\AppData\Local\hermes\state.db'

print(f"Starting watcher on session {session_id}...")

def get_last_message():
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    cur.execute("SELECT id, role, length(content), substr(content, 1, 100) FROM messages WHERE session_id = ? ORDER BY id DESC LIMIT 1", (session_id,))
    row = cur.fetchone()
    conn.close()
    return row

last_seen = get_last_message()
print(f"Initial state: {last_seen}")

no_change_count = 0
last_id = last_seen[0] if last_seen else 0

while True:
    time.sleep(10)
    current = get_last_message()
    if not current:
        continue
    
    cur_id, cur_role, cur_len, cur_preview = current
    
    if cur_id != last_id:
        print(f"[ACTIVE] New msg {cur_id} ({cur_role}, len={cur_len})")
        last_id = cur_id
        no_change_count = 0
    else:
        # Same message ID. If role is 'assistant' and content length > 0, check if Hermes finished its turn.
        if cur_role == 'assistant' and cur_len > 0:
            no_change_count += 1
            print(f"[ASSISTANT_REPLY] length={cur_len}, stable for {no_change_count * 10}s")
            if no_change_count >= 3:  # 30 seconds of quiet after assistant text response
                print("Hermes Desktop turn completed!")
                break
        elif cur_role == 'tool':
            # Still calling tools or processing
            no_change_count += 1
            if no_change_count >= 18:  # 180s without new tool calls might mean paused or completed
                print("No tool activity for 3 minutes. Checking status...")
                # Let's verify if assistant completed
                if cur_len > 0:
                    break
        else:
            no_change_count = 0

print("Watcher exiting: Hermes has stopped/finished.")
