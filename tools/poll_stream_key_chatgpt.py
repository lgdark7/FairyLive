import subprocess
import time

ps_poll = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
if (-not $proc) {
    Write-Output "NO_CHATGPT"
    exit
}

$root = [System.Windows.Automation.AutomationElement]::FromHandle($proc.MainWindowHandle)

# Check if generating
$stopCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, "Stop streaming")
$stopBtn = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $stopCond)
if ($stopBtn) {
    Write-Output "STREAMING"
    exit
}

# Also check for Stop button
$stopCond2 = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, "Stop generating")
$stopBtn2 = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $stopCond2)
if ($stopBtn2) {
    Write-Output "STREAMING"
    exit
}

# Find all documents / text
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
    [System.IO.File]::WriteAllText("tools/chatgpt_stream_key_response.txt", $allText, [System.Text.Encoding]::UTF8)
    Write-Output "DONE length=$($allText.Length)"
} else {
    Write-Output "EMPTY"
}
"""

def poll():
    for attempt in range(1, 30):
        res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_poll], capture_output=True, text=True)
        out = res.stdout.strip()
        print(f"Attempt {attempt}: {out}")
        if out.startswith("DONE"):
            return True
        time.sleep(4)
    return False

if __name__ == "__main__":
    poll()
