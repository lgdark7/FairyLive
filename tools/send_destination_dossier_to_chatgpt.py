import subprocess
import time

ps_code = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName Microsoft.VisualBasic

$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
if (-not $proc) { 
    Write-Output "ChatGPT proc not found"
    exit 
}

# 1. Read dossier text and copy to clipboard
$dossier = Get-Content -Path 'tools/destination_separation_dossier_to_chatgpt.txt' -Raw -Encoding UTF8
[System.Windows.Forms.Clipboard]::SetText($dossier)
Write-Output "Clipboard set: $($dossier.Length) chars"

# 2. Activate ChatGPT window
[Microsoft.VisualBasic.Interaction]::AppActivate($proc.Id)
Start-Sleep -Milliseconds 600

# 3. Find ProseMirror edit box
$root = [System.Windows.Automation.AutomationElement]::FromHandle($proc.MainWindowHandle)
$editCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ClassNameProperty, "ProseMirror")
$edit = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $editCond)

if ($edit) {
    $rect = $edit.Current.BoundingRectangle
    Write-Output "Edit rect: X=$($rect.X), Y=$($rect.Y), W=$($rect.Width), H=$($rect.Height)"
    
    Add-Type -MemberDefinition '[DllImport("user32.dll")] public static extern void mouse_event(int dwFlags, int dx, int dy, int dwData, int dwExtraInfo);' -Name Win32Mouse -Namespace Win32
    
    [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point([int]($rect.X + 30), [int]($rect.Y + 10))
    Start-Sleep -Milliseconds 200
    [Win32.Win32Mouse]::mouse_event(0x02, 0, 0, 0, 0)
    Start-Sleep -Milliseconds 50
    [Win32.Win32Mouse]::mouse_event(0x04, 0, 0, 0, 0)
    Start-Sleep -Milliseconds 400
    
    # Send Ctrl+A then Ctrl+V
    [System.Windows.Forms.SendKeys]::SendWait("^a")
    Start-Sleep -Milliseconds 200
    [System.Windows.Forms.SendKeys]::SendWait("^v")
    Start-Sleep -Milliseconds 1200
    
    # Check if Send button is now enabled
    $btnCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, "Send")
    $btn = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $btnCond)
    if ($btn) {
        Write-Output "Send button IsEnabled: $($btn.Current.IsEnabled)"
        if ($btn.Current.IsEnabled) {
            $inv = $btn.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
            if ($inv) {
                $inv.Invoke()
                Write-Output "CLICKED_SEND_BUTTON!"
            }
        } else {
            # Try Enter key
            [System.Windows.Forms.SendKeys]::SendWait("{ENTER}")
            Write-Output "SENT_ENTER_KEY"
        }
    }
} else {
    Write-Output "ProseMirror edit box not found"
}
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True, text=True)
print(res.stdout)
if res.stderr:
    print("Stderr:", res.stderr)
