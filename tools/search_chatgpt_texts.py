import subprocess

ps_code = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
$root = [System.Windows.Automation.AutomationElement]::FromHandle($proc.MainWindowHandle)
$condition = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Text)
$texts = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $condition)

Write-Output "TOTAL_TEXTS: $($texts.Count)"

for ($i = 0; $i -lt $texts.Count; $i++) {
    $val = $texts[$i].Current.Name
    if ($val -and ($val -match 'REVALIDATION' -or $val -match 'M9 APPROVED' -or $val -match 'Pasted text' -or $val -match '10-Cycle')) {
        Write-Output "MATCH [$i]: $val"
    }
}
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True, text=True)
print(res.stdout)
