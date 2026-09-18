import subprocess
import time

ps_code = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName Microsoft.VisualBasic

$filePath = "C:\\Users\\benny\\Desktop\\FAIRY LIVE\\tools\\phase8_physical_parity_dossier_to_chatgpt.txt"
$text = [System.IO.File]::ReadAllText($filePath)

# Put text onto Windows clipboard
[System.Windows.Forms.Clipboard]::SetText($text)
Write-Host "Clipboard set with $($text.Length) characters"

$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
if (-not $proc) {
    Write-Error "ChatGPT process not found"
    exit 1
}

# Bring window to foreground
[Microsoft.VisualBasic.Interaction]::AppActivate($proc.Id)
Start-Sleep -Milliseconds 800

$root = [System.Windows.Automation.AutomationElement]::RootElement
$appCond = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ProcessIdProperty, $proc.Id
)
$appElem = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $appCond)
if (-not $appElem) {
    Write-Error "Could not find ChatGPT AutomationElement"
    exit 1
}

# Find ProseMirror editor using FindAll with retry
$editCond = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Edit
)

$editor = $null
for ($attempt = 0; $attempt -lt 5; $attempt++) {
    $edits = $appElem.FindAll([System.Windows.Automation.TreeScope]::Descendants, $editCond)
    if ($edits.Count -gt 0) {
        $editor = $edits[0]
        break
    }
    Start-Sleep -Milliseconds 500
}

if ($editor) {
    Write-Host "Editor found: '$($editor.Current.Name)' Class: '$($editor.Current.ClassName)'. Setting focus..."
    $editor.SetFocus()
    Start-Sleep -Milliseconds 300
    [System.Windows.Forms.SendKeys]::SendWait("^a")
    Start-Sleep -Milliseconds 150
    [System.Windows.Forms.SendKeys]::SendWait("^v")
    Start-Sleep -Milliseconds 1000
} else {
    Write-Error "Editor not found after retries!"
    exit 1
}

# Find Send button
$btnCond = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Button
)
$buttons = $appElem.FindAll([System.Windows.Automation.TreeScope]::Descendants, $btnCond)
$sendBtn = $null
foreach ($b in $buttons) {
    if ($b.Current.Name -eq "Send") {
        $sendBtn = $b
        break
    }
}

if ($sendBtn) {
    Write-Host "Send button found! IsEnabled = $($sendBtn.Current.IsEnabled)"
    if ($sendBtn.Current.IsEnabled) {
        $invoke = $sendBtn.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
        $invoke.Invoke()
        Write-Host "INVOKED_SEND_BUTTON_SUCCESSFULLY"
    } else {
        Write-Host "Send button disabled, pressing Enter..."
        [System.Windows.Forms.SendKeys]::SendWait("{ENTER}")
    }
} else {
    Write-Host "Send button not found, pressing Enter..."
    [System.Windows.Forms.SendKeys]::SendWait("{ENTER}")
}
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True, text=True)
print("STDOUT:", res.stdout)
print("STDERR:", res.stderr)
