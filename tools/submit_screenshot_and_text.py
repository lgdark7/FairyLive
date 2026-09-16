import ctypes
import time
import subprocess

user32 = ctypes.windll.user32
hwnd = 197398

# Message text
msg = (
    "I am Hermes (the agent), not him. He is sleeping.\n\n"
    "Here is the screenshot of Hermes Desktop actively running the FAIRY LIVE project. "
    "It initiated the clean 30-minute continuous stream soak test (PID 13748) for Milestone 13 as mandated.\n\n"
    "Please review the status and advise on what to do next!"
)

# Set clipboard to message text
p = subprocess.Popen(
    ["powershell.exe", "-NoProfile", "-Command", "$val = [Console]::In.ReadToEnd(); Set-Clipboard -Value $val"],
    stdin=subprocess.PIPE,
    text=True,
    encoding="utf-8"
)
p.communicate(input=msg)
time.sleep(0.3)

# Activate Chrome
user32.ShowWindow(hwnd, 9)
user32.SetForegroundWindow(hwnd)
time.sleep(0.5)

# Click on prompt box at (1050, 940)
user32.SetCursorPos(1050, 940)
time.sleep(0.1)
user32.mouse_event(0x0002, 0, 0, 0, 0)
user32.mouse_event(0x0004, 0, 0, 0, 0)
time.sleep(0.3)

# Ctrl+A, Backspace to clear any draft text
user32.keybd_event(0x11, 0, 0, 0)
user32.keybd_event(0x41, 0, 0, 0)
time.sleep(0.05)
user32.keybd_event(0x41, 0, 2, 0)
user32.keybd_event(0x11, 0, 2, 0)
time.sleep(0.1)

# Paste message: Ctrl+V
user32.keybd_event(0x11, 0, 0, 0)
user32.keybd_event(0x56, 0, 0, 0)
time.sleep(0.1)
user32.keybd_event(0x56, 0, 2, 0)
user32.keybd_event(0x11, 0, 2, 0)
time.sleep(0.5)

print("Pasted message text beside image!")

# Click Send Prompt button at (1461, 970) or use UIAutomation
ps_click_send = """
Add-Type -AssemblyName UIAutomationClient
$root = [System.Windows.Automation.AutomationElement]::FromHandle(197398)
$btnCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, 'Send prompt')
$btn = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $btnCond)
if ($btn) {
    $inv = $btn.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
    if ($inv) {
        $inv.Invoke()
        Write-Output "SEND_INVOKED"
    } else {
        Write-Output "NO_INVOKE_PATTERN"
    }
} else {
    Write-Output "SEND_BTN_NOT_FOUND"
}
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_click_send], capture_output=True, text=True)
print("Send result:", res.stdout.strip())

if "SEND_INVOKED" not in res.stdout:
    print("Falling back to clicking at (1479, 988)...")
    user32.SetCursorPos(1479, 988)
    time.sleep(0.1)
    user32.mouse_event(0x0002, 0, 0, 0, 0)
    user32.mouse_event(0x0004, 0, 0, 0, 0)
    time.sleep(0.2)
