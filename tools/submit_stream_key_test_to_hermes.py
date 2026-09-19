import time
import ctypes
import subprocess
import os

image_path = r"C:\Users\benny\Desktop\FAIRY LIVE\tools\youtube_studio_live_stream_key.jpg"
prompt_text = (
    f"@file:{image_path}\n\n"
    "In this screenshot it has stream key by using it test live! "
    f"Image path: {image_path}"
)

print(f"Prepared stream key prompt: {prompt_text}")

ps_clip = f"""
Set-Clipboard -Value @'
{prompt_text}
'@
"""
subprocess.run(["powershell", "-NoProfile", "-Command", ps_clip], check=True)
print("Copied prompt to clipboard.")

user32 = ctypes.windll.user32

# Find Hermes Electron window
ps_find = """
Get-Process | Where-Object { $_.MainWindowTitle -match "Hermes" -or $_.ProcessName -match "Hermes" -or $_.ProcessName -match "electron" } | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -ExpandProperty MainWindowHandle
"""
res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_find], capture_output=True)
hwnd_str = res.stdout.decode("utf-8").strip()
hwnd = int(hwnd_str) if hwnd_str else 262866

print(f"Targeting Hermes HWND: {hwnd}")
user32.ShowWindow(hwnd, 9)
user32.SetForegroundWindow(hwnd)
time.sleep(0.5)

# Click composer edit box at (778, 745)
user32.SetCursorPos(778, 745)
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
time.sleep(0.8)

# Press Enter to submit
user32.keybd_event(VK_RETURN, 0, 0, 0)
time.sleep(0.05)
user32.keybd_event(VK_RETURN, 0, KEYEVENTF_KEYUP, 0)
print("Submitted YouTube stream key testing directive to Hermes Desktop!")
