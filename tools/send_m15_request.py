import time
import subprocess
import ctypes

user32 = ctypes.windll.user32

def send_to_chatgpt(text):
    # 1. Set clipboard via PowerShell
    ps_proc = subprocess.Popen(
        ["powershell.exe", "-NoProfile", "-Command", "$val = [Console]::In.ReadToEnd(); Set-Clipboard -Value $val"],
        stdin=subprocess.PIPE,
        text=True,
        encoding="utf-8"
    )
    ps_proc.communicate(input=text)
    time.sleep(0.5)

    # 2. Focus ChatGPT window
    ps_focus = """
    $proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
    if ($proc) {
        Add-Type -AssemblyName Microsoft.VisualBasic
        [Microsoft.VisualBasic.Interaction]::AppActivate($proc.Id)
        Start-Sleep -Milliseconds 800
        Add-Type -AssemblyName System.Windows.Forms
        [System.Windows.Forms.SendKeys]::SendWait("^v")
        Start-Sleep -Milliseconds 1000
        [System.Windows.Forms.SendKeys]::SendWait("{ENTER}")
        Write-Output "SENT"
    } else {
        Write-Output "NOT_FOUND"
    }
    """
    res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_focus], capture_output=True, text=True)
    print("PowerShell Send Result:", res.stdout.strip(), res.stderr.strip())

if __name__ == "__main__":
    prompt = "Yes, please provide the master Hermes prompt for M15-A -> M15-C with the complete technical specifications, layout requirements, preview editor controls, and acceptance criteria so Hermes can build and execute the entire final phase end-to-end!"
    send_to_chatgpt(prompt)
