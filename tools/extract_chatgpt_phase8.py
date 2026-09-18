import subprocess

ps_code = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
if (-not $proc) {
    Write-Error "ChatGPT process not found"
    exit 1
}

$root = [System.Windows.Automation.AutomationElement]::RootElement
$appCond = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ProcessIdProperty, $proc.Id
)
$appElem = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $appCond)

# Find all Text or Group control elements to extract conversation text
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

$outFile = "C:\\Users\\benny\\Desktop\\FAIRY LIVE\\tools\\chatgpt_phase8_response.txt"
[System.IO.File]::WriteAllText($outFile, $fullText)
Write-Host "Wrote $($fullText.Length) characters to $outFile"
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True, text=True)
print("STDOUT:", res.stdout)
print("STDERR:", res.stderr)
