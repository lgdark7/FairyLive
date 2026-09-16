import subprocess

ps_code = r"""
Add-Type -AssemblyName UIAutomationClient
$root = [System.Windows.Automation.AutomationElement]::FromHandle(131862)
$tabCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::TabItem)
$tabs = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $tabCond)
Write-Output "Found $($tabs.Count) tabs:"
for ($i = 0; $i -lt $tabs.Count; $i++) {
    $t = $tabs[$i]
    Write-Output "Tab $i: '$($t.Current.Name)'"
}
"""

p = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True, text=True)
print(p.stdout)
