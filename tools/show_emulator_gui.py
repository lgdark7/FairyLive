import subprocess
import ctypes

user32 = ctypes.windll.user32

# Get all processes matching qemu or emulator
ps = """
Get-Process | Where-Object { $_.ProcessName -match 'qemu|emulator' } | Select-Object Id, ProcessName, MainWindowHandle, MainWindowTitle | ConvertTo-Json
"""
p = subprocess.run(["powershell", "-Command", ps], capture_output=True, text=True)
print("Processes:\n", p.stdout)

# Find all windows associated with these PIDs
import json
try:
    procs = json.loads(p.stdout)
    if isinstance(procs, dict):
        procs = [procs]
except Exception as e:
    procs = []

pids = [p["Id"] for p in procs]
print("PIDs:", pids)

def find_all_hwnds():
    hwnds = []
    def cb(hwnd, lparam):
        pid = ctypes.c_ulong()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        if pid.value in pids:
            length = user32.GetWindowTextLengthW(hwnd)
            buff = ctypes.create_unicode_buffer(length + 1)
            user32.GetWindowTextW(hwnd, buff, length + 1)
            rect = (ctypes.c_long * 4)()
            user32.GetWindowRect(hwnd, rect)
            hwnds.append((hwnd, pid.value, buff.value, list(rect)))
        return True
    WNDENUMPROC = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
    user32.EnumWindows(WNDENUMPROC(cb), 0)
    return hwnds

hwnds = find_all_hwnds()
print("Found HWNDs for emulator/qemu processes:", hwnds)

for h, pid, title, rect in hwnds:
    print(f"Bringing HWND {h} ('{title}') to screen at (100, 50, 550, 950)...")
    user32.ShowWindow(h, 9) # SW_RESTORE
    user32.SetWindowPos(h, -1, 50, 50, 550, 950, 0x0040)
    user32.SetWindowPos(h, -2, 50, 50, 550, 950, 0x0040)
    user32.SetForegroundWindow(h)

print("Finished!")
