import subprocess

ps_code = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$root = [System.Windows.Automation.AutomationElement]::FromHandle(131862)
if (-not $root) {
    Write-Output "NO_ROOT"
    exit
}

# Find Edit or Document controls
$condition = New-Object System.Windows.Automation.OrCondition(
    (New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Edit)),
    (New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Document))
)

$edits = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $condition)
Write-Output "Edits/Documents count: $($edits.Count)"
for ($i = 0; $i -lt $edits.Count; $i++) {
    $e = $edits[$i]
    $rect = $e.Current.BoundingRectangle
    Write-Output "Edit [$i]: Name='$($e.Current.Name)' Class='$($e.Current.ClassName)' Rect=($($rect.X), $($rect.Y), $($rect.Width), $($rect.Height))"
}

# Find Buttons
$btnCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Button)
$btns = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $btnCond)
Write-Output "Buttons count: $($btns.Count)"
for ($i = [Math]::Max(0, $btns.Count - 20); $i -lt $btns.Count; $i++) {
    $b = $btns[$i]
    $rect = $b.Current.BoundingRectangle
    Write-Output "Button [$i]: Name='$($b.Current.Name)' Id='$($b.Current.AutomationId)' Rect=($($rect.X), $($rect.Y), $($rect.Width), $($rect.Height))"
}
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True, text=True)
print(res.stdout)
