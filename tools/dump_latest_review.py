import subprocess

ps_code = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
$root = [System.Windows.Automation.AutomationElement]::FromHandle($proc.MainWindowHandle)
$condition = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Text)
$texts = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $condition)

for ($i = 210; $i -lt $texts.Count; $i++) {
    $val = $texts[$i].Current.Name
    if ($val) {
        Write-Output "[$i] $val"
    }
}
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True, text=True)
with open("tools/chatgpt_m9_latest_review.txt", "w", encoding="utf-8") as f:
    f.write(res.stdout)

print("Saved", len(res.stdout), "chars to tools/chatgpt_m9_latest_review.txt")
