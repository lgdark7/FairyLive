import subprocess
import time

ps_poll = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
if (-not $proc) { exit 1 }

$root = [System.Windows.Automation.AutomationElement]::RootElement
$appCond = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ProcessIdProperty, $proc.Id
)
$appElem = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $appCond)
if (-not $appElem) { exit 1 }

$btnCond = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Button
)

# Wait 3 seconds for response generation to start
Start-Sleep -Seconds 3

for ($i = 0; $i -lt 60; $i++) {
    $buttons = $appElem.FindAll([System.Windows.Automation.TreeScope]::Descendants, $btnCond)
    $hasStop = $false
    foreach ($b in $buttons) {
        if ($b.Current.Name -eq "Stop") {
            $hasStop = $true
            break
        }
    }

    if ($hasStop) {
        Write-Host "ChatGPT still generating ($i)..."
        Start-Sleep -Seconds 3
    } else {
        Write-Host "ChatGPT finished generating!"
        break
    }
}
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_poll], capture_output=True, text=True)
print(res.stdout)
