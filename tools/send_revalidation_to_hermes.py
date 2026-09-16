import ctypes
import time
import subprocess

user32 = ctypes.windll.user32
hwnd = 721818

directive_path = r"C:\Users\benny\Desktop\FAIRY LIVE\tools\chatgpt_m13_revalidation_directive.txt"
with open(directive_path, "r", encoding="utf-8") as f:
    directive_content = f.read()

# Copy to clipboard via PowerShell stdin
p = subprocess.Popen(
    ["powershell.exe", "-NoProfile", "-Command", "$val = [Console]::In.ReadToEnd(); Set-Clipboard -Value $val"],
    stdin=subprocess.PIPE,
    text=True,
    encoding="utf-8"
)
p.communicate(input=directive_content)
time.sleep(0.3)

# Restore and focus Hermes Desktop
user32.ShowWindow(hwnd, 9)
user32.SetForegroundWindow(hwnd)
time.sleep(0.4)

# Focus composer at (350, 1005)
user32.SetCursorPos(350, 1005)
time.sleep(0.1)
user32.mouse_event(0x0002, 0, 0, 0, 0)
user32.mouse_event(0x0004, 0, 0, 0, 0)
time.sleep(0.2)

# Select all (Ctrl+A) and Paste (Ctrl+V)
user32.keybd_event(0x11, 0, 0, 0)
user32.keybd_event(0x41, 0, 0, 0)
time.sleep(0.05)
user32.keybd_event(0x41, 0, 2, 0)
user32.keybd_event(0x11, 0, 2, 0)
time.sleep(0.1)

user32.keybd_event(0x11, 0, 0, 0)
user32.keybd_event(0x56, 0, 0, 0)
time.sleep(0.05)
user32.keybd_event(0x56, 0, 2, 0)
user32.keybd_event(0x11, 0, 2, 0)
time.sleep(0.4)

# Send Enter
user32.keybd_event(0x0D, 0, 0, 0)
time.sleep(0.05)
user32.keybd_event(0x0D, 0, 2, 0)
print("Submitted ChatGPT Revalidation Directive to Hermes Desktop chat!")
