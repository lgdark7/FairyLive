import subprocess
import time

report_path = "tools/m15d_creator_studio_report.txt"
with open(report_path, "r", encoding="utf-8") as f:
    text = f.read()

# Copy report to clipboard
ps_copy = subprocess.Popen(["powershell", "-Command", "Set-Clipboard -Value ([Console]::In.ReadToEnd())"], stdin=subprocess.PIPE, text=True)
ps_copy.communicate(text)
print("Report copied to clipboard, length:", len(text))

ps_code = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName Microsoft.VisualBasic
Add-Type -AssemblyName System.Drawing
Add-Type -MemberDefinition @\"
[DllImport("user32.dll")] public static extern void mouse_event(int dwFlags, int dx, int dy, int dwData, int dwExtraInfo);
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
\"@ -Name Win32 -Namespace Win32

$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match "ChatGPT" } | Select-Object -First 1
if (-not $proc) { Write-Output "CHATGPT_NOT_RUNNING"; exit }

[Win32.Win32]::SetForegroundWindow($proc.MainWindowHandle)
Start-Sleep -Milliseconds 500

$root = [System.Windows.Automation.AutomationElement]::FromHandle($proc.MainWindowHandle)

# 1. Find ProseMirror edit box
$editCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ClassNameProperty, "ProseMirror")
$edit = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $editCond)

if ($edit) {
    $rect = $edit.Current.BoundingRectangle
    Write-Output "Found ProseMirror at X=$($rect.X), Y=$($rect.Y)"
    [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point([int]($rect.X + 50), [int]($rect.Y + 20))
    Start-Sleep -Milliseconds 150
    [Win32.Win32]::mouse_event(0x02, 0, 0, 0, 0)
    Start-Sleep -Milliseconds 50
    [Win32.Win32]::mouse_event(0x04, 0, 0, 0, 0)
    Start-Sleep -Milliseconds 300
} else {
    Write-Output "ProseMirror not found, attempting generic edit control"
    $genericEdit = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, (New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Edit)))
    if ($genericEdit) {
        $rect = $genericEdit.Current.BoundingRectangle
        [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point([int]($rect.X + 50), [int]($rect.Y + 20))
        Start-Sleep -Milliseconds 150
        [Win32.Win32]::mouse_event(0x02, 0, 0, 0, 0)
        Start-Sleep -Milliseconds 50
        [Win32.Win32]::mouse_event(0x04, 0, 0, 0, 0)
        Start-Sleep -Milliseconds 300
    }
}

# 2. Paste clipboard
Write-Output "Sending Ctrl+V..."
[System.Windows.Forms.SendKeys]::SendWait("^v")
Start-Sleep -Milliseconds 1500

# 3. Find and click Send button
$btnCond = New-Object System.Windows.Automation.AndCondition(
    (New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Button)),
    (New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, "Send"))
)
$btn = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $btnCond)

if ($btn) {
    Write-Output "Send button found. IsEnabled: $($btn.Current.IsEnabled)"
    $retry = 0
    while (-not $btn.Current.IsEnabled -and $retry -lt 10) {
        Start-Sleep -Milliseconds 200
        $retry++
    }
    if ($btn.Current.IsEnabled) {
        $bRect = $btn.Current.BoundingRectangle
        $clickX = [int]($bRect.X + $bRect.Width / 2)
        $clickY = [int]($bRect.Y + $bRect.Height / 2)
        Write-Output "Clicking Send button at X=$clickX, Y=$clickY..."
        [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point($clickX, $clickY)
        Start-Sleep -Milliseconds 150
        [Win32.Win32]::mouse_event(0x02, 0, 0, 0, 0)
        Start-Sleep -Milliseconds 50
        [Win32.Win32]::mouse_event(0x04, 0, 0, 0, 0)
        Write-Output "CLICKED_SEND_SUCCESS"
    } else {
        Write-Output "Pressing Enter as fallback..."
        [System.Windows.Forms.SendKeys]::SendWait("{ENTER}")
        Write-Output "ENTER_SENT"
    }
} else {
    Write-Output "Send button not found, pressing Enter..."
    [System.Windows.Forms.SendKeys]::SendWait("{ENTER}")
    Write-Output "ENTER_SENT"
}
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True, text=True)
print(res.stdout)
if res.stderr:
    print("STDERR:", res.stderr)
