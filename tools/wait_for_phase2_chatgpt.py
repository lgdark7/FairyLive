import subprocess
import time

ps_check = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
if (-not $proc) {
    Write-Output "NO_PROC"
    exit
}

$root = [System.Windows.Automation.AutomationElement]::FromHandle($proc.MainWindowHandle)
$cond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Button)
$btns = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $cond)

$hasStop = $false
foreach ($b in $btns) {
    if ($b.Current.Name -eq "Stop" -and $b.Current.IsEnabled) {
        $hasStop = $true
        break
    }
}

if ($hasStop) {
    Write-Output "GENERATING"
} else {
    $docCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Document)
    $docs = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $docCond)
    $allText = ""
    foreach ($d in $docs) {
        $textPat = $d.GetCurrentPattern([System.Windows.Automation.TextPattern]::Pattern)
        if ($textPat) {
            $allText += $textPat.DocumentRange.GetText(-1)
        }
    }
    [System.IO.File]::WriteAllText("tools/chatgpt_phase2_response.txt", $allText, [System.Text.Encoding]::UTF8)
    Write-Output "FINISHED length=$($allText.Length)"
}
"""

def main():
    print("Waiting for ChatGPT to start and complete response...")
    time.sleep(4)
    for i in range(1, 45):
        res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_check], capture_output=True, text=True)
        out = res.stdout.strip()
        print(f"Check {i}: {out}")
        if out.startswith("FINISHED"):
            time.sleep(2)
            res2 = subprocess.run(["powershell", "-NoProfile", "-Command", ps_check], capture_output=True, text=True)
            print("ChatGPT completed response!")
            break
        time.sleep(4)

if __name__ == '__main__':
    main()
