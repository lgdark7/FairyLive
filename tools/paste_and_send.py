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

$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
if (-not $proc) { exit }

[Microsoft.VisualBasic.Interaction]::AppActivate($proc.Id)
Start-Sleep -Milliseconds 500

$root = [System.Windows.Automation.AutomationElement]::FromHandle($proc.MainWindowHandle)
$editCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ClassNameProperty, "ProseMirror")
$edit = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $editCond)

if ($edit) {
    $rect = $edit.Current.BoundingRectangle
    Write-Output "Edit rect: X=$($rect.X), Y=$($rect.Y), W=$($rect.Width), H=$($rect.Height)"
    
    Add-Type -MemberDefinition '[DllImport("user32.dll")] public static extern void mouse_event(int dwFlags, int dx, int dy, int dwData, int dwExtraInfo);' -Name Win32Mouse -Namespace Win32
    
    # Click inside the edit box
    $clickX = [int]($rect.X + 60)
    $clickY = [int]($rect.Y + 20)
    [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point($clickX, $clickY)
    Start-Sleep -Milliseconds 200
    [Win32.Win32Mouse]::mouse_event(0x02, 0, 0, 0, 0) # left down
    Start-Sleep -Milliseconds 50
    [Win32.Win32Mouse]::mouse_event(0x04, 0, 0, 0, 0) # left up
    Start-Sleep -Milliseconds 500
    
    Write-Output "Pasting..."
    [System.Windows.Forms.SendKeys]::SendWait("^v")
    Start-Sleep -Milliseconds 1500
    
    Write-Output "Sending Enter..."
    [System.Windows.Forms.SendKeys]::SendWait("{ENTER}")
    Write-Output "DONE"
}
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True, text=True)
print(res.stdout)
if res.stderr:
    print("STDERR:", res.stderr)
