import subprocess

ps_code = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName Microsoft.VisualBasic

$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
if (-not $proc) { exit }

[Microsoft.VisualBasic.Interaction]::AppActivate($proc.Id)
Start-Sleep -Milliseconds 400

$root = [System.Windows.Automation.AutomationElement]::FromHandle($proc.MainWindowHandle)
$editCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ClassNameProperty, "ProseMirror")
$edit = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $editCond)

if ($edit) {
    $rect = $edit.Current.BoundingRectangle
    Write-Output "Edit rect: X=$($rect.X), Y=$($rect.Y), W=$($rect.Width), H=$($rect.Height)"
    
    # Click inside the edit box
    Add-Type -MemberDefinition '[DllImport("user32.dll")] public static extern void mouse_event(int dwFlags, int dx, int dy, int dwData, int dwExtraInfo);' -Name Win32Mouse -Namespace Win32
    
    # Set cursor pos
    [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point([int]($rect.X + 50), [int]($rect.Y + 20))
    Start-Sleep -Milliseconds 200
    [Win32.Win32Mouse]::mouse_event(0x02, 0, 0, 0, 0) # left down
    Start-Sleep -Milliseconds 50
    [Win32.Win32Mouse]::mouse_event(0x04, 0, 0, 0, 0) # left up
    Start-Sleep -Milliseconds 300
    
    # Send Enter
    [System.Windows.Forms.SendKeys]::SendWait("{ENTER}")
    Write-Output "ENTER_SENT"
}
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True, text=True)
print(res.stdout)
if res.stderr:
    print("STDERR:", res.stderr)
