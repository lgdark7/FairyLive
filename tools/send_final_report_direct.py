import subprocess
import time
import ctypes

user32 = ctypes.windll.user32

# Win32 Constants
HWND_TOPMOST = -1
HWND_NOTOPMOST = -2
SWP_NOMOVE = 0x0002
SWP_NOSIZE = 0x0001
SWP_SHOWWINDOW = 0x0040

def send_report_to_chatgpt():
    print("Reading report...")
    with open("tools/m9_final_report.txt", "r", encoding="utf-8") as f:
        report_text = f.read()

    print("Copying report to Windows clipboard...")
    ps_cmd = 'Set-Clipboard -Value ([System.IO.File]::ReadAllText("tools/m9_final_report.txt", [System.Text.Encoding]::UTF8))'
    subprocess.run(["powershell", "-Command", ps_cmd], check=True)
    time.sleep(0.5)

    # Find ChatGPT HWND
    ps_find = """
    (Get-Process -Name ChatGPT | Where-Object { $_.MainWindowTitle -match 'ChatGPT' } | Select-Object -First 1).MainWindowHandle
    """
    p = subprocess.run(["powershell", "-Command", ps_find], capture_output=True, text=True)
    hwnd_str = p.stdout.strip()
    if not hwnd_str or hwnd_str == "0":
        print("ChatGPT HWND not found!")
        return
    hwnd = int(hwnd_str)
    print(f"ChatGPT HWND: {hwnd}")

    # Bring to foreground via SetWindowPos TOPMOST then restore
    user32.ShowWindow(hwnd, 9) # SW_RESTORE
    user32.SetWindowPos(hwnd, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW)
    time.sleep(0.2)
    user32.SetWindowPos(hwnd, HWND_NOTOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW)
    user32.SetForegroundWindow(hwnd)
    time.sleep(1.0)

    # Get the bounding rect of the ChatGPT window or input box
    rect = (ctypes.c_long * 4)()
    user32.GetWindowRect(hwnd, rect)
    left, top, right, bottom = rect[0], rect[1], rect[2], rect[3]
    print(f"ChatGPT Window Rect: left={left}, top={top}, right={right}, bottom={bottom}")

    # Input box is typically at center-bottom: (left + right)//2, bottom - 50
    input_x = (left + right) // 2
    input_y = bottom - 50
    print(f"Clicking ChatGPT input at ({input_x}, {input_y})...")
    user32.SetCursorPos(input_x, input_y)
    time.sleep(0.1)
    user32.mouse_event(0x02, 0, 0, 0, 0)
    time.sleep(0.05)
    user32.mouse_event(0x04, 0, 0, 0, 0)
    time.sleep(0.5)

    # Paste Ctrl+V
    print("Pasting...")
    user32.keybd_event(0x11, 0, 0, 0) # Ctrl down
    time.sleep(0.05)
    user32.keybd_event(0x56, 0, 0, 0) # V down
    time.sleep(0.05)
    user32.keybd_event(0x56, 0, 2, 0) # V up
    time.sleep(0.05)
    user32.keybd_event(0x11, 0, 2, 0) # Ctrl up
    time.sleep(1.5)

    # Send button is at right side of input: right - 40, bottom - 50
    send_x = right - 45
    send_y = bottom - 50
    print(f"Clicking Send button at ({send_x}, {send_y})...")
    user32.SetCursorPos(send_x, send_y)
    time.sleep(0.1)
    user32.mouse_event(0x02, 0, 0, 0, 0)
    time.sleep(0.05)
    user32.mouse_event(0x04, 0, 0, 0, 0)
    time.sleep(0.5)

    # Also send Enter key as backup
    user32.keybd_event(0x0D, 0, 0, 0) # Enter down
    time.sleep(0.05)
    user32.keybd_event(0x0D, 0, 2, 0) # Enter up

    print("Submission completed!")

if __name__ == "__main__":
    send_report_to_chatgpt()
