import subprocess
import ctypes

user32 = ctypes.windll.user32

def enum_windows_callback(hwnd, extra):
    if user32.IsWindowVisible(hwnd):
        length = user32.GetWindowTextLengthW(hwnd)
        buff = ctypes.create_unicode_buffer(length + 1)
        user32.GetWindowTextW(hwnd, buff, length + 1)
        title = buff.value
        if any(x in title.lower() for x in ["pixel", "emulator", "android", "5554"]):
            rect = (ctypes.c_long * 4)()
            user32.GetWindowRect(hwnd, rect)
            extra.append((hwnd, title, rect[0], rect[1], rect[2], rect[3]))
    return True

EnumWindowsProc = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_int, ctypes.POINTER(ctypes.py_object))

windows = []
# Use EnumWindows via ctypes
def enum_all():
    results = []
    def cb(hwnd, lparam):
        length = user32.GetWindowTextLengthW(hwnd)
        if length > 0:
            buff = ctypes.create_unicode_buffer(length + 1)
            user32.GetWindowTextW(hwnd, buff, length + 1)
            title = buff.value
            if any(k in title.lower() for k in ["pixel", "emulator", "5554", "qemu", "android"]):
                rect = (ctypes.c_long * 4)()
                user32.GetWindowRect(hwnd, rect)
                is_vis = user32.IsWindowVisible(hwnd)
                results.append({"hwnd": hwnd, "title": title, "rect": list(rect), "visible": bool(is_vis)})
        return True

    WNDENUMPROC = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
    user32.EnumWindows(WNDENUMPROC(cb), 0)
    return results

res = enum_all()
print("Found matching windows:", res)
for w in res:
    hwnd = w["hwnd"]
    print(f"Restoring and bringing to front: {w['title']} (HWND: {hwnd})")
    # SW_RESTORE = 9, SW_SHOW = 5
    user32.ShowWindow(hwnd, 9)
    # HWND_TOP = 0, HWND_TOPMOST = -1
    user32.SetWindowPos(hwnd, -1, 100, 100, 500, 900, 0x0040) # SWP_SHOWWINDOW
    user32.SetWindowPos(hwnd, -2, 100, 100, 500, 900, 0x0040) # not topmost, but on top
    user32.SetForegroundWindow(hwnd)
    print("Done bringing window to front!")
