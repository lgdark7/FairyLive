import subprocess

ps_code = r"""
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$root = [System.Windows.Automation.AutomationElement]::FromHandle(131862)
$btnCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Button)
$btns = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $btnCond)
foreach ($b in $btns) {
    if ($b.Current.Name -match 'Send|Stop|Submit') {
        Write-Output "Button: '$($b.Current.Name)', Rect: $($b.Current.BoundingRectangle), Enabled: $($b.Current.IsEnabled)"
        if ($b.Current.Name -eq 'Send prompt') {
            $inv = $b.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
            if ($inv) {
                $inv.Invoke()
                Write-Output "INVOKED Send prompt!"
            }
        }
    }
}

$editCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ClassNameProperty, "ProseMirror")
$edit = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $editCond)
if ($edit) {
    Write-Output "ProseMirror: Name='$($edit.Current.Name)'"
}
"""

p = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True, text=True)
print(p.stdout)
