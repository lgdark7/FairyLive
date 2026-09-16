import subprocess
import time
import sys
import os

ps_get_hwnd = """
Get-Process chrome | Where-Object { $_.MainWindowTitle -ne '' } | Select-Object -ExpandProperty MainWindowHandle -First 1
"""

def get_chrome_hwnd():
    p = subprocess.run(["powershell", "-NoProfile", "-Command", ps_get_hwnd], capture_output=True, text=True)
    out = p.stdout.strip()
    return int(out) if out.isdigit() else 197398

def wait_and_dump():
    hwnd = get_chrome_hwnd()
    print(f"Using Chrome HWND: {hwnd}")
    
    ps_check = f"""
    Add-Type -AssemblyName UIAutomationClient
    $root = [System.Windows.Automation.AutomationElement]::FromHandle({hwnd})
    $btnCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Button)
    $btns = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $btnCond)
    for ($i = [Math]::Max(0, $btns.Count - 15); $i -lt $btns.Count; $i++) {{
        $b = $btns[$i]
        if ($b.Current.Name -match 'Stop answering|Stop streaming|Stop generating') {{
            Write-Output "GENERATING"
            exit
        }}
    }}
    Write-Output "FINISHED"
    """

    print("Checking ChatGPT generation state...")
    start_t = time.time()
    # Wait at least 5 seconds for generation to start
    time.sleep(5.0)
    
    while time.time() - start_t < 300:
        p = subprocess.run(["powershell", "-NoProfile", "-Command", ps_check], capture_output=True, text=True)
        out = p.stdout.strip()
        if "GENERATING" in out:
            print(f"ChatGPT is generating... ({int(time.time() - start_t)}s elapsed)")
            time.sleep(5.0)
        else:
            print(f"ChatGPT finished generating or idle after {int(time.time() - start_t)}s!")
            break

    # Wait another 3s for DOM to stabilize
    time.sleep(3.0)

    # Dump the latest response texts
    ps_dump = f"""
    Add-Type -AssemblyName UIAutomationClient
    $root = [System.Windows.Automation.AutomationElement]::FromHandle({hwnd})
    $textCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Text)
    $texts = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $textCond)
    Write-Output "TOTAL_TEXTS: $($texts.Count)"
    for ($i = [Math]::Max(0, $texts.Count - 80); $i -lt $texts.Count; $i++) {{
        $t = $texts[$i]
        $val = $t.Current.Name
        if ($val -and $val.Length -gt 5) {{
            Write-Output "=== ITEM $i ==="
            Write-Output $val
        }}
    }}
    """
    p2 = subprocess.run(["powershell", "-NoProfile", "-Command", ps_dump], capture_output=True, text=True)
    resp = p2.stdout
    out_path = r"C:\Users\benny\Desktop\FAIRY LIVE\tools\chatgpt_m13_review.txt"
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(resp)
    print(f"Saved response texts to {out_path}")

if __name__ == "__main__":
    wait_and_dump()
