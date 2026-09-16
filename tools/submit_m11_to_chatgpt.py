import ctypes
import time
import subprocess
import os

user32 = ctypes.windll.user32

def submit_m11():
    print("Reading tools/m11_final_report.txt...")
    with open("tools/m11_final_report.txt", "r", encoding="utf-8") as f:
        text = f.read()

    print(f"Read {len(text)} characters. Setting clipboard...")
    ps_cmd = 'Set-Clipboard -Value ([System.IO.File]::ReadAllText("tools/m11_final_report.txt", [System.Text.Encoding]::UTF8))'
    subprocess.run(["powershell", "-NoProfile", "-Command", ps_cmd], check=True)
    time.sleep(0.5)

    print("Activating Chrome window (HWND 131862)...")
    h = 131862
    user32.ShowWindow(h, 9) # SW_RESTORE
    user32.SetForegroundWindow(h)
    time.sleep(1.0)

    # Click inside prompt box (1114, 995)
    print("Clicking ChatGPT prompt box at (1114, 995)...")
    user32.SetCursorPos(1114, 995)
    time.sleep(0.1)
    user32.mouse_event(0x02, 0, 0, 0, 0) # Left down
    time.sleep(0.05)
    user32.mouse_event(0x04, 0, 0, 0, 0) # Left up
    time.sleep(0.5)

    # Paste via Ctrl+V
    print("Sending Ctrl+V...")
    user32.keybd_event(0x11, 0, 0, 0) # Ctrl down
    time.sleep(0.05)
    user32.keybd_event(0x56, 0, 0, 0) # V down
    time.sleep(0.05)
    user32.keybd_event(0x56, 0, 2, 0) # V up
    time.sleep(0.05)
    user32.keybd_event(0x11, 0, 2, 0) # Ctrl up
    time.sleep(1.5)

    # Inspect for Send button via UIAutomation
    ps_find_send = """
    Add-Type -AssemblyName UIAutomationClient
    Add-Type -AssemblyName UIAutomationTypes
    $root = [System.Windows.Automation.AutomationElement]::FromHandle(131862)
    $btnCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Button)
    $btns = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $btnCond)
    for ($i = 0; $i -lt $btns.Count; $i++) {
        $b = $btns[$i]
        if ($b.Current.Name -match "Send prompt" -or $b.Current.Name -match "Send" -or $b.Current.AutomationId -match "send") {
            $r = $b.Current.BoundingRectangle
            Write-Output "SEND_BTN:$($r.X),$($r.Y),$($r.Width),$($r.Height)"
            exit
        }
    }
    """
    res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_find_send], capture_output=True, text=True)
    out = res.stdout.strip()
    print("UIAutomation Send button search:", out)
    if "SEND_BTN:" in out:
        parts = out.split("SEND_BTN:")[1].split(",")
        bx, by, bw, bh = int(parts[0]), int(parts[1]), int(parts[2]), int(parts[3])
        cx, cy = bx + bw // 2, by + bh // 2
        print(f"Clicking Send button at ({cx}, {cy})...")
        user32.SetCursorPos(cx, cy)
        time.sleep(0.1)
        user32.mouse_event(0x02, 0, 0, 0, 0)
        time.sleep(0.05)
        user32.mouse_event(0x04, 0, 0, 0, 0)
    else:
        # Press Enter
        print("Send button not found by name; sending Enter key to submit...")
        user32.keybd_event(0x0D, 0, 0, 0) # Enter down
        time.sleep(0.05)
        user32.keybd_event(0x0D, 0, 2, 0) # Enter up

    time.sleep(0.5)
    print("Submitted! Waiting 6s and capturing screenshot...")
    time.sleep(6.0)

    from PIL import ImageGrab
    im = ImageGrab.grab()
    im.save("tools/chatgpt_submitted_m11.png")
    print("Screenshot saved to tools/chatgpt_submitted_m11.png")

if __name__ == "__main__":
    submit_m11()
