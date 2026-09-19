import subprocess
import time

ps_code = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
if (-not $proc) { exit 1 }

$root = [System.Windows.Automation.AutomationElement]::RootElement
$cond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ProcessIdProperty, $proc.Id)
$appWindow = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $cond)

$btnCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Button)
$buttons = $appWindow.FindAll([System.Windows.Automation.TreeScope]::Descendants, $btnCond)

$isStreaming = $false
foreach ($b in $buttons) {
    if ($b.Current.Name -match 'Stop streaming|Stop generating') {
        $isStreaming = $true
        break
    }
}

if ($isStreaming) {
    Write-Host "STREAMING"
} else {
    $hasSend = $false
    foreach ($b in $buttons) {
        if ($b.Current.Name -match 'Send prompt|Send message') {
            $hasSend = $true
            break
        }
    }
    if ($hasSend) {
        Write-Host "READY"
    } else {
        Write-Host "UNKNOWN"
    }
}
"""

for i in range(30):
    time.sleep(3)
    res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True, text=True)
    status = res.stdout.strip()
    print(f"Poll #{i+1}: {status}")
    if status == "READY":
        print("ChatGPT response is ready!")
        break
