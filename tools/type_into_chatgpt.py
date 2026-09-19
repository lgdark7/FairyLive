import subprocess
import time

ps_code = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName Microsoft.VisualBasic

$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
[Microsoft.VisualBasic.Interaction]::AppActivate($proc.Id)
Start-Sleep -Milliseconds 500

$root = [System.Windows.Automation.AutomationElement]::FromHandle($proc.MainWindowHandle)
$editCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ClassNameProperty, "ProseMirror")
$edit = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $editCond)

if ($edit) {
    # Try ValuePattern
    $valPattern = $null
    try {
        $valPattern = $edit.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern)
    } catch {}

    if ($valPattern) {
        Write-Output "ValuePattern supported!"
    } else {
        Write-Output "ValuePattern NOT supported"
    }

    # Focus the element via UIAutomation
    $edit.SetFocus()
    Start-Sleep -Milliseconds 200

    # Let's type a character directly
    [System.Windows.Forms.SendKeys]::SendWait("Hello")
    Start-Sleep -Milliseconds 500

    $btnCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, "Send")
    $btn = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $btnCond)
    Write-Output "Send button IsEnabled: $($btn.Current.IsEnabled)"
}
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True, text=True)
print(res.stdout)
if res.stderr:
    print("Stderr:", res.stderr)
