import subprocess
import time
import sys

ps_check = """
Add-Type -AssemblyName UIAutomationClient
$root = [System.Windows.Automation.AutomationElement]::FromHandle(132176)
$btnCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Button)
$btns = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $btnCond)
foreach ($b in $btns) {
    if ($b.Current.Name -match "Stop generating|Stop answering") {
        Write-Output "GENERATING"
        exit
    }
}
Write-Output "IDLE"
"""

print("Polling ChatGPT until response generation completes...")
for attempt in range(40):
    time.sleep(3.0)
    res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_check], capture_output=True, text=True)
    out = res.stdout.strip()
    if "GENERATING" in out:
        print(f"[{attempt+1}] ChatGPT is generating response...")
    else:
        print("ChatGPT is done generating!")
        break

time.sleep(2.0)

# Dump latest response texts
ps_dump = """
Add-Type -AssemblyName UIAutomationClient
$root = [System.Windows.Automation.AutomationElement]::FromHandle(132176)
$textCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Text)
$texts = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $textCond)
Write-Output "TOTAL_TEXTS: $($texts.Count)"
for ($i = [Math]::Max(0, $texts.Count - 60); $i -lt $texts.Count; $i++) {
    $t = $texts[$i]
    $val = $t.Current.Name
    if ($val -and $val.Length -gt 15) {
        Write-Output "=== [$i] ==="
        Write-Output $val
    }
}
"""
res2 = subprocess.run(["powershell", "-NoProfile", "-Command", ps_dump], capture_output=True, text=True)
content = res2.stdout
with open("tools/chatgpt_phase3_response.txt", "w", encoding="utf-8") as f:
    f.write(content)

print(f"Captured {len(content)} chars of response. Written to tools/chatgpt_phase3_response.txt")
