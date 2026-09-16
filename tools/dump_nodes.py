import subprocess

ps_code = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
if (-not $proc) { exit }

$root = [System.Windows.Automation.AutomationElement]::FromHandle($proc.MainWindowHandle)
# Get all elements under root
$walker = [System.Windows.Automation.TreeWalker]::RawViewWalker
$queue = New-Object System.Collections.Queue
$queue.Enqueue($root)

$output = @()

while ($queue.Count -gt 0) {
    $current = $queue.Dequeue()
    $name = $current.Current.Name
    if ($name -match "Must-have" -or $name -match "Nice-to-have" -or $name -match "priorities") {
        $output += "MATCH: $name"
    }
    
    $child = $walker.GetFirstChild($current)
    while ($child -ne $null) {
        $cName = $child.Current.Name
        if ($cName) {
            $output += "NODE: $cName"
        }
        $queue.Enqueue($child)
        $child = $walker.GetNextSibling($child)
    }
}

$output | Out-File -FilePath "tools/chatgpt_nodes.txt" -Encoding utf8
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True, text=True)
print("Done")