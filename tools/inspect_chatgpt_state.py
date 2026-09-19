import subprocess

ps = """
Add-Type -AssemblyName UIAutomationClient
$procs = Get-Process -Name ChatGPT
Write-Output ("Found ChatGPT processes: " + $procs.Count)
foreach ($p in $procs) {
    if ($p.MainWindowHandle -ne 0) {
        Write-Output ("Process Id: " + $p.Id + " Title: '" + $p.MainWindowTitle + "' Handle: " + $p.MainWindowHandle)
        $root = [System.Windows.Automation.AutomationElement]::FromHandle($p.MainWindowHandle)
        $cond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Button)
        $buttons = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $cond)
        Write-Output ("Buttons found: " + $buttons.Count)
        foreach ($b in $buttons) {
            $name = $b.Current.Name
            $help = $b.Current.HelpText
            $enabled = $b.Current.IsEnabled
            if ($name -match 'Send|Stop|Prompt|Ask|Search' -or $help -match 'Send|Stop') {
                Write-Output ("  Button: '" + $name + "' Help: '" + $help + "' Enabled: " + $enabled)
            }
        }
    }
}
"""

res = subprocess.run(["powershell", "-NoProfile", "-Command", ps], capture_output=True, text=True)
print(res.stdout)
if res.stderr:
    print("Error:", res.stderr)
