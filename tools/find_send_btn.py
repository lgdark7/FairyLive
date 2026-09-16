import subprocess

ps_code = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$root = [System.Windows.Automation.AutomationElement]::FromHandle(131862)
$btnCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Button)
$btns = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $btnCond)

for ($i = 0; $i -lt $btns.Count; $i++) {
    $b = $btns[$i]
    $r = $b.Current.BoundingRectangle
    if ($r.Y -gt 850) {
        Write-Output "Button [$i]: Name='$($b.Current.Name)' Id='$($b.Current.AutomationId)' Rect=($($r.X), $($r.Y), $($r.Width), $($r.Height))"
    }
}
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True)
print(res.stdout.decode('utf-8', errors='replace'))
