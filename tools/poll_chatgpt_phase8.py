import subprocess
import time

ps_code = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
if (-not $proc) {
    Write-Output "NO_CHATGPT"
    exit
}

$root = [System.Windows.Automation.AutomationElement]::RootElement
$appCond = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ProcessIdProperty, $proc.Id
)
$appElem = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $appCond)

$btnCond = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Button
)
$buttons = $appElem.FindAll([System.Windows.Automation.TreeScope]::Descendants, $btnCond)

$streaming = $false
$hasSend = $false
foreach ($b in $buttons) {
    if ($b.Current.Name -match "Stop streaming|Stop generating") {
        $streaming = $true
    }
    if ($b.Current.Name -eq "Send") {
        $hasSend = $true
    }
}

if ($streaming) {
    Write-Output "GENERATING"
} elseif ($hasSend) {
    Write-Output "READY"
} else {
    Write-Output "UNKNOWN"
}
"""

for attempt in range(60):
    res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True, text=True)
    status = res.stdout.strip()
    print(f"Poll #{attempt+1}: {status}")
    if status == "READY":
        print("ChatGPT response is ready!")
        break
    time.sleep(3)
