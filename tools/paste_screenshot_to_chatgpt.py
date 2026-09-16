import subprocess
import time
import ctypes

ps_code = r"""
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

# 1. Copy image to clipboard
$img = [System.Drawing.Image]::FromFile('C:\Users\benny\Desktop\hermes_desktop_screenshot.png')
[System.Windows.Forms.Clipboard]::SetImage($img)
Write-Output "Image loaded to clipboard"

# 2. Find Chrome window
$root = [System.Windows.Automation.AutomationElement]::FromHandle(197398)
if (-not $root) {
    Write-Output "Chrome window 197398 not found"
    exit 1
}

# Bring Chrome to foreground
$user32 = Add-Type -MemberDefinition @"
[DllImport("user32.dll")]
public static extern bool SetForegroundWindow(IntPtr hWnd);
[DllImport("user32.dll")]
public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
"@ -Name "Win32Util" -PassThru

[Win32Util]::ShowWindow([IntPtr]197398, 9)
[Win32Util]::SetForegroundWindow([IntPtr]197398)
Start-Sleep -Milliseconds 500

# 3. Find 'Chat with ChatGPT' ProseMirror element
$editCond = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::NameProperty,
    "Chat with ChatGPT"
)
$editElem = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $editCond)

if ($editElem) {
    Write-Output "Found Chat with ChatGPT element"
    $rect = $editElem.Current.BoundingRectangle
    Write-Output "Bounding: $($rect.X), $($rect.Y), $($rect.Width), $($rect.Height)"
    
    # Focus and click in center of edit area
    $editElem.SetFocus()
    Start-Sleep -Milliseconds 300
    
    [System.Windows.Forms.SendKeys]::SendWait("^v")
    Write-Output "Pasted image via SendKeys ^v"
} else {
    Write-Output "Could not find Chat with ChatGPT element"
}
"""

res = subprocess.run(['powershell', '-NoProfile', '-Command', ps_code], capture_output=True, text=True)
print(res.stdout)
if res.stderr:
    print("Error:", res.stderr)
