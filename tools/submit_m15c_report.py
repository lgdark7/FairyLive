#!/usr/bin/env python3
"""
Submit Milestone 15-C Verification Report to ChatGPT desktop app.
"""
import time
import subprocess
import os

with open("tools/m15c_final_release_dossier.txt", "r", encoding="utf-8") as f:
    REPORT = f.read()

def submit_to_chatgpt(text):
    import win32gui
    import win32con
    import win32clipboard

    # 1. Put report into clipboard
    win32clipboard.OpenClipboard()
    win32clipboard.EmptyClipboard()
    win32clipboard.SetClipboardText(text, win32clipboard.CF_UNICODETEXT)
    win32clipboard.CloseClipboard()
    print("[CLIPBOARD] Final M15-C report copied to clipboard successfully.")

    # 2. Locate ChatGPT window
    hwnds = []
    def enum_cb(hwnd, extra):
        if win32gui.IsWindowVisible(hwnd):
            title = win32gui.GetWindowText(hwnd)
            if "chatgpt" in title.lower():
                hwnds.append((hwnd, title))
        return True

    win32gui.EnumWindows(enum_cb, None)
    if not hwnds:
        print("[ERROR] ChatGPT window not found!")
        return False

    target_hwnd, title = hwnds[0]
    print(f"[FOUND] ChatGPT window: '{title}' (HWND: {target_hwnd})")

    # Restore and bring to front
    win32gui.ShowWindow(target_hwnd, win32con.SW_RESTORE)
    time.sleep(0.5)
    win32gui.SetForegroundWindow(target_hwnd)
    time.sleep(1.0)

    # Use paste_and_send logic
    import ctypes
    user32 = ctypes.windll.user32

    VK_CONTROL = 0x11
    VK_V = 0x56
    VK_RETURN = 0x0D

    def key_down(vk):
        user32.keybd_event(vk, 0, 0, 0)

    def key_up(vk):
        user32.keybd_event(vk, 0, 2, 0)

    print("[SEND] Pasting report via Ctrl+V...")
    key_down(VK_CONTROL)
    time.sleep(0.1)
    key_down(VK_V)
    time.sleep(0.1)
    key_up(VK_V)
    key_up(VK_CONTROL)
    time.sleep(1.5)

    print("[SEND] Pressing Enter...")
    key_down(VK_RETURN)
    time.sleep(0.1)
    key_up(VK_RETURN)
    time.sleep(2.0)

    print("[SUCCESS] Report submitted to ChatGPT desktop app!")
    return True

if __name__ == "__main__":
    submit_to_chatgpt(REPORT)
