import ctypes
import time
import subprocess

user32 = ctypes.windll.user32

def get_chatgpt_hwnd():
    ps = subprocess.run(["powershell", "-Command", "(Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1).MainWindowHandle"], capture_output=True, text=True)
    return int(ps.stdout.strip())

hwnd = get_chatgpt_hwnd()
print("ChatGPT HWND:", hwnd)

user32.ShowWindow(hwnd, 9) # SW_RESTORE
user32.SetForegroundWindow(hwnd)
time.sleep(0.5)

# Click Send button at (914, 779)
user32.SetCursorPos(914, 779)
time.sleep(0.1)
user32.mouse_event(0x02, 0, 0, 0, 0) # left down
time.sleep(0.05)
user32.mouse_event(0x04, 0, 0, 0, 0) # left up
print("Clicked Send button at (914, 779)")

time.sleep(0.5)
# Also send Enter key just in case
user32.keybd_event(0x0D, 0, 0, 0)
time.sleep(0.05)
user32.keybd_event(0x0D, 0, 2, 0)
print("Sent Enter key")
