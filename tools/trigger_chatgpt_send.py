import subprocess
import time

ps_code = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName Microsoft.VisualBasic

$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
if (-not $proc) { exit }

[Microsoft.VisualBasic.Interaction]::AppActivate($proc.Id)
Start-Sleep -Milliseconds 500

$root = [System.Windows.Automation.AutomationElement]::FromHandle($proc.MainWindowHandle)
$editCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ClassNameProperty, "ProseMirror")
$edit = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $editCond)

if ($edit) {
    $rect = $edit.Current.BoundingRectangle
    Add-Type -MemberDefinition '[DllImport("user32.dll")] public static extern void mouse_event(int dwFlags, int dx, int dy, int dwData, int dwExtraInfo);' -Name Win32Mouse -Namespace Win32
    
    [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point([int]($rect.X + 30), [int]($rect.Y + 10))
    Start-Sleep -Milliseconds 100
    [Win32.Win32Mouse]::mouse_event(0x02, 0, 0, 0, 0)
    Start-Sleep -Milliseconds 50
    [Win32.Win32Mouse]::mouse_event(0x04, 0, 0, 0, 0)
    Start-Sleep -Milliseconds 200

    # Type a space and backspace to trigger input dirty event
    [System.Windows.Forms.SendKeys]::SendWait(" ")
    Start-Sleep -Milliseconds 100
    [System.Windows.Forms.SendKeys]::SendWait("{BACKSPACE}")
    Start-Sleep -Milliseconds 300

    $btnCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, "Send")
    $btn = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $btnCond)
    if ($btn) {
        Write-Output "Send button IsEnabled after touch: $($btn.Current.IsEnabled)"
        if ($btn.Current.IsEnabled) {
            $inv = $btn.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
            if ($inv) {
                $inv.Invoke()
                Write-Output "CLICKED_SEND_BUTTON!"
            }
        }
    }
}
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True, text=True)
print(res.stdout)
if res.stderr:
    print("Stderr:", res.stderr)
