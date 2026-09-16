import ctypes
import time
import subprocess
import os

user32 = ctypes.windll.user32

def send_summary():
    txt_path = r"C:\Users\benny\Desktop\FAIRY LIVE\tools\m13_progress_summary_for_chatgpt.txt"
    with open(txt_path, "r", encoding="utf-8") as f:
        content = f.read()

    # Set clipboard safely using powershell script file or stdin
    ps_proc = subprocess.Popen(
        ["powershell.exe", "-NoProfile", "-Command", "$val = [Console]::In.ReadToEnd(); Set-Clipboard -Value $val"],
        stdin=subprocess.PIPE,
        text=True,
        encoding="utf-8"
    )
    ps_proc.communicate(input=content)
    time.sleep(0.5)

    # Find Chrome window
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
    user32.ShowWindow(target_hwnd, 9) # SW_RESTORE
    user32.SetForegroundWindow(target_hwnd)
    time.sleep(1.0)

    # Focus ProseMirror prompt box at (1019, 1007)
    user32.SetCursorPos(1019, 1007)
    time.sleep(0.2)
    user32.mouse_event(0x0002, 0, 0, 0, 0)
    user32.mouse_event(0x0004, 0, 0, 0, 0)
    time.sleep(0.5)

    # Select all and delete in case there's old draft text
    user32.keybd_event(0x11, 0, 0, 0) # Ctrl
    user32.keybd_event(0x41, 0, 0, 0) # A
    time.sleep(0.05)
    user32.keybd_event(0x41, 0, 2, 0)
    user32.keybd_event(0x11, 0, 2, 0)
    time.sleep(0.1)

    # Paste: Ctrl+V
    user32.keybd_event(0x11, 0, 0, 0)
    user32.keybd_event(0x56, 0, 0, 0)
    time.sleep(0.1)
    user32.keybd_event(0x56, 0, 2, 0)
    user32.keybd_event(0x11, 0, 2, 0)
    print("Pasted summary into ChatGPT prompt box.")
    time.sleep(1.5)

    # Inspect buttons to find Send button
    ps_find_btn = f"""
    Add-Type -AssemblyName UIAutomationClient
    $root = [System.Windows.Automation.AutomationElement]::FromHandle({target_hwnd})
    $btnCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Button)
    $btns = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $btnCond)
    foreach ($b in $btns) {{
        if ($b.Current.Name -match 'Send prompt|Send') {{
            Write-Output "FOUND_SEND:$($b.Current.BoundingRectangle.ToString())"
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
    print(res.stdout)
    if "INVOKED" in res.stdout:
        print("Successfully invoked Send button via UIA!")
        return True

    # Fallback to Enter key
    print("Pressing Enter to send...")
    user32.keybd_event(0x0D, 0, 0, 0) # Enter
    time.sleep(0.05)
    user32.keybd_event(0x0D, 0, 2, 0)
    return True

if __name__ == "__main__":
    send_summary()
