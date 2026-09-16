import ctypes
import time
import subprocess

user32 = ctypes.windll.user32

def send_m14_inquiry():
    prompt = "Understood. The 30-minute soak test is actively running now. In the meantime, please provide the full specifications, technical requirements, acceptance criteria, and implementation prompt for Milestone 14 (the next milestone) so we are fully prepared to review and implement it once M13 is officially closed."

    ps_proc = subprocess.Popen(
        ["powershell.exe", "-NoProfile", "-Command", "$val = [Console]::In.ReadToEnd(); Set-Clipboard -Value $val"],
        stdin=subprocess.PIPE,
        text=True,
        encoding="utf-8"
    )
    ps_proc.communicate(input=prompt)
    time.sleep(0.5)

    # Bring Chrome window to front
    hwnd = 197398
    user32.ShowWindow(hwnd, 9)
    user32.SetForegroundWindow(hwnd)
    time.sleep(0.5)

    # Focus prompt box at (1000, 985)
    user32.SetCursorPos(1000, 985)
    time.sleep(0.1)
    user32.mouse_event(0x0002, 0, 0, 0, 0)
    user32.mouse_event(0x0004, 0, 0, 0, 0)
    time.sleep(0.3)

    # Ctrl+A -> Backspace
    user32.keybd_event(0x11, 0, 0, 0)
    user32.keybd_event(0x41, 0, 0, 0)
    time.sleep(0.05)
    user32.keybd_event(0x41, 0, 2, 0)
    user32.keybd_event(0x11, 0, 2, 0)
    time.sleep(0.1)

    # Paste: Ctrl+V
    user32.keybd_event(0x11, 0, 0, 0)
    user32.keybd_event(0x56, 0, 0, 0)
    time.sleep(0.1)
    user32.keybd_event(0x56, 0, 2, 0)
    user32.keybd_event(0x11, 0, 2, 0)
    print("Pasted prompt text.")
    time.sleep(1.0)

    # Now click the Send button at (1461, 985)
    print("Clicking Send button at (1461, 985)...")
    user32.SetCursorPos(1461, 985)
    time.sleep(0.2)
    user32.mouse_event(0x0002, 0, 0, 0, 0)
    user32.mouse_event(0x0004, 0, 0, 0, 0)
    time.sleep(0.5)
    print("Clicked!")

if __name__ == "__main__":
    send_m14_inquiry()
