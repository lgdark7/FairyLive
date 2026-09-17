import subprocess

ps_extract = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
if (-not $proc) {
    Write-Output "NO_PROC"
    exit
}

$root = [System.Windows.Automation.AutomationElement]::RootElement
$appCond = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ProcessIdProperty, $proc.Id
)
$appElem = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $appCond)
if (-not $appElem) {
    Write-Output "NO_APP_ELEM"
    exit
}

$docCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Document)
$docs = $appElem.FindAll([System.Windows.Automation.TreeScope]::Descendants, $docCond)
$allText = ""
foreach ($d in $docs) {
    $textPat = $d.GetCurrentPattern([System.Windows.Automation.TextPattern]::Pattern)
    if ($textPat) {
        $allText += $textPat.DocumentRange.GetText(-1)
    }
}
[System.IO.File]::WriteAllText("tools/chatgpt_phase6_response.txt", $allText, [System.Text.Encoding]::UTF8)
Write-Output "FINISHED length=$($allText.Length)"
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_extract], capture_output=True, text=True)
print(res.stdout)
