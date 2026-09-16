import subprocess
import time

ps_script = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
$root = [System.Windows.Automation.AutomationElement]::FromHandle(132176)
$editCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ClassNameProperty, "ProseMirror")
$edit = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $editCond)

$dossier = Get-Content -Path 'tools/v11_phase4_dossier_to_chatgpt.txt' -Raw -Encoding UTF8

if ($edit) {
    $valPat = $edit.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern)
    if ($valPat) {
        $valPat.SetValue($dossier)
        Write-Output "SetValue done! Length: $($dossier.Length)"
    } else {
        Write-Output "No ValuePattern on ProseMirror"
    }
} else {
    Write-Output "ProseMirror not found"
}

Start-Sleep -Milliseconds 600

$btnCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, "Send")
$btn = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $btnCond)
if ($btn) {
    Write-Output "Send button enabled: $($btn.Current.IsEnabled)"
    if ($btn.Current.IsEnabled) {
        $inv = $btn.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
        if ($inv) {
            $inv.Invoke()
            Write-Output "INVOKED_SEND_SUCCESS!"
        }
    }
} else {
    Write-Output "Send button not found"
}
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_script], capture_output=True, text=True)
print(res.stdout)
if res.stderr:
    print("STDERR:", res.stderr)
