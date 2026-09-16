import ctypes
import time
import subprocess
import os

user32 = ctypes.windll.user32

def ask_m14():
    prompt = "Please provide the complete scope, technical requirements, acceptance criteria, and implementation prompt for Milestone 14 (the next milestone) so we can review the specifications while the M13 30-minute soak test is actively executing."

    ps_proc = subprocess.Popen(
        ["powershell.exe", "-NoProfile", "-Command", "$val = [Console]::In.ReadToEnd(); Set-Clipboard -Value $val"],
        stdin=subprocess.PIPE,
        text=True,
        encoding="utf-8"
    )
    ps_proc.communicate(input=prompt)
    time.sleep(0.5)

    target_hwnd = None
    def enum_cb(hwnd, lparam):
        nonlocal target_hwnd
        if user32.IsWindowVisible(hwnd):
            length = user32.GetWindowTextLengthW(hwnd)
            if length > 0:
                buf = ctypes.create_unicode_buffer(length + 1)
                user32.GetWindowTextW(hwnd, buf, length + 1)
                if "FAIRY LIVE PROJECT" in buf.value or "ChatGPT" in buf.value:
                    target_hwnd = hwnd
        return True

    WNDENUMPROC = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_int, ctypes.c_int)
    user32.EnumWindows(WNDENUMPROC(enum_cb), 0)

    if not target_hwnd:
        print("ERROR: Could not find ChatGPT window!")
        return False

    print(f"Found window HWND: {target_hwnd}")
    user32.ShowWindow(target_hwnd, 9)
    user32.SetForegroundWindow(target_hwnd)
    time.sleep(1.0)

    # Click prompt box at (1019, 1007)
    user32.SetCursorPos(1019, 1007)
    time.sleep(0.2)
    user32.mouse_event(0x0002, 0, 0, 0, 0)
    user32.mouse_event(0x0004, 0, 0, 0, 0)
    time.sleep(0.5)

    # Select all and delete
    user32.keybd_event(0x11, 0, 0, 0)
    user32.keybd_event(0x41, 0, 0, 0)
    time.sleep(0.05)
    user32.keybd_event(0x41, 0, 2, 0)
    user32.keybd_event(0x11, 0, 2, 0)
    time.sleep(0.1)

    # Paste
    user32.keybd_event(0x11, 0, 0, 0)
    user32.keybd_event(0x56, 0, 0, 0)
    time.sleep(0.1)
    user32.keybd_event(0x56, 0, 2, 0)
    user32.keybd_event(0x11, 0, 2, 0)
    print("Pasted M14 inquiry into ChatGPT prompt box.")
    time.sleep(1.5)

    # Find and click send button via UIA
    ps_find_btn = f"""
    Add-Type -AssemblyName UIAutomationClient
    $root = [System.Windows.Automation.AutomationElement]::FromHandle({target_hwnd})
    $btnCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Button)
    $btns = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $btnCond)
    foreach ($b in $btns) {{
        if ($b.Current.Name -match 'Send prompt|Send') {{
            $inv = $b.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
            if ($inv) {{
                $inv.Invoke()
                Write-Output "INVOKED"
                exit
            }}
        }}
    }}
    """
    res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_find_btn], capture_output=True, text=True)
    if "INVOKED" in res.stdout:
        print("Successfully invoked Send button via UIA!")
    else:
        print("Fallback to Enter key...")
        user32.keybd_event(0x0D, 0, 0, 0)
        time.sleep(0.05)
        user32.keybd_event(0x0D, 0, 2, 0)

    return True

if __name__ == "__main__":
    ask_m14()
