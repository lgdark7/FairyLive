import subprocess

ps_code = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$proc = Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1
$root = [System.Windows.Automation.AutomationElement]::FromHandle($proc.MainWindowHandle)
$editCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ClassNameProperty, "ProseMirror")
$edit = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $editCond)

if ($edit) {
    Write-Output "ProseMirror element found!"
    Write-Output "Name: $($edit.Current.Name)"
    Write-Output "HelpText: $($edit.Current.HelpText)"
    
    # Check children
    $children = $edit.FindAll([System.Windows.Automation.TreeScope]::Children, [System.Windows.Automation.Condition]::TrueCondition)
    Write-Output "Children count: $($children.Count)"
    foreach ($c in $children) {
        Write-Output "  Child: Class=$($c.Current.ClassName) Name='$($c.Current.Name)'"
    }
} else {
    Write-Output "ProseMirror element NOT found"
}
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True, text=True)
print(res.stdout)
