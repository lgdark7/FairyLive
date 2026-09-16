import ctypes
import time
import subprocess
import os

user32 = ctypes.windll.user32

def submit_m10():
    print("Reading tools/m10_final_report.txt...")
    with open("tools/m10_final_report.txt", "r", encoding="utf-8") as f:
        text = f.read()

    print(f"Read {len(text)} characters. Setting clipboard...")
    # PowerShell Set-Clipboard
    ps_cmd = 'Set-Clipboard -Value ([System.IO.File]::ReadAllText("tools/m10_final_report.txt", [System.Text.Encoding]::UTF8))'
    subprocess.run(["powershell", "-NoProfile", "-Command", ps_cmd], check=True)
    time.sleep(0.5)

    print("Activating Chrome window...")
    h = 131862
    user32.ShowWindow(h, 9)
    user32.SetForegroundWindow(h)
    time.sleep(1.0)

    # Click inside prompt box (1114, 995)
    print("Clicking ChatGPT prompt box at (1114, 995)...")
    user32.SetCursorPos(1114, 995)
    time.sleep(0.1)
    user32.mouse_event(0x02, 0, 0, 0, 0) # Left down
    time.sleep(0.05)
    user32.mouse_event(0x04, 0, 0, 0, 0) # Left up
    time.sleep(0.5)

    # Paste via Ctrl+V
    print("Sending Ctrl+V...")
    user32.keybd_event(0x11, 0, 0, 0) # Ctrl down
    time.sleep(0.05)
    user32.keybd_event(0x56, 0, 0, 0) # V down
    time.sleep(0.05)
    user32.keybd_event(0x56, 0, 2, 0) # V up
    time.sleep(0.05)
    user32.keybd_event(0x11, 0, 2, 0) # Ctrl up
    time.sleep(1.5)

    # Press Enter
    print("Sending Enter key to submit...")
    user32.keybd_event(0x0D, 0, 0, 0) # Enter down
    time.sleep(0.05)
    user32.keybd_event(0x0D, 0, 2, 0) # Enter up
    time.sleep(0.5)

    print("Submitted! Waiting 5s and capturing screenshot...")
    time.sleep(5.0)

    from PIL import ImageGrab
    im = ImageGrab.grab()
    im.save("tools/chatgpt_submitted_m10.png")
    print("Screenshot saved to tools/chatgpt_submitted_m10.png")

if __name__ == "__main__":
    submit_m10()
