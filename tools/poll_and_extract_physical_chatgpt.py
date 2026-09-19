import subprocess
import time

ps_poll = r"""
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
        if ($b.Current.Name -match 'Send prompt|Send message|Send') {
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

print("=== POLLING CHATGPT APP FOR RESPONSE ===")
for i in range(40):
    time.sleep(3)
    res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_poll], capture_output=True, text=True)
    status = res.stdout.strip()
    print(f"Poll #{i+1}: {status}")
    if "READY" in status:
        print("ChatGPT response generation complete!")
        break

ps_extract = r"""
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
if (-not $proc) { exit 1 }

$root = [System.Windows.Automation.AutomationElement]::RootElement
$appCond = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ProcessIdProperty, $proc.Id
)
$appElem = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $appCond)

$cond = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Text
)

$elements = $appElem.FindAll([System.Windows.Automation.TreeScope]::Descendants, $cond)

$fullText = ""
foreach ($el in $elements) {
    $val = $el.Current.Name
    if ($val -and $val.Length -gt 1) {
        $fullText += "$val`n"
    }
}

$outFile = "C:\Users\benny\Desktop\FAIRY LIVE\tools\chatgpt_physical_response.txt"
[System.IO.File]::WriteAllText($outFile, $fullText)
Write-Host "Wrote $($fullText.Length) characters to $outFile"
"""

time.sleep(2)
print("=== EXTRACTING RESPONSE ===")
res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_extract], capture_output=True, text=True)
print(res.stdout)
