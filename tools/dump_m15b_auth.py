import subprocess

ps_code = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
if (-not $proc) { exit }

$root = [System.Windows.Automation.AutomationElement]::FromHandle($proc.MainWindowHandle)
$condition = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Text)
$texts = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $condition)

for ($i = [Math]::Max(0, $texts.Count - 160); $i -lt $texts.Count; $i++) {
    $elem = $texts[$i]
    $val = $elem.Current.Name
    if ($val -and $val.Length -gt 1) {
        Write-Output $val
    }
}
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True, text=True)
with open("tools/chatgpt_m15b_auth.txt", "w", encoding="utf-8") as f:
    f.write(res.stdout)
print("Wrote chatgpt_m15b_auth.txt")