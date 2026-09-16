import subprocess
import os

ps_dump = """
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName UIAutomationClient
$root = [System.Windows.Automation.AutomationElement]::FromHandle(131862)
$textCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Text)
$texts = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $textCond)
Write-Output "TOTAL_TEXTS: $($texts.Count)"
$start = [Math]::Max(0, $texts.Count - 350)
for ($i = $start; $i -lt $texts.Count; $i++) {
    Write-Output "=== ITEM $i ==="
    Write-Output ($texts[$i].Current.Name)
}
"""

p = subprocess.run(["powershell", "-NoProfile", "-Command", ps_dump], capture_output=True)
out = p.stdout.decode('utf-8', errors='replace')
with open("tools/chatgpt_m12_review.txt", "w", encoding="utf-8") as f:
    f.write(out)

print(f"Dumped {len(out)} chars to tools/chatgpt_m12_review.txt")
