import ctypes
import time
import subprocess

user32 = ctypes.windll.user32
ps = subprocess.run(["powershell", "-Command", "(Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match \"ChatGPT\" }).MainWindowHandle"], capture_output=True, text=True)
hwnd_str = ps.stdout.strip()
if hwnd_str and hwnd_str != "0":
    hwnd = int(hwnd_str)
    user32.ShowWindow(hwnd, 9)
    user32.SetForegroundWindow(hwnd)
    time.sleep(0.5)

with open("tools/m15f_verification_report.txt", "r", encoding="utf-8") as f:
    report_text = f.read()

prompt = f"""Here is the complete implementation and verification report for Milestone 15-F (Canvas-First Studio Layout & Half-Height Expandable Inspector Bottom Sheet):

{report_text}

All 216/216 tests are PASSING. The release APK is 2.3 MB. Please review and provide your evaluation and next milestone directives."""

subprocess.run(["powershell", "-Command", "Set-Clipboard -Value $input"], input=prompt, text=True)

ps_script = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName System.Windows.Forms
$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match "ChatGPT" }
$element = [System.Windows.Automation.AutomationElement]::FromHandle($proc.MainWindowHandle)
$condition = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Edit)
$edit = $element.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $condition)

if ($edit) {
    $rect = $edit.Current.BoundingRectangle
    $clickX = [int]($rect.X + 20)
    $clickY = [int]($rect.Y + 20)
    [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point($clickX, $clickY)
    
    [System.Windows.Forms.SendKeys]::SendWait(" ")
    Start-Sleep -Milliseconds 100
    [System.Windows.Forms.SendKeys]::SendWait("{BACKSPACE}")
    Start-Sleep -Milliseconds 100
    [System.Windows.Forms.SendKeys]::SendWait("^a")
    Start-Sleep -Milliseconds 100
    [System.Windows.Forms.SendKeys]::SendWait("^v")
    Start-Sleep -Milliseconds 500
    
    $btnCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Button)
    $buttons = $element.FindAll([System.Windows.Automation.TreeScope]::Descendants, $btnCond)
    $sent = $false
    for ($i = 0; $i -lt $buttons.Count; $i++) {
        $btn = $buttons.Item($i)
        $name = $btn.Current.Name
        $help = $btn.Current.HelpText
        if ($name -match "Send" -or $help -match "Send" -or $name -eq "Send prompt") {
            try {
                $invoke = $btn.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
                $invoke.Invoke()
                Write-Host "Send invoked via InvokePattern"
                $sent = $true
                break
            } catch {
                Write-Host "Invoke failed: $_"
            }
        }
    }
    if (-not $sent) {
        [System.Windows.Forms.SendKeys]::SendWait("{ENTER}")
        Write-Host "Send triggered via ENTER key"
    }
} else {
    Write-Host "Edit box not found"
}
"""

res = subprocess.run(["powershell", "-Command", ps_script], capture_output=True, text=True)
print(res.stdout)
