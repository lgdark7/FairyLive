import subprocess

ps_code = r"""
Add-Type -AssemblyName UIAutomationClient
$root = [System.Windows.Automation.AutomationElement]::FromHandle(131862)

$btnCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Button)
$btns = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $btnCond)
foreach ($b in $btns) {
    if ($b.Current.Name -match 'Stop answering|Send prompt') {
        Write-Output "Button: '$($b.Current.Name)', Enabled: $($b.Current.IsEnabled)"
    }
}

$editCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ClassNameProperty, "ProseMirror")
$edit = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $editCond)
if ($edit) {
    $val = $edit.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern)
    if ($val) {
        Write-Output "ProseMirror Value length: $($val.Current.Value.Length)"
    } else {
        Write-Output "ProseMirror: $($edit.Current.Name)"
    }
}
"""

p = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True, text=True)
print(p.stdout)
