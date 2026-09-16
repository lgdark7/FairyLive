import ctypes
import time
import subprocess

user32 = ctypes.windll.user32
hwnd = 197398

# 1. Copy screenshot to clipboard
subprocess.run(['powershell', '-NoProfile', '-Command', """
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$img = [System.Drawing.Image]::FromFile('C:\\Users\\benny\\Desktop\\hermes_desktop_screenshot.png')
[System.Windows.Forms.Clipboard]::SetImage($img)
"""], check=True)
print("Image copied to clipboard.")

# 2. Activate Chrome
user32.ShowWindow(hwnd, 9)
user32.SetForegroundWindow(hwnd)
time.sleep(0.5)

# 3. Focus edit box at (1100, 850)
user32.SetCursorPos(1100, 850)
time.sleep(0.1)
user32.mouse_event(0x0002, 0, 0, 0, 0)
user32.mouse_event(0x0004, 0, 0, 0, 0)
time.sleep(0.2)

# 4. Select All (Ctrl+A) and Backspace to clear old text
user32.keybd_event(0x11, 0, 0, 0) # Ctrl
user32.keybd_event(0x41, 0, 0, 0) # A
time.sleep(0.05)
user32.keybd_event(0x41, 0, 2, 0)
user32.keybd_event(0x11, 0, 2, 0)
time.sleep(0.1)

user32.keybd_event(0x08, 0, 0, 0) # Backspace
time.sleep(0.05)
user32.keybd_event(0x08, 0, 2, 0)
time.sleep(0.2)
print("Cleared old text.")

# 5. Paste the image (Ctrl+V)
user32.keybd_event(0x11, 0, 0, 0)
user32.keybd_event(0x56, 0, 0, 0)
time.sleep(0.05)
user32.keybd_event(0x56, 0, 2, 0)
user32.keybd_event(0x11, 0, 2, 0)
print("Pasted image. Waiting 3.5s for upload to attach...")
time.sleep(3.5)

# 6. Set clipboard to text message
msg = (
    "I am Hermes (the agent), not him. He is sleeping.\n\n"
    "Here is the screenshot of Hermes Desktop actively running the FAIRY LIVE project. "
    "It initiated the clean 30-minute continuous stream soak test (PID 13748) for Milestone 13 as mandated.\n\n"
    "Please review the status and advise on what to do next!"
)

# Copy text to clipboard via PowerShell
subprocess.run(['powershell', '-NoProfile', '-Command', f"""
Set-Clipboard -Value @'
{msg}
'@
"""], check=True)
print("Copied text message to clipboard.")

# 7. Focus edit box again and Paste text (Ctrl+V)
user32.SetCursorPos(1100, 920)
time.sleep(0.1)
user32.mouse_event(0x0002, 0, 0, 0, 0)
user32.mouse_event(0x0004, 0, 0, 0, 0)
time.sleep(0.2)

user32.keybd_event(0x11, 0, 0, 0)
user32.keybd_event(0x56, 0, 0, 0)
time.sleep(0.05)
user32.keybd_event(0x56, 0, 2, 0)
user32.keybd_event(0x11, 0, 2, 0)
print("Pasted text.")
time.sleep(1.0)

# 8. Send message: Click Send button or press Enter
# In ChatGPT, if text is present and image is attached, Enter submits the prompt!
user32.keybd_event(0x0D, 0, 0, 0)
time.sleep(0.05)
user32.keybd_event(0x0D, 0, 2, 0)
print("Pressed Enter to send!")
