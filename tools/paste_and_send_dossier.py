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

# Put full_msg on clipboard
p = subprocess.Popen(
    ["powershell.exe", "-NoProfile", "-Command", "$val = [Console]::In.ReadToEnd(); Set-Clipboard -Value $val"],
    stdin=subprocess.PIPE,
    text=True,
    encoding="utf-8"
)
p.communicate(input=full_msg)
time.sleep(0.5)

# Bring Chrome to front
user32.ShowWindow(hwnd, 9)
user32.SetForegroundWindow(hwnd)
time.sleep(0.5)

# Click inside 'Chat with ChatGPT' at (1114, 995)
user32.SetCursorPos(1114, 995)
time.sleep(0.1)
user32.mouse_event(0x0002, 0, 0, 0, 0)
user32.mouse_event(0x0004, 0, 0, 0, 0)
time.sleep(0.3)

# Select all (Ctrl+A) and Paste (Ctrl+V)
user32.keybd_event(0x11, 0, 0, 0)
user32.keybd_event(0x41, 0, 0, 0)
time.sleep(0.05)
user32.keybd_event(0x41, 0, 2, 0)
user32.keybd_event(0x11, 0, 2, 0)
time.sleep(0.1)

user32.keybd_event(0x11, 0, 0, 0)
user32.keybd_event(0x56, 0, 0, 0)
time.sleep(0.1)
user32.keybd_event(0x56, 0, 2, 0)
user32.keybd_event(0x11, 0, 2, 0)
time.sleep(1.5)

print("Pasted dossier. Checking length via UIA...")

# Check length & click Send prompt via UIA
ps_send = """
Add-Type -AssemblyName UIAutomationClient
$root = [System.Windows.Automation.AutomationElement]::FromHandle(197398)

$editCond = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::NameProperty,
    'Chat with ChatGPT'
)
$elem = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $editCond)
if ($elem) {
    $val = $elem.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern).Current.Value
    Write-Output "Box length: $($val.Length)"
}

$btnCond = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::NameProperty,
    'Send prompt'
)
$btn = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $btnCond)
if ($btn) {
    Write-Output "Found Send prompt button at $($btn.Current.BoundingRectangle.ToString())"
    $inv = $btn.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
    if ($inv) {
        $inv.Invoke()
        Write-Output "INVOKED_SEND_PROMPT"
    } else {
        Write-Output "NO_INVOKE_PATTERN"
    }
} else {
    Write-Output "SEND_PROMPT_NOT_FOUND"
}
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_send], capture_output=True, text=True)
print(res.stdout)
