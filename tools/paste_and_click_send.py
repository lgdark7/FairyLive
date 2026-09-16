import subprocess
import time

report_path = "tools/m9_revalidation_report.txt"
with open(report_path, "r", encoding="utf-8") as f:
    text = f.read()

# Copy to clipboard via PowerShell
ps_copy = subprocess.Popen(["powershell", "-Command", "Set-Clipboard -Value ([Console]::In.ReadToEnd())"], stdin=subprocess.PIPE, text=True)
ps_copy.communicate(text)
print("Copied to clipboard, length:", len(text))

ps_code = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName Microsoft.VisualBasic
Add-Type -AssemblyName System.Drawing
Add-Type -MemberDefinition '[DllImport("user32.dll")] public static extern void mouse_event(int dwFlags, int dx, int dy, int dwData, int dwExtraInfo); [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);' -Name Win32 -Namespace Win32

$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
if (-not $proc) { exit }

[Win32.Win32]::SetForegroundWindow($proc.MainWindowHandle)
Start-Sleep -Milliseconds 400

# Send Ctrl+V
Write-Output "Sending Ctrl+V..."
[System.Windows.Forms.SendKeys]::SendWait("^v")
Start-Sleep -Milliseconds 1500

$root = [System.Windows.Automation.AutomationElement]::FromHandle($proc.MainWindowHandle)
$btnCond = New-Object System.Windows.Automation.AndCondition(
    (New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Button)),
    (New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, "Send"))
)
$btn = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $btnCond)

if ($btn) {
    Write-Output "Send button IsEnabled: $($btn.Current.IsEnabled)"
    if ($btn.Current.IsEnabled) {
        $rect = $btn.Current.BoundingRectangle
        $clickX = [int]($rect.X + $rect.Width / 2)
        $clickY = [int]($rect.Y + $rect.Height / 2)
        Write-Output "Clicking Send button at X=$clickX, Y=$clickY..."
        [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point($clickX, $clickY)
        Start-Sleep -Milliseconds 100
        [Win32.Win32]::mouse_event(0x02, 0, 0, 0, 0)
        Start-Sleep -Milliseconds 50
        [Win32.Win32]::mouse_event(0x04, 0, 0, 0, 0)
        Write-Output "CLICKED_SEND"
    } else {
        Write-Output "Send button is NOT enabled. Pressing Enter..."
        [System.Windows.Forms.SendKeys]::SendWait("{ENTER}")
    }
} else {
    Write-Output "Send button not found!"
}
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True, text=True)
print(res.stdout)
if res.stderr:
    print("STDERR:", res.stderr)
