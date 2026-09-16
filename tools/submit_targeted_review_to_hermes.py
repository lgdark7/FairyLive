import ctypes
import time
import subprocess

user32 = ctypes.windll.user32
hwnd = 721818

with open(r"C:\Users\benny\Desktop\FAIRY LIVE\tools\chatgpt_m13_targeted_review.txt", "r", encoding="utf-8") as f:
    chatgpt_review = f.read()

header = (
    "Here is ChatGPT's official gate review on your revalidation findings:\n\n"
    "Status: 🟡 M13 REVALIDATION REQUIRED — 🔒 M14 REMAINS LOCKED\n\n"
    "ChatGPT commended the resolution of Blocker 4 (CPU harness normalization), Blocker 6 (ABR 3s cooldown), "
    "and Blocker 5 (Memory plateau), but identified ONE CRITICAL REMAINING BLOCKER in A/V Accounting:\n\n"
)

full_submission = header + chatgpt_review + "\n\n" + (
    "Please investigate and reconcile this exact discrepancy (Raw AAC AU count vs AAC PTS duration vs Wall-clock duration vs Video PTS), "
    "produce the required reconciliation table, synchronize the docs, and provide the final A/V revalidation dossier for ChatGPT!"
)

# Put full_submission on clipboard
p = subprocess.Popen(
    ["powershell.exe", "-NoProfile", "-Command", "$val = [Console]::In.ReadToEnd(); Set-Clipboard -Value $val"],
    stdin=subprocess.PIPE,
    text=True,
    encoding="utf-8"
)
p.communicate(input=full_submission)
time.sleep(0.4)

# Restore and focus Hermes Desktop
user32.ShowWindow(hwnd, 9)
user32.SetForegroundWindow(hwnd)
time.sleep(0.4)

# Click composer at (350, 1005)
user32.SetCursorPos(350, 1005)
time.sleep(0.1)
user32.mouse_event(0x0002, 0, 0, 0, 0)
user32.mouse_event(0x0004, 0, 0, 0, 0)
time.sleep(0.2)

# Ctrl+A, Ctrl+V
user32.keybd_event(0x11, 0, 0, 0)
user32.keybd_event(0x41, 0, 0, 0)
time.sleep(0.05)
user32.keybd_event(0x41, 0, 2, 0)
user32.keybd_event(0x11, 0, 2, 0)
time.sleep(0.1)

user32.keybd_event(0x11, 0, 0, 0)
user32.keybd_event(0x56, 0, 0, 0)
time.sleep(0.05)
user32.keybd_event(0x56, 0, 2, 0)
user32.keybd_event(0x11, 0, 2, 0)
time.sleep(0.5)

# Enter to send
user32.keybd_event(0x0D, 0, 0, 0)
time.sleep(0.05)
user32.keybd_event(0x0D, 0, 2, 0)
print("Submitted ChatGPT targeted review to Hermes Desktop chat!")
