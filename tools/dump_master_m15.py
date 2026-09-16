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

# Find start of latest user message or latest prompt
for ($i = [Math]::Max(0, $texts.Count - 600); $i -lt $texts.Count; $i++) {
    $elem = $texts[$i]
    $val = $elem.Current.Name
    if ($val -and $val.Length -gt 1) {
        Write-Output "[$i] $val"
    }
}
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True, text=True)
with open("tools/chatgpt_master_m15_prompt.txt", "w", encoding="utf-8") as f:
    f.write(res.stdout)
print("Saved to tools/chatgpt_master_m15_prompt.txt, length:", len(res.stdout))
