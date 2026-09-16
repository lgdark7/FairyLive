import ctypes
import time
import subprocess

user32 = ctypes.windll.user32
hwnd = 721818

msg = (
    "Here is ChatGPT's exact instruction and verdict after reviewing your live screenshot:\n\n"
    "> \"Yep bro — that screenshot is exactly what we want. 🎯\n"
    "> Hermes has started the actual M13 30-minute continuous soak test:\n"
    "> ✅ 145/145 unit tests\n"
    "> ✅ 23/23 connected tests\n"
    "> ✅ 20 start/stop cycles completed\n"
    "> ✅ 10 reconnect cycles + exhaustion completed\n"
    "> 🟢 30-minute RTMP soak is NOW RUNNING (Sampling every 60s, V/A timestamps, CPU, PSS, native/Dalvik, RSS, threads, sockets, codecs)\n"
    "> 🧪 Scheduled stress: ABR, overlay/GIF, camera cycling, thermal, memory pressure\n\n"
    "> What you should do now:\n"
    "> Nothing. Let Hermes finish. ☕\n"
    "> Once the 30 minutes finish, Hermes should automatically:\n"
    "> 30-min soak ➔ analyze ledger ➔ fix failures if any ➔ rerun ➔ full regression ➔ update docs ➔ Git checkpoint ➔ final M13 dossier.\n"
    "> Then bring me that final M13 dossier.\n"
    "> I'll make the actual gate decision: 🟢 M13 APPROVED — M14 AUTHORIZED or send Hermes back for fixes.\n"
    "> M14 stays locked until then.\"\n\n"
    "Follow ChatGPT: Complete the 30-minute soak test, analyze the ledger, run the full regressions, update all 6 project docs, create the M13 Git checkpoint, and prepare the final M13 dossier for ChatGPT's review. Do NOT proceed to M14 until ChatGPT reviews and grants official authorization."
)

# Put on clipboard
p = subprocess.Popen(
    ["powershell.exe", "-NoProfile", "-Command", "$val = [Console]::In.ReadToEnd(); Set-Clipboard -Value $val"],
    stdin=subprocess.PIPE,
    text=True,
    encoding="utf-8"
)
p.communicate(input=msg)
time.sleep(0.3)

# Bring Hermes Desktop to front
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
time.sleep(0.3)

# Enter to send
user32.keybd_event(0x0D, 0, 0, 0)
time.sleep(0.05)
user32.keybd_event(0x0D, 0, 2, 0)
print("Submitted ChatGPT instructions into Hermes Desktop chat!")
