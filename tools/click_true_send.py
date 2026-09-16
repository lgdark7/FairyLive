import ctypes
import time

user32 = ctypes.windll.user32

# Set Cursor Pos and Click at (1755, 844)
user32.SetCursorPos(1755, 844)
time.sleep(0.1)
user32.mouse_event(0x02, 0, 0, 0, 0) # left down
time.sleep(0.05)
user32.mouse_event(0x04, 0, 0, 0, 0) # left up
print("Clicked Send button at (1755, 844) successfully!")
