import subprocess
import time
import sys

ps_check = """
Add-Type -AssemblyName UIAutomationClient
$root = [System.Windows.Automation.AutomationElement]::FromHandle(131862)
$btnCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Button)
$btns = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $btnCond)
for ($i = [Math]::Max(0, $btns.Count - 10); $i -lt $btns.Count; $i++) {
    $b = $btns[$i]
    if ($b.Current.Name -match 'Stop answering') {
        Write-Output "GENERATING"
        exit
    }
}
Write-Output "FINISHED"
"""

def wait_and_dump():
    print("Waiting for ChatGPT to finish generating response...")
    start_t = time.time()
    while time.time() - start_t < 300: # 5 min timeout
        p = subprocess.run(["powershell", "-NoProfile", "-Command", ps_check], capture_output=True)
        out = p.stdout.decode('utf-8', errors='replace').strip()
        if "GENERATING" in out:
            print("ChatGPT is still generating... sleeping 5s")
            time.sleep(5.0)
        else:
            print("ChatGPT has finished generating!")
            break
    
    time.sleep(3.0)
    print("Dumping response...")
    
    ps_dump = """
Add-Type -AssemblyName UIAutomationClient
$root = [System.Windows.Automation.AutomationElement]::FromHandle(131862)
$textCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Text)
$texts = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $textCond)
Write-Output "TOTAL_TEXTS: $($texts.Count)"
for ($i = [Math]::Max(0, $texts.Count - 50); $i -lt $texts.Count; $i++) {
    $t = $texts[$i]
    $val = $t.Current.Name
    if ($val -and $val.Length -gt 10) {
        Write-Output "=== ITEM $i ==="
        Write-Output $val
    }
}
"""
    p2 = subprocess.run(["powershell", "-NoProfile", "-Command", ps_dump], capture_output=True)
    resp = p2.stdout.decode('utf-8', errors='replace')
    with open("tools/chatgpt_m11_revalidation_review.txt", "w", encoding="utf-8") as f:
        f.write(resp)
    print("Wrote review to tools/chatgpt_m11_revalidation_review.txt")

if __name__ == "__main__":
    wait_and_dump()
