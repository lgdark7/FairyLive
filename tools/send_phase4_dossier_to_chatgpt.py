import subprocess
import time

ps_code = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName Microsoft.VisualBasic

$filePath = "C:\\Users\\benny\\Desktop\\FAIRY LIVE\\tools\\phase4_broadcast_parity_dossier_to_chatgpt.txt"
$text = [System.IO.File]::ReadAllText($filePath)

# Put text onto Windows clipboard
[System.Windows.Forms.Clipboard]::SetText($text)
Write-Host "Clipboard set with $($text.Length) characters"

# Locate ChatGPT window
$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -ne "" } | Select-Object -First 1
if (-not $proc) {
    Write-Error "ChatGPT process not found"
    exit 1
}

# Bring window to foreground
[Microsoft.VisualBasic.Interaction]::AppActivate($proc.Id)
Start-Sleep -Milliseconds 400

$root = [System.Windows.Automation.AutomationElement]::RootElement
$appCond = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ProcessIdProperty, $proc.Id
)
$appElem = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $appCond)
if (-not $appElem) {
    Write-Error "Could not find ChatGPT AutomationElement"
    exit 1
}

# Find Send button
$sendBtnCond = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::AutomationIdProperty, "send-button"
)
$sendBtn = $appElem.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $sendBtnCond)

# Find ProseMirror editor
$editCond = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ClassNameProperty, "ProseMirror"
)
$editor = $appElem.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $editCond)

if ($editor) {
    $editor.SetFocus()
    Start-Sleep -Milliseconds 200
    [System.Windows.Forms.SendKeys]::SendWait("^a")
    Start-Sleep -Milliseconds 100
    [System.Windows.Forms.SendKeys]::SendWait("^v")
    Start-Sleep -Milliseconds 400
}

# Now click Send button
$sendBtn = $appElem.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $sendBtnCond)
if ($sendBtn -and $sendBtn.Current.IsEnabled) {
    Write-Host "Send button is ENABLED! Clicking Send button..."
    $invoke = $sendBtn.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
    $invoke.Invoke()
    Write-Host "INVOKED_SEND_BUTTON_SUCCESSFULLY"
} else {
    Write-Host "Send button disabled, pressing Enter..."
    [System.Windows.Forms.SendKeys]::SendWait("{ENTER}")
}
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True, text=True)
print("STDOUT:", res.stdout)
print("STDERR:", res.stderr)
