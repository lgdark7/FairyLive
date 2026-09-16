import ctypes
import time
import subprocess

user32 = ctypes.windll.user32

def submit_revalidation():
    print("Reading tools/m10_revalidation_report.txt...")
    with open("tools/m10_revalidation_report.txt", "r", encoding="utf-8") as f:
        text = f.read()

    print(f"Read {len(text)} characters. Setting clipboard...")
    ps_cmd = 'Set-Clipboard -Value ([System.IO.File]::ReadAllText("tools/m10_revalidation_report.txt", [System.Text.Encoding]::UTF8))'
    subprocess.run(["powershell", "-NoProfile", "-Command", ps_cmd], check=True)
    time.sleep(0.5)

    print("Activating Chrome window...")
    h = 131862
    user32.ShowWindow(h, 9)
    user32.SetForegroundWindow(h)
    time.sleep(1.0)

    print("Clicking ChatGPT prompt box at (1114, 995)...")
    user32.SetCursorPos(1114, 995)
    time.sleep(0.1)
    user32.mouse_event(0x02, 0, 0, 0, 0)
    time.sleep(0.05)
    user32.mouse_event(0x04, 0, 0, 0, 0)
    time.sleep(0.5)

    print("Pasting...")
    user32.keybd_event(0x11, 0, 0, 0) # Ctrl
    time.sleep(0.05)
    user32.keybd_event(0x56, 0, 0, 0) # V
    time.sleep(0.05)
    user32.keybd_event(0x56, 0, 2, 0) # V up
    time.sleep(0.05)
    user32.keybd_event(0x11, 0, 2, 0) # Ctrl up
    time.sleep(1.5)

    print("Clicking Send button at (1479, 988)...")
    user32.SetCursorPos(1479, 988)
    time.sleep(0.1)
    user32.mouse_event(0x02, 0, 0, 0, 0)
    time.sleep(0.05)
    user32.mouse_event(0x04, 0, 0, 0, 0)

    print("Submitted! Waiting 3s...")
    time.sleep(3.0)

if __name__ == "__main__":
    submit_revalidation()
