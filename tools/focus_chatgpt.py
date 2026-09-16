import subprocess

ps_script = """
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class WinUtil {
    [DllImport("user32.dll")]
    public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")]
    public static extern bool SetForegroundWindow(IntPtr hWnd);
}
"@

$discord = Get-Process -Name Discord -ErrorAction SilentlyContinue
foreach ($d in $discord) {
    if ($d.MainWindowHandle -ne [IntPtr]::Zero) {
        [WinUtil]::ShowWindow($d.MainWindowHandle, 6) # SW_MINIMIZE
    }
}

$c = Get-Process -Name ChatGPT -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
if ($c) {
    [WinUtil]::ShowWindow($c.MainWindowHandle, 9) # SW_RESTORE
    [WinUtil]::SetForegroundWindow($c.MainWindowHandle)
    Write-Host "ChatGPT restored: $($c.MainWindowHandle)"
} else {
    Write-Host "ChatGPT process not found"
}
"""

p = subprocess.run(["powershell", "-Command", ps_script], capture_output=True, text=True)
print("Stdout:", p.stdout)
print("Stderr:", p.stderr)
