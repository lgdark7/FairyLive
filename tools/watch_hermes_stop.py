import os
import sys
import time
import sqlite3
import subprocess
import ctypes
from ctypes import wintypes
from PIL import ImageGrab

SESSION_ID = "20260913_114916_d1adb4"
DB_PATH = r"C:\Users\benny\AppData\Local\hermes\state.db"
LOG_PATH = r"C:\Users\benny\AppData\Local\hermes\logs\agent.log"
SCREENSHOT_PATH = r"C:\Users\benny\Desktop\hermes_desktop_screenshot.png"

def get_last_assistant_message():
    try:
        conn = sqlite3.connect(DB_PATH)
        cur = conn.cursor()
        cur.execute(
            "SELECT id, content FROM messages WHERE session_id = ? AND role = 'assistant' AND length(content) > 100 ORDER BY id DESC LIMIT 1",
            (SESSION_ID,)
        )
        row = cur.fetchone()
        conn.close()
        return row if row else (0, "")
    except Exception:
        return (0, "")

def capture_hermes_screenshot():
    try:
        user32 = ctypes.windll.user32
        hwnd = 721818
        if user32.IsWindow(hwnd):
            user32.ShowWindow(hwnd, 9)
            user32.SetForegroundWindow(hwnd)
            time.sleep(0.3)
            rect = wintypes.RECT()
            user32.GetWindowRect(hwnd, ctypes.byref(rect))
            bbox = (rect.left, rect.top, rect.right, rect.bottom)
            img = ImageGrab.grab(bbox=bbox)
            img.save(SCREENSHOT_PATH)
            return True
    except Exception as e:
        print("Screenshot error:", e)
    return False

print(f"[WATCHER] Watching Hermes Desktop session {SESSION_ID} for turn completion...")
initial_id, _ = get_last_assistant_message()

while True:
    time.sleep(10)
    
    # Check agent.log for turn completion
    try:
        out = subprocess.check_output(
            ["powershell.exe", "-NoProfile", "-Command", f"Get-Content -Path '{LOG_PATH}' -Tail 30 | Select-String -Pattern '{SESSION_ID}'"],
            text=True
        )
    except Exception:
        out = ""

    last_id, last_content = get_last_assistant_message()

    # Check if a new final assistant message was stored (id > initial_id) and turn finished in log
    if last_id > initial_id and ("Turn ended:" in out or "tui turn finished:" in out):
        # Verify no background process is actively executing for FAIRY LIVE
        # Check if python or gradle is running under test
        try:
            p_out = subprocess.check_output(
                ["powershell.exe", "-NoProfile", "-Command", "Get-Process -Name 'python', 'gradle64' -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id"],
                text=True
            ).strip()
        except Exception:
            p_out = ""

        # Double check log doesn't indicate an in-flight API call
        if "conversation turn:" not in out.splitlines()[-1] if out.splitlines() else True:
            print(f"\n[ALERT] Hermes Desktop has finished working in session {SESSION_ID}!")
            print(f"Message ID: {last_id} ({len(last_content)} characters)")
            capture_hermes_screenshot()
            print("\n--- HERMES DESKTOP FINAL OUTPUT PREVIEW ---")
            print(last_content[-1500:])
            sys.exit(0)
