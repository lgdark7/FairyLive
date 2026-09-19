import time
import subprocess

def main():
    report_path = "tools/stream_key_investigation_dossier_to_chatgpt.txt"
    with open(report_path, "r", encoding="utf-8") as f:
        text = f.read()

    # Copy to clipboard via PowerShell
    ps_copy = subprocess.Popen(["powershell", "-NoProfile", "-Command", "Set-Clipboard -Value ([Console]::In.ReadToEnd())"], stdin=subprocess.PIPE, text=True)
    ps_copy.communicate(text)
    print("Clipboard loaded with dossier length:", len(text))

    # Focus ChatGPT, paste clipboard, and invoke the Send button via UIAutomation
    ps_action = """
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName Microsoft.VisualBasic
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
if (-not $proc) {
    Write-Output "CHATGPT_PROCESS_NOT_FOUND"
    exit
}

[Microsoft.VisualBasic.Interaction]::AppActivate($proc.Id)
Start-Sleep -Milliseconds 600

# Send Ctrl+V to paste multi-line content into ProseMirror
[System.Windows.Forms.SendKeys]::SendWait("^v")
Start-Sleep -Milliseconds 1200

# Now locate the Send button via UIAutomation
$root = [System.Windows.Automation.AutomationElement]::FromHandle($proc.MainWindowHandle)
$btnCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, "Send")
$btn = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $btnCond)

if ($btn -and $btn.Current.IsEnabled) {
    $inv = $btn.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
    if ($inv) {
        $inv.Invoke()
        Write-Output "INVOKED_SEND_BUTTON"
    } else {
        Write-Output "INVOKE_PATTERN_NOT_FOUND"
        [System.Windows.Forms.SendKeys]::SendWait("{ENTER}")
    }
} else {
    Write-Output "SEND_BUTTON_NOT_ENABLED_OR_FOUND"
    [System.Windows.Forms.SendKeys]::SendWait("^{ENTER}")
}
"""

    res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_action], capture_output=True, text=True)
    print("UIAutomation result:", res.stdout.strip())
    if res.stderr.strip():
        print("UIAutomation stderr:", res.stderr.strip())

if __name__ == '__main__':
    main()
