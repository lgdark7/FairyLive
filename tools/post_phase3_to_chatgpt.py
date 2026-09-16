import subprocess
import time
import ctypes

user32 = ctypes.windll.user32
hwnd = 132176

with open("tools/v11_phase3_dossier_to_chatgpt.txt", "r", encoding="utf-8") as f:
    dossier = f.read()

# Copy to clipboard via PowerShell
p = subprocess.Popen(
    ["powershell.exe", "-NoProfile", "-Command", "$val = [Console]::In.ReadToEnd(); Set-Clipboard -Value $val"],
    stdin=subprocess.PIPE,
    text=True,
    encoding="utf-8"
)
p.communicate(input=dossier)
time.sleep(0.5)

# Bring ChatGPT to front
user32.ShowWindow(hwnd, 9) # SW_RESTORE
time.sleep(0.3)
user32.SetForegroundWindow(hwnd)
time.sleep(0.5)

# Click inside ProseMirror at (1100, 888)
user32.SetCursorPos(1100, 888)
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
time.sleep(1.0)

print("Pasted. Now invoking Send button...")
ps_send = """
Add-Type -AssemblyName UIAutomationClient
$root = [System.Windows.Automation.AutomationElement]::FromHandle(132176)
$btnCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, "Send")
$btn = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $btnCond)
if ($btn) {
    Write-Output "Found Send button: Enabled=$($btn.Current.IsEnabled)"
    $inv = $btn.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
    if ($inv) {
        $inv.Invoke()
        Write-Output "INVOKED_SEND"
    } else {
        Write-Output "NO_INVOKE_PATTERN"
    }
} else {
    Write-Output "SEND_NOT_FOUND"
}
"""
res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_send], capture_output=True, text=True)
print(res.stdout)
