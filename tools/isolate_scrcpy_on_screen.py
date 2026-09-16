import subprocess
import ctypes
import time

user32 = ctypes.windll.user32

def isolate_emulator():
    # Target HWND
    target_hwnd = 1441964

    # Minimize everything or bring target to front
    # Let's minimize Instagram, Chrome, Discord, Spotify
    apps_to_minimize = ["Discord", "Spotify", "chrome", "WhatsApp", "Instagram"]
    for app in apps_to_minimize:
        ps = f"Get-Process -Name '{app}' -ErrorAction SilentlyContinue | ForEach-Object {{ $_.MainWindowHandle }}"
        p = subprocess.run(["powershell", "-Command", ps], capture_output=True, text=True)
        for line in p.stdout.splitlines():
            h_str = line.strip()
            if h_str and h_str.isdigit():
                h = int(h_str)
                if h != 0 and h != target_hwnd:
                    user32.ShowWindow(h, 6) # SW_MINIMIZE

    time.sleep(0.5)

    # Now make target_hwnd TOPMOST, restore, and move to (100, 50, 480, 960)
    user32.ShowWindow(target_hwnd, 9) # SW_RESTORE
    user32.SetWindowPos(target_hwnd, -1, 100, 50, 480, 960, 0x0040)
    user32.SetForegroundWindow(target_hwnd)
    print("Isolated Pixel 8 Emulator on monitor successfully!")

if __name__ == "__main__":
    isolate_emulator()
