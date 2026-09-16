import ctypes
import time
import subprocess
from PIL import Image

user32 = ctypes.windll.user32
hwnd = 197398

# Copy image to clipboard via PowerShell
subprocess.run(['powershell', '-NoProfile', '-Command', """
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$img = [System.Drawing.Image]::FromFile('C:\\Users\\benny\\Desktop\\hermes_desktop_screenshot.png')
[System.Windows.Forms.Clipboard]::SetImage($img)
"""], check=True)
print("Copied image to clipboard")

# Restore and focus Chrome
user32.ShowWindow(hwnd, 9)
user32.SetForegroundWindow(hwnd)
time.sleep(0.5)

# Click on ChatGPT edit box (X=1100, Y=850)
user32.SetCursorPos(1100, 850)
time.sleep(0.1)
user32.mouse_event(0x0002, 0, 0, 0, 0)
user32.mouse_event(0x0004, 0, 0, 0, 0)
time.sleep(0.3)

# Ctrl+V
user32.keybd_event(0x11, 0, 0, 0) # Ctrl down
user32.keybd_event(0x56, 0, 0, 0) # V down
time.sleep(0.05)
user32.keybd_event(0x56, 0, 2, 0) # V up
user32.keybd_event(0x11, 0, 2, 0) # Ctrl up
print("Pasted image into ChatGPT!")
time.sleep(2.0)
