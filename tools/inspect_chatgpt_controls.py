import subprocess

ps_code = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName Microsoft.VisualBasic

$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
if (-not $proc) {
    Write-Output "NO_CHATGPT"
    exit
}

$root = [System.Windows.Automation.AutomationElement]::FromHandle($proc.MainWindowHandle)

# Find Edit or Document controls
$condition = New-Object System.Windows.Automation.OrCondition(
    (New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Edit)),
    (New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Document))
)

$edits = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $condition)
Write-Output "Edits/Documents found: $($edits.Count)"
for ($i = 0; $i -lt $edits.Count; $i++) {
    $e = $edits[$i]
    Write-Output "Edit [$i]: Name='$($e.Current.Name)' Class='$($e.Current.ClassName)' Id='$($e.Current.AutomationId)'"
}

# Find Buttons
$btnCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Button)
$btns = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $btnCond)
Write-Output "Buttons found: $($btns.Count)"
for ($i = [Math]::Max(0, $btns.Count - 15); $i -lt $btns.Count; $i++) {
    $b = $btns[$i]
    Write-Output "Button [$i]: Name='$($b.Current.Name)' Id='$($b.Current.AutomationId)'"
}
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True, text=True)
print(res.stdout)
