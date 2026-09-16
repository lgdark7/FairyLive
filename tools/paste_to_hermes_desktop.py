import subprocess
import time

ps_code = r"""
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName Microsoft.VisualBasic

$proc = Get-Process electron | Where-Object { $_.MainWindowTitle -eq 'Hermes' } | Select-Object -First 1
if (-not $proc) {
    Write-Output "Hermes Desktop not found!"
    exit
}

[Microsoft.VisualBasic.Interaction]::AppActivate($proc.Id)
Start-Sleep -Milliseconds 500

$root = [System.Windows.Automation.AutomationElement]::FromHandle($proc.MainWindowHandle)

$editCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, "Message")
$edit = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $editCond)

if ($edit) {
    Write-Output "Found Hermes Desktop Message box: $($edit.Current.BoundingRectangle.ToString())"
    $rect = $edit.Current.BoundingRectangle
    $clickX = [int]($rect.X + 50)
    $clickY = [int]($rect.Y + $rect.Height / 2)
    
    Add-Type -MemberDefinition '[DllImport("user32.dll")] public static extern void mouse_event(int dwFlags, int dx, int dy, int dwData, int dwExtraInfo);' -Name Win32Mouse -Namespace Win32
    [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point($clickX, $clickY)
    Start-Sleep -Milliseconds 200
    [Win32.Win32Mouse]::mouse_event(0x02, 0, 0, 0, 0)
    Start-Sleep -Milliseconds 50
    [Win32.Win32Mouse]::mouse_event(0x04, 0, 0, 0, 0)
    Start-Sleep -Milliseconds 300
    
    # Read directive from file
    $content = Get-Content -Path "C:\Users\benny\Desktop\FAIRY LIVE\tools\chatgpt_m14_directive.txt" -Raw -Encoding UTF8
    Set-Clipboard -Value $content
    Start-Sleep -Milliseconds 300
    
    Write-Output "Pasting into Hermes Desktop Message box..."
    [System.Windows.Forms.SendKeys]::SendWait("^v")
    Start-Sleep -Milliseconds 1000
    
    Write-Output "Pasted successfully!"
} else {
    Write-Output "Could not find Message box!"
}
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True, text=True)
print(res.stdout)
if res.stderr:
    print("ERR:", res.stderr)
