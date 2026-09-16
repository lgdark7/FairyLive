import ctypes
import time
import subprocess
import os

user32 = ctypes.windll.user32

def submit_reval():
    print("Reading tools/m11_revalidation_report.txt...")
    with open("tools/m11_revalidation_report.txt", "r", encoding="utf-8") as f:
        content = f.read()

    # Copy to clipboard via powershell
    p = subprocess.Popen(["powershell", "-NoProfile", "-Command", "$input | Set-Clipboard"], stdin=subprocess.PIPE, text=True, encoding='utf-8')
    p.communicate(input=content)
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
                if "FAIRY LIVE" in buf.value and "Chrome" in buf.value:
                    target_hwnd = hwnd
        return True

    WNDENUMPROC = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_int, ctypes.c_int)
    user32.EnumWindows(WNDENUMPROC(enum_cb), 0)

    if not target_hwnd:
        print("Could not find Chrome FAIRY LIVE window!")
        return

    print(f"Found window HWND: {target_hwnd}")
    user32.ShowWindow(target_hwnd, 9) # SW_RESTORE
    user32.SetForegroundWindow(target_hwnd)
    time.sleep(1.0)

    # Click ProseMirror prompt box at (1114, 995)
    user32.SetCursorPos(1114, 995)
    time.sleep(0.2)
    user32.mouse_event(0x0002, 0, 0, 0, 0)
    user32.mouse_event(0x0004, 0, 0, 0, 0)
    time.sleep(0.5)

    # Paste
    # Ctrl down
    user32.keybd_event(0x11, 0, 0, 0)
    # V down
    user32.keybd_event(0x56, 0, 0, 0)
    time.sleep(0.1)
    # V up
    user32.keybd_event(0x56, 0, 2, 0)
    # Ctrl up
    user32.keybd_event(0x11, 0, 2, 0)
    print("Pasted report into prompt box.")

    time.sleep(1.5)

    # Find Send button via UIAutomation
    ps_find_send = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
$root = [System.Windows.Automation.AutomationElement]::RootElement
$cond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, "FAIRY LIVE PROJECT - Recheck FAIRY LIVE M8 - Google Chrome")
$win = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $cond)
if ($win) {
    $btnCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Button)
    $btns = $win.FindAll([System.Windows.Automation.TreeScope]::Descendants, $btnCond)
    foreach ($b in $btns) {
        $name = $b.Current.Name
        $rect = $b.Current.BoundingRectangle
        if ($name -match "Send" -or ($rect.Y -gt 900 -and $rect.X -gt 1400)) {
            Write-Output "SEND_BTN:$($rect.X),$($rect.Y),$($rect.Width),$($rect.Height)"
            break
        }
    }
}
"""
    p = subprocess.run(["powershell", "-NoProfile", "-Command", ps_find_send], capture_output=True, text=True)
    out = p.stdout.strip()
    print(f"UIAutomation Send button search: {out}")
    if "SEND_BTN:" in out:
        parts = out.split("SEND_BTN:")[1].split(",")
        bx = int(float(parts[0])) + int(float(parts[2])) // 2
        by = int(float(parts[1])) + int(float(parts[3])) // 2
        print(f"Clicking Send button at ({bx}, {by})...")
        user32.SetCursorPos(bx, by)
        time.sleep(0.2)
        user32.mouse_event(0x0002, 0, 0, 0, 0)
        user32.mouse_event(0x0004, 0, 0, 0, 0)
    else:
        print("Fallback to Enter key...")
        user32.keybd_event(0x0D, 0, 0, 0)
        user32.keybd_event(0x0D, 0, 2, 0)

    print("Submitted! Waiting 10s...")
    time.sleep(10.0)

if __name__ == "__main__":
    submit_reval()
