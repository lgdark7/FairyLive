import subprocess
import time
import os
import sys

def run(c):
    p = subprocess.Popen(c, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    out, err = p.communicate()
    return out.strip()

print("1. Tapping + Add element (228, 1918)...")
run("adb shell input tap 228 1918")
time.sleep(1.5)

run("adb shell uiautomator dump /sdcard/dump.xml")
run("adb pull /sdcard/dump.xml tools/dump_add_sheet.xml")
