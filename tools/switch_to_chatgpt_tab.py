import subprocess

ps_code = """
Add-Type -AssemblyName UIAutomationClient
$root = [System.Windows.Automation.AutomationElement]::FromHandle(131862)
$tabCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::TabItem)
$tabs = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $tabCond)
foreach ($t in $tabs) {
    if ($t.Current.Name -match 'FAIRY LIVE PROJECT') {
        Write-Output "Found tab: $($t.Current.Name)"
        $sel = $t.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern)
        if ($sel) {
            $sel.Select()
            Write-Output "Selected tab via SelectionItemPattern!"
            exit
        }
        $rect = $t.Current.BoundingRectangle
        Write-Output "Rect: $rect"
    }
}
"""

p = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True, text=True)
print(p.stdout)
