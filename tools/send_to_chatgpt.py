import time
import subprocess

report_path = "tools/m15c_ui_redesign_report.txt"
with open(report_path, "r", encoding="utf-8") as f:
    text = f.read()

# Copy to clipboard via PowerShell
ps_copy = subprocess.Popen(["powershell", "-Command", "Set-Clipboard -Value ([Console]::In.ReadToEnd())"], stdin=subprocess.PIPE, text=True)
ps_copy.communicate(text)

print("Clipboard set with report length:", len(text))

# Focus ChatGPT and Paste
ps_focus_paste = """
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName Microsoft.VisualBasic

$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
if ($proc) {
    [Microsoft.VisualBasic.Interaction]::AppActivate($proc.Id)
    Start-Sleep -Milliseconds 800
    [System.Windows.Forms.SendKeys]::SendWait("^v")
    Start-Sleep -Milliseconds 1200
    [System.Windows.Forms.SendKeys]::SendWait("{ENTER}")
    Write-Output "SENT"
} else {
    Write-Output "NOT_FOUND"
}
"""

res = subprocess.run(["powershell", "-Command", ps_focus_paste], capture_output=True, text=True)
print("Result:", res.stdout.strip(), res.stderr.strip())
