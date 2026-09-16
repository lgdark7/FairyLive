import subprocess

ps_code = """
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$root = [System.Windows.Automation.AutomationElement]::FromHandle(131862)
if (-not $root) {
    Write-Output "NO_ROOT"
    exit
}

$docCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Document)
$doc = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $docCond)

if (-not $doc) {
    Write-Output "NO_DOCUMENT"
    exit
}

Write-Output "Document found: Name='$($doc.Current.Name)'"
$all = $doc.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
Write-Output "Total elements in Document: $($all.Count)"

# Look for edit/document or text or button
for ($i = 0; $i -lt $all.Count; $i++) {
    $item = $all[$i]
    $type = $item.Current.ControlType.ProgrammaticName
    $name = $item.Current.Name
    $rect = $item.Current.BoundingRectangle
    if ($type -match 'Edit|Button|Text' -and ($name -match 'Message|Send|Ask|Attach|submit' -or $type -match 'Edit')) {
        Write-Output "[$i] Type=$type Name='$name' Rect=($($rect.X), $($rect.Y), $($rect.Width), $($rect.Height))"
    }
}
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code], capture_output=True)
print(res.stdout.decode('utf-8', errors='replace'))
