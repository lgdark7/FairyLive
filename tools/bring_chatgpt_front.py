import subprocess

ps_code = """
Add-Type -MemberDefinition '[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd); [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);' -Name Win32Win -Namespace Win32

$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
Write-Output "Handle: $($proc.MainWindowHandle)"

# Show normal / restore (9 = SW_RESTORE, 1 = SW_SHOWNORMAL)
[Win32.Win32Win]::ShowWindow($proc.MainWindowHandle, 9)
[Win32.Win32Win]::SetForegroundWindow($proc.MainWindowHandle)
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True, text=True)
print(res.stdout)
