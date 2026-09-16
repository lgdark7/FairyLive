import ctypes
import time
import subprocess

user32 = ctypes.windll.user32

def send_roadmap():
    text = """Here is the relevant section from the original project master roadmap:

---
# 42. MILESTONE 9: SCENES
Implement:
* scene creation
* scene configuration
* overlay sets
* scene switching
* scene transitions where appropriate
Streaming should not need to restart during ordinary scene switching.

# 43. MILESTONE 10: CAMERA / FACE CAM (APPROVED & CLOSED)

# 44. MILESTONE 11: NETWORK RESILIENCE (COMPLETED AS M9: Adaptive Bitrate & Network Resilience)

# 45. MILESTONE 12: LOW-END OPTIMIZATION
Test on weakest practical Android hardware available. Measure: RAM, CPU, GPU, FPS, dropped frames, encoder load, startup, network stability, battery, temperature. Optimize based on measurements.

# 46. MILESTONE 13: STRESS TESTING
Long streams, repeated start/stop, network interruptions, Wi-Fi/mobile switching, background/foreground, screen lock, camera failure, mic failure, encoder failure, many overlays, low RAM, thermal throttling.

# 47. MILESTONE 14: SECURITY + HARDENING
Permissions, exported components, services, intents, network security, credentials, sensitive data, logs, release configuration.

# 48. MILESTONE 15: RELEASE QA
Release configuration, signing, APK/AAB, versioning, crash handling, compatibility, performance validation, final QA.
---

Context:
In our execution history, original M11 (Network Resilience) was completed as Milestone 9 (Adaptive Bitrate & Network Resilience Engine, Approved). Milestone 10 (Camera / Face Cam) is now Approved.
Original Milestone 9 (SCENES) was deferred.

Please decide and provide the official scope and strict acceptance criteria for Milestone 11:
Should M11 be SCENES (scene creation, configuration, overlay sets, runtime switching without stream restart), or LOW-END OPTIMIZATION, or another defined scope?
Please provide the formal authorization and instructions for M11."""

    print("Setting prompt to clipboard...")
    ps_cmd = 'Set-Clipboard -Value ([System.IO.File]::ReadAllText("tools/m11_query_prompt.txt", [System.Text.Encoding]::UTF8))'
    with open("tools/m11_query_prompt.txt", "w", encoding="utf-8") as f:
        f.write(text)
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
    print("Submitted roadmap prompt to ChatGPT!")

if __name__ == "__main__":
    send_roadmap()
