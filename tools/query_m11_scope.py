import ctypes
import time
import subprocess

user32 = ctypes.windll.user32

def ask_m11_scope():
    prompt = "Please provide the official scope, architecture requirements, test criteria, and verification constraints for Milestone 11 (M11) from our project roadmap."
    print("Setting prompt to clipboard...")
    ps_cmd = f'Set-Clipboard -Value "{prompt}"'
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
    user32.keybd_event(0x11, 0, 0, 0)
    time.sleep(0.05)
    user32.keybd_event(0x56, 0, 0, 0)
    time.sleep(0.05)
    user32.keybd_event(0x56, 0, 2, 0)
    time.sleep(0.05)
    user32.keybd_event(0x11, 0, 2, 0)
    time.sleep(1.5)

    print("Clicking Send button at (1479, 988)...")
    user32.SetCursorPos(1479, 988)
    time.sleep(0.1)
    user32.mouse_event(0x02, 0, 0, 0, 0)
    time.sleep(0.05)
    user32.mouse_event(0x04, 0, 0, 0, 0)
    print("Submitted query for M11 scope!")

if __name__ == "__main__":
    ask_m11_scope()
