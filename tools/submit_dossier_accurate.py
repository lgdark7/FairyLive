import ctypes
import time
import subprocess

user32 = ctypes.windll.user32
hwnd = 197398

dossier_path = r"C:\Users\benny\Desktop\FAIRY LIVE\tools\m13_final_submission_dossier.txt"
with open(dossier_path, "r", encoding="utf-8") as f:
    dossier_content = f.read()

full_msg = (
    "Here is the complete Milestone 13 Final Submission Dossier from Hermes Desktop following the completion "
    "of the 30-minute continuous RTMP stream soak test, full regressions, documentation sync, and Git checkpoint:\n\n"
    + dossier_content + "\n\n"
    "Please review this dossier and provide the official gate decision (🟢 M13 APPROVED — M14 AUTHORIZED) "
    "and the directives/instructions for Milestone 14!"
)

# Copy to clipboard via PowerShell stdin
p = subprocess.Popen(
    ["powershell.exe", "-NoProfile", "-Command", "$val = [Console]::In.ReadToEnd(); Set-Clipboard -Value $val"],
    stdin=subprocess.PIPE,
    text=True,
    encoding="utf-8"
)
p.communicate(input=full_msg)
time.sleep(0.5)

# Activate Chrome
user32.ShowWindow(hwnd, 9)
user32.SetForegroundWindow(hwnd)
time.sleep(0.5)

# Click directly inside 'Chat with ChatGPT' box at (1114, 995)
user32.SetCursorPos(1114, 995)
time.sleep(0.1)
user32.mouse_event(0x0002, 0, 0, 0, 0)
user32.mouse_event(0x0004, 0, 0, 0, 0)
time.sleep(0.3)

# Clear: Ctrl+A, Backspace
user32.keybd_event(0x11, 0, 0, 0)
user32.keybd_event(0x41, 0, 0, 0)
time.sleep(0.05)
user32.keybd_event(0x41, 0, 2, 0)
user32.keybd_event(0x11, 0, 2, 0)
time.sleep(0.1)

user32.keybd_event(0x08, 0, 0, 0)
time.sleep(0.05)
user32.keybd_event(0x08, 0, 2, 0)
time.sleep(0.2)

# Paste: Ctrl+V
user32.keybd_event(0x11, 0, 0, 0)
user32.keybd_event(0x56, 0, 0, 0)
time.sleep(0.1)
user32.keybd_event(0x56, 0, 2, 0)
user32.keybd_event(0x11, 0, 2, 0)
time.sleep(1.5)
print("Pasted dossier into ChatGPT prompt box!")

# Check UIAutomation to find Send button or invoke it
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
        Write-Output "NO_INVOKE"
    }
} else {
    Write-Output "SEND_NOT_FOUND"
}
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_click_send], capture_output=True, text=True)
print("Send result:", res.stdout.strip())

if "SEND_INVOKED" not in res.stdout:
    print("Clicking at (1479, 988)...")
    user32.SetCursorPos(1479, 988)
    time.sleep(0.1)
    user32.mouse_event(0x0002, 0, 0, 0, 0)
    user32.mouse_event(0x0004, 0, 0, 0, 0)
    time.sleep(0.3)
