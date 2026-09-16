import ctypes
import time
import subprocess
import os

user32 = ctypes.windll.user32

def submit_m12():
    print("Reading tools/m12_final_report.txt...")
    with open("tools/m12_final_report.txt", "r", encoding="utf-8") as f:
        content = f.read()

    p = subprocess.Popen(["powershell", "-NoProfile", "-Command", "$input | Set-Clipboard"], stdin=subprocess.PIPE, text=True, encoding='utf-8')
    p.communicate(input=content)
    time.sleep(0.5)

    target_hwnd = None
    def enum_cb(hwnd, lparam):
        nonlocal target_hwnd
        if user32.IsWindowVisible(hwnd):
            length = user32.GetWindowTextLengthW(hwnd)
            if length > 0:
                buf = ctypes.create_unicode_buffer(length + 1)
                user32.GetWindowTextW(hwnd, buf, length + 1)
                if "FAIRY LIVE" in buf.value and "Chrome" in buf.value:
                    target_hwnd = hwnd
        return True

    WNDENUMPROC = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_int, ctypes.c_int)
    user32.EnumWindows(WNDENUMPROC(enum_cb), 0)

    if not target_hwnd:
        print("Could not find Chrome FAIRY LIVE window!")
        return

    print(f"Found window HWND: {target_hwnd}")
    user32.ShowWindow(target_hwnd, 9) # SW_RESTORE
    user32.SetForegroundWindow(target_hwnd)
    time.sleep(1.0)

    # Click ProseMirror prompt box at (1114, 995)
    user32.SetCursorPos(1114, 995)
    time.sleep(0.2)
    user32.mouse_event(0x0002, 0, 0, 0, 0)
    user32.mouse_event(0x0004, 0, 0, 0, 0)
    time.sleep(0.5)

    # Paste
    user32.keybd_event(0x11, 0, 0, 0)
    user32.keybd_event(0x56, 0, 0, 0)
    time.sleep(0.1)
    user32.keybd_event(0x56, 0, 2, 0)
    user32.keybd_event(0x11, 0, 2, 0)
    print("Pasted report into prompt box.")
    time.sleep(1.5)

    # Find Send button via UIAutomation
    try:
        import uiautomation as auto
        chrome = auto.WindowControl(searchDepth=1, ClassName='Chrome_WidgetWin_1')
        send_btn = None
        for b in chrome.GetChildren():
            pass
        # Search send button
        btn = auto.ButtonControl(searchDepth=15, Name='Send prompt')
        if not btn.Exists(maxSearchSeconds=2):
            btn = auto.ButtonControl(searchDepth=15, AutomationId='send-button')
        if btn.Exists(maxSearchSeconds=2):
            print(f"Found send button via UIA: {btn.Name}, Bounding: {btn.BoundingRectangle}")
            btn.Click()
            print("Clicked Send button!")
            return
    except Exception as e:
        print(f"UIA error: {e}")

    # Fallback to mouse click at (1684, 995)
    print("Falling back to mouse click on send button at (1684, 995)...")
    user32.SetCursorPos(1684, 995)
    time.sleep(0.2)
    user32.mouse_event(0x0002, 0, 0, 0, 0)
    user32.mouse_event(0x0004, 0, 0, 0, 0)
    print("Clicked send button fallback!")

if __name__ == "__main__":
    submit_m12()
