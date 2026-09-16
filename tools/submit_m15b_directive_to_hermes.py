import time
import ctypes
import subprocess
import os

directive_path = r"C:\Users\benny\Desktop\FAIRY LIVE\tools\m15b_redesign_directive.txt"
with open(directive_path, "r", encoding="utf-8") as f:
    directive_text = f.read()

print(f"Loaded directive text: {len(directive_text)} characters")

# Copy to Windows clipboard via PowerShell
ps_clip = """
Set-Clipboard -Value ([System.IO.File]::ReadAllText('C:\\Users\\benny\\Desktop\\FAIRY LIVE\\tools\\m15b_redesign_directive.txt'))
"""
subprocess.run(["powershell", "-NoProfile", "-Command", ps_clip], check=True)
print("Copied directive to clipboard successfully.")

user32 = ctypes.windll.user32
hwnd = 1901350

user32.ShowWindow(hwnd, 9)
user32.SetForegroundWindow(hwnd)
time.sleep(0.5)

# Click composer edit box at (627 + 50 = 677, 871 + 10 = 881)
user32.SetCursorPos(677, 881)
time.sleep(0.1)
user32.mouse_event(0x0002, 0, 0, 0, 0)
time.sleep(0.05)
user32.mouse_event(0x0004, 0, 0, 0, 0)
time.sleep(0.3)

# Send Ctrl+V
VK_CONTROL = 0x11
VK_V = 0x56
VK_RETURN = 0x0D
KEYEVENTF_KEYUP = 0x0002

user32.keybd_event(VK_CONTROL, 0, 0, 0)
user32.keybd_event(VK_V, 0, 0, 0)
time.sleep(0.05)
user32.keybd_event(VK_V, 0, KEYEVENTF_KEYUP, 0)
user32.keybd_event(VK_CONTROL, 0, KEYEVENTF_KEYUP, 0)
time.sleep(0.5)

# Press Enter to send
user32.keybd_event(VK_RETURN, 0, 0, 0)
time.sleep(0.05)
user32.keybd_event(VK_RETURN, 0, KEYEVENTF_KEYUP, 0)
print("Directive submitted to Hermes Desktop chat successfully!")
