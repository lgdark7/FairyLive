import subprocess

ps_dump = r"""
Add-Type -AssemblyName UIAutomationClient
$root = [System.Windows.Automation.AutomationElement]::FromHandle(131862)
$textCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Text)
$texts = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $textCond)
Write-Output "TOTAL_TEXTS: $($texts.Count)"
for ($i = [Math]::Max(0, $texts.Count - 80); $i -lt $texts.Count; $i++) {
    $t = $texts[$i]
    $val = $t.Current.Name
    if ($val -and $val.Length -gt 15) {
        Write-Output "=== ITEM $i ==="
        Write-Output $val
    }
}
"""

p = subprocess.run(["powershell", "-NoProfile", "-Command", ps_dump], capture_output=True, text=True)
with open("tools/latest_screen_texts.txt", "w", encoding="utf-8") as f:
    f.write(p.stdout)
print("Dumped to tools/latest_screen_texts.txt")
