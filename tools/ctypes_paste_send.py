import ctypes
import time
import subprocess

user32 = ctypes.windll.user32

# Find ChatGPT window
def get_chatgpt_hwnd():
    ps = subprocess.run(["powershell", "-Command", "(Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1).MainWindowHandle"], capture_output=True, text=True)
    return int(ps.stdout.strip())

hwnd = get_chatgpt_hwnd()
print("HWND:", hwnd)

# Load report to clipboard
report_path = "tools/m9_revalidation_report.txt"
with open(report_path, "r", encoding="utf-8") as f:
    text = f.read()

ps_copy = subprocess.Popen(["powershell", "-Command", "Set-Clipboard -Value ([Console]::In.ReadToEnd())"], stdin=subprocess.PIPE, text=True)
ps_copy.communicate(text)
print("Loaded clipboard, length:", len(text))

# Bring to foreground and focus
user32.ShowWindow(hwnd, 9) # SW_RESTORE
user32.SetForegroundWindow(hwnd)
time.sleep(0.5)

# Click inside the ProseMirror editor at X=600, Y=830 (screen coordinates)
# Let's get the cursor pos or click at 600, 830
user32.SetCursorPos(600, 830)
time.sleep(0.1)
user32.mouse_event(0x02, 0, 0, 0, 0) # left down
time.sleep(0.05)
user32.mouse_event(0x04, 0, 0, 0, 0) # left up
time.sleep(0.3)

# Send Ctrl+V using keybd_event
VK_CONTROL = 0x11
VK_V = 0x56
VK_RETURN = 0x0D

user32.keybd_event(VK_CONTROL, 0, 0, 0)
user32.keybd_event(VK_V, 0, 0, 0)
time.sleep(0.05)
user32.keybd_event(VK_V, 0, 2, 0)
user32.keybd_event(VK_CONTROL, 0, 2, 0)

print("Sent Ctrl+V")
time.sleep(1.5)

# Press Enter to send
user32.keybd_event(VK_RETURN, 0, 0, 0)
time.sleep(0.05)
user32.keybd_event(VK_RETURN, 0, 2, 0)

print("Sent Enter")
