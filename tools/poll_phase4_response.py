import subprocess
import time
import os

ps_script = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
$root = [System.Windows.Automation.AutomationElement]::FromHandle(132176)

# Check if Stop Streaming button exists
$stopCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, "Stop streaming")
$stopBtn = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $stopCond)
if ($stopBtn) {
    Write-Output "GENERATING"
    exit 0
}

# Find all text/document elements or recent messages
$docCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Document)
$docs = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $docCond)

$allText = ""
foreach ($d in $docs) {
    $textPat = $d.GetCurrentPattern([System.Windows.Automation.TextPattern]::Pattern)
    if ($textPat) {
        $allText += $textPat.DocumentRange.GetText(-1)
    }
}

if ($allText.Length -gt 0) {
    [System.IO.File]::WriteAllText("tools/chatgpt_latest_response.txt", $allText, [System.Text.Encoding]::UTF8)
    Write-Output "COMPLETE Length: $($allText.Length)"
} else {
    Write-Output "NO_TEXT"
}
"""

print("Polling ChatGPT for completion...")
for i in range(30):
    res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_script], capture_output=True, text=True)
    out = res.stdout.strip()
    print(f"Poll {i+1}: {out}")
    if "GENERATING" in out:
        time.sleep(3)
        continue
    elif "COMPLETE" in out:
        # Check if the text contains Phase 5 or next instructions
        if os.path.exists("tools/chatgpt_latest_response.txt"):
            with open("tools/chatgpt_latest_response.txt", "r", encoding="utf-8") as f:
                content = f.read()
            if "PHASE 5" in content.upper() or "PHASE 4" in content.upper() or "DIRECTIVE" in content.upper():
                print("Captured valid response with directive content!")
                break
    time.sleep(3)
