import subprocess
import time
import ctypes

user32 = ctypes.windll.user32

def submit_report():
    print("Reading report file...")
    with open("tools/m9_final_report.txt", "r", encoding="utf-8") as f:
        text = f.read()

    print("Copying report to Windows clipboard...")
    # Use PowerShell to set clipboard
    ps_cmd = 'Set-Clipboard -Value ([System.IO.File]::ReadAllText("tools/m9_final_report.txt", [System.Text.Encoding]::UTF8))'
    subprocess.run(["powershell", "-Command", ps_cmd], check=True)
    time.sleep(0.5)

    print("Activating ChatGPT window...")
    ps_activate = """
    $proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
    if ($proc) {
        Add-Type @"
        using System;
        using System.Runtime.InteropServices;
        public class Win32 {
            [DllImport("user32.dll")]
            [return: MarshalAs(UnmanagedType.Bool)]
            public static extern bool SetForegroundWindow(IntPtr hWnd);
            [DllImport("user32.dll")]
            [return: MarshalAs(UnmanagedType.Bool)]
            public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
        }
"@
        [Win32]::ShowWindow($proc.MainWindowHandle, 9)
        [Win32]::SetForegroundWindow($proc.MainWindowHandle)
    }
    """
    subprocess.run(["powershell", "-Command", ps_activate], check=True)
    time.sleep(1.0)

    # Click inside ChatGPT input area (around 1500, 840)
    print("Focusing ChatGPT input area...")
    user32.SetCursorPos(1500, 840)
    time.sleep(0.1)
    user32.mouse_event(0x02, 0, 0, 0, 0)
    time.sleep(0.05)
    user32.mouse_event(0x04, 0, 0, 0, 0)
    time.sleep(0.3)

    # Send Ctrl+V
    print("Pasting report...")
    # VK_CONTROL = 0x11, 'V' = 0x56
    user32.keybd_event(0x11, 0, 0, 0) # Ctrl down
    time.sleep(0.05)
    user32.keybd_event(0x56, 0, 0, 0) # V down
    time.sleep(0.05)
    user32.keybd_event(0x56, 0, 2, 0) # V up
    time.sleep(0.05)
    user32.keybd_event(0x11, 0, 2, 0) # Ctrl up
    time.sleep(1.0)

    # Click Send button at (1755, 844)
    print("Clicking Send button at (1755, 844)...")
    user32.SetCursorPos(1755, 844)
    time.sleep(0.2)
    user32.mouse_event(0x02, 0, 0, 0, 0)
    time.sleep(0.05)
    user32.mouse_event(0x04, 0, 0, 0, 0)
    time.sleep(0.2)
    print("Report submitted successfully!")

if __name__ == "__main__":
    submit_report()
