Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName Microsoft.VisualBasic

$proc = Get-Process electron | Where-Object { $_.MainWindowTitle -eq "Hermes" } | Select-Object -First 1
if (-not $proc) {
    Write-Output "Hermes process not found!"
    exit 1
}

Write-Output "Found Hermes PID: $($proc.Id), Handle: $($proc.MainWindowHandle)"
[Microsoft.VisualBasic.Interaction]::AppActivate($proc.Id)
Start-Sleep -Milliseconds 500

$root = [System.Windows.Automation.AutomationElement]::FromHandle($proc.MainWindowHandle)

$cond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, "Message")
$edit = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $cond)

if ($edit) {
    Write-Output "Found edit: $($edit.Current.Name)"
    $edit.SetFocus()
    Start-Sleep -Milliseconds 300
    
    $prompt = @"
@file:C:\Users\benny\Desktop\FAIRY LIVE\tools\youtube_studio_live_stream_key.jpg

In this screenshot it has stream key by using it test live!
"@
    Set-Clipboard -Value $prompt
    Start-Sleep -Milliseconds 300
    
    [System.Windows.Forms.SendKeys]::SendWait("^v")
    Start-Sleep -Milliseconds 500
    
    Write-Output "Pasted. Pressing Enter..."
    [System.Windows.Forms.SendKeys]::SendWait("{ENTER}")
    Start-Sleep -Milliseconds 500
    Write-Output "Enter pressed."
} else {
    Write-Output "Edit control not found!"
    exit 1
}
