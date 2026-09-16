import subprocess
import time
import ctypes

user32 = ctypes.windll.user32

def bring_scrcpy_top():
    ps = """
    (Get-Process -Name scrcpy -ErrorAction SilentlyContinue).MainWindowHandle
    """
    p = subprocess.run(["powershell", "-Command", ps], capture_output=True, text=True)
    hwnds = [int(x.strip()) for x in p.stdout.splitlines() if x.strip() and x.strip().isdigit()]
    print("scrcpy HWNDs:", hwnds)

    for h in hwnds:
        print(f"Bringing scrcpy HWND {h} to top...")
        user32.ShowWindow(h, 9) # SW_RESTORE
        # HWND_TOPMOST = -1, SWP_SHOWWINDOW = 0x0040
        user32.SetWindowPos(h, -1, 50, 50, 480, 960, 0x0040)
        user32.SetForegroundWindow(h)

if __name__ == "__main__":
    bring_scrcpy_top()
