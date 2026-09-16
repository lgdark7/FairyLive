import subprocess
import time

ps_code = r"""
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName Microsoft.VisualBasic

$proc = Get-Process chrome | Where-Object { $_.MainWindowTitle -match 'FAIRY LIVE PROJECT' } | Select-Object -First 1
if (-not $proc) {
    Write-Output "Chrome not found!"
    exit
}

[Microsoft.VisualBasic.Interaction]::AppActivate($proc.Id)
Start-Sleep -Milliseconds 500

$root = [System.Windows.Automation.AutomationElement]::FromHandle($proc.MainWindowHandle)

# Find ProseMirror
$editCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ClassNameProperty, "ProseMirror")
$edit = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $editCond)
if (-not $edit) {
    $editCond2 = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, "Chat with ChatGPT")
    $edit = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $editCond2)
}

if ($edit) {
    Write-Output "Found edit box: $($edit.Current.Name)"
    $rect = $edit.Current.BoundingRectangle
    $clickX = [int]($rect.X + [Math]::Min(100, $rect.Width / 2))
    $clickY = [int]($rect.Y + $rect.Height / 2)
    
    Add-Type -MemberDefinition '[DllImport("user32.dll")] public static extern void mouse_event(int dwFlags, int dx, int dy, int dwData, int dwExtraInfo);' -Name Win32Mouse -Namespace Win32
    [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point($clickX, $clickY)
    Start-Sleep -Milliseconds 200
    [Win32.Win32Mouse]::mouse_event(0x02, 0, 0, 0, 0)
    Start-Sleep -Milliseconds 50
    [Win32.Win32Mouse]::mouse_event(0x04, 0, 0, 0, 0)
    Start-Sleep -Milliseconds 300
    
    # Set clipboard text
    $msg = "Understood. The 30-minute soak test is actively running now. In the meantime, please provide the full specifications, technical requirements, acceptance criteria, and implementation prompt for Milestone 14 (the next milestone) so we are fully prepared to review and implement it once M13 is officially closed."
    Set-Clipboard -Value $msg
    Start-Sleep -Milliseconds 300
    
    Write-Output "Pasting..."
    [System.Windows.Forms.SendKeys]::SendWait("^a")
    Start-Sleep -Milliseconds 100
    [System.Windows.Forms.SendKeys]::SendWait("{BACKSPACE}")
    Start-Sleep -Milliseconds 100
    [System.Windows.Forms.SendKeys]::SendWait("^v")
    Start-Sleep -Milliseconds 1000
    
    # Click Send button
    $btnCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Button)
    $btns = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $btnCond)
    foreach ($b in $btns) {
        if ($b.Current.Name -match 'Send prompt|Send') {
            Write-Output "Found button: $($b.Current.Name)"
            $inv = $b.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
            if ($inv) {
                $inv.Invoke()
                Write-Output "INVOKED Send prompt button!"
                exit
            }
        }
    }
    
    Write-Output "Fallback Send via Enter..."
    [System.Windows.Forms.SendKeys]::SendWait("{ENTER}")
} else {
    Write-Output "Could not find edit box!"
}
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True, text=True)
print(res.stdout)
if res.stderr:
    print("ERR:", res.stderr)
