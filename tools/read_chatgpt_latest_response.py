import subprocess

ps_code = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
if (-not $proc) { exit }

$root = [System.Windows.Automation.AutomationElement]::FromHandle($proc.MainWindowHandle)
$condition = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Text)
$texts = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $condition)

Write-Output "TOTAL_TEXTS: $($texts.Count)"

# Output the latest 80 text elements
for ($i = [Math]::Max(0, $texts.Count - 80); $i -lt $texts.Count; $i++) {
    $elem = $texts[$i]
    $val = $elem.Current.Name
    if ($val -and $val.Length -gt 5) {
        Write-Output "--- [$i] ---"
        Write-Output $val
    }
}
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
out_text = res.stdout.decode("utf-8", errors="replace")
print(out_text)
