import subprocess
import time

ps_code = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName Microsoft.VisualBasic

$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
if (-not $proc) {
    Write-Output "ChatGPT not found"
    exit
}

# 1. Set clipboard
$dossier = Get-Content -Path 'tools/phase2_overlay_stability_dossier_to_chatgpt.txt' -Raw -Encoding UTF8
[System.Windows.Forms.Clipboard]::SetText($dossier)
Write-Output "Clipboard set with $($dossier.Length) characters"

# 2. Activate ChatGPT
[Microsoft.VisualBasic.Interaction]::AppActivate($proc.Id)
Start-Sleep -Milliseconds 500

$root = [System.Windows.Automation.AutomationElement]::FromHandle($proc.MainWindowHandle)
$editCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ClassNameProperty, "ProseMirror")
$edit = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $editCond)

if ($edit) {
    # Set focus on ProseMirror edit box
    $edit.SetFocus()
    Start-Sleep -Milliseconds 300

    # Select all and paste
    [System.Windows.Forms.SendKeys]::SendWait("^a")
    Start-Sleep -Milliseconds 150
    [System.Windows.Forms.SendKeys]::SendWait("^v")
    Start-Sleep -Milliseconds 1500

    # Check Send button
    $btnCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, "Send")
    $btn = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $btnCond)

    if ($btn -and $btn.Current.IsEnabled) {
        Write-Output "Send button is ENABLED! Clicking Send button..."
        $inv = $btn.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
        $inv.Invoke()
        Write-Output "INVOKED_SEND_BUTTON_SUCCESSFULLY"
    } else {
        Write-Output "Send button not enabled via invoke, sending Enter..."
        [System.Windows.Forms.SendKeys]::SendWait("{ENTER}")
        Write-Output "SENT_ENTER"
    }
} else {
    Write-Output "Edit box not found"
}
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True, text=True)
print(res.stdout)
if res.stderr:
    print("Stderr:", res.stderr)
