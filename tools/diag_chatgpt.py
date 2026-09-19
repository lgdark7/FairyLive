import subprocess

ps_diag = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
if (-not $proc) {
    Write-Host "No ChatGPT proc"
    exit 1
}
Write-Host "Found proc Id=$($proc.Id) Title=$($proc.MainWindowTitle)"

$root = [System.Windows.Automation.AutomationElement]::RootElement
$appCond = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ProcessIdProperty, $proc.Id
)
$appElem = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $appCond)
if (-not $appElem) {
    Write-Host "No appElem"
    exit 1
}

$all = $appElem.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
Write-Host "Total elements: $($all.Count)"

$edits = $appElem.FindAll([System.Windows.Automation.TreeScope]::Descendants, (New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Edit)))
Write-Host "Edit elements: $($edits.Count)"
foreach ($e in $edits) {
    Write-Host "Edit Name: '$($e.Current.Name)' Class: '$($e.Current.ClassName)'"
}

$docs = $appElem.FindAll([System.Windows.Automation.TreeScope]::Descendants, (New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Document)))
Write-Host "Doc elements: $($docs.Count)"
foreach ($d in $docs) {
    Write-Host "Doc Name: '$($d.Current.Name)' Class: '$($d.Current.ClassName)'"
}
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_diag], capture_output=True, text=True)
print(res.stdout)
print(res.stderr)
