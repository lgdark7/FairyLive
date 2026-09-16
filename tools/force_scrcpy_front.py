import ctypes
import time

user32 = ctypes.windll.user32
kernel32 = ctypes.windll.kernel32

def force_foreground(hwnd):
    fore_hwnd = user32.GetForegroundWindow()
    fore_thread = user32.GetWindowThreadProcessId(fore_hwnd, None)
    curr_thread = kernel32.GetCurrentThreadId()

    user32.AttachThreadInput(curr_thread, fore_thread, True)
    user32.ShowWindow(hwnd, 9) # SW_RESTORE
    user32.SetWindowPos(hwnd, -1, 50, 50, 480, 960, 0x0040) # HWND_TOPMOST
    user32.SetForegroundWindow(hwnd)
    user32.AttachThreadInput(curr_thread, fore_thread, False)

# Find scrcpy HWND
import subprocess
ps = "(Get-Process -Name scrcpy).MainWindowHandle"
p = subprocess.run(["powershell", "-Command", ps], capture_output=True, text=True)
hwnd = int(p.stdout.strip())
print("scrcpy HWND:", hwnd)
force_foreground(hwnd)
print("Brought scrcpy to foreground!")
