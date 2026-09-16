import subprocess
import time

ps_code = """
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class WinUtil {
    [DllImport("user32.dll")]
    public static extern bool ShowWindowAsync(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")]
    public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")]
    public static extern bool IsIconic(IntPtr hWnd);
}
"@

$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
if ($proc) {
    Write-Host "Found ChatGPT HWND: $($proc.MainWindowHandle)"
    # SW_RESTORE = 9, SW_SHOW = 5
    [WinUtil]::ShowWindowAsync($proc.MainWindowHandle, 9)
    Start-Sleep -Milliseconds 500
    [WinUtil]::SetForegroundWindow($proc.MainWindowHandle)
    Write-Host "Restored and SetForegroundWindow called."
} else {
    Write-Host "ChatGPT process not found!"
}
"""

subprocess.run(["powershell", "-Command", ps_code], check=True)
time.sleep(1.0)
