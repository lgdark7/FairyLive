import subprocess
import time

ps_poll = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -ne "" } | Select-Object -First 1
if (-not $proc) { exit 1 }

$root = [System.Windows.Automation.AutomationElement]::FromHandle($proc.MainWindowHandle)

# Poll until Stop button disappears and Send button returns
$stopCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, "Stop")
$sendCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, "Send")

for ($i = 0; $i -lt 60; $i++) {
    $stop = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $stopCond)
    if ($stop) {
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
