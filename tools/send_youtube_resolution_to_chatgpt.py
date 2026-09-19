import subprocess

ps_code = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName Microsoft.VisualBasic

$filePath = "C:\\\\Users\\\\benny\\\\Desktop\\\\FAIRY LIVE\\\\tools\\\\youtube_live_resolution_dossier.txt"
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
    $name = $b.Current.Name
    $helpText = $b.Current.HelpText
    if ($name -match "Send" -or $helpText -match "Send" -or $b.Current.AutomationId -match "send") {
        $sendBtn = $b
        break
    }
}

if ($sendBtn) {
    Write-Host "Found Send button: '$($sendBtn.Current.Name)'. Invoking..."
    $invokePattern = $sendBtn.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
    if ($invokePattern) {
        $invokePattern.Invoke()
        Write-Host "Send button invoked via pattern!"
    } else {
        $editor.SetFocus()
        [System.Windows.Forms.SendKeys]::SendWait("{ENTER}")
        Write-Host "Sent via ENTER keypress!"
    }
} else {
    Write-Host "Send button not specifically identified, pressing ENTER in editor..."
    $editor.SetFocus()
    [System.Windows.Forms.SendKeys]::SendWait("{ENTER}")
}

Write-Host "YouTube Live Resolution Dossier successfully delivered to ChatGPT!"
"""

proc = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True, text=True)
print("STDOUT:", proc.stdout)
print("STDERR:", proc.stderr)
print("EXIT CODE:", proc.returncode)
