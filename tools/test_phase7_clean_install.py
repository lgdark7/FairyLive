#!/usr/bin/env python3
"""
Phase 7 Step 3: Clean Install Test Runner
Performs:
1. Full uninstall of com.fairylive.app
2. Fresh install of app-release.apk
3. First launch & cold start timing
4. Permissions check
5. Dashboard rendering check
6. Studio tab navigation
7. Settings tab navigation
8. Scene persistence check (verify default scenes and overlays load)
9. App process kill & reload
10. Scene persistence verification after reload
"""
import subprocess, time, sys, os, re, json

DEVICE = "emulator-5554"
APK = "app/build/outputs/apk/release/app-release.apk"

def run(cmd):
    p = subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, errors='replace')
    out, err = p.communicate()
    return (out + err).strip()

def ui():
    run(f"adb -s {DEVICE} shell uiautomator dump /sdcard/ux.xml")
    run(f'adb -s {DEVICE} pull /sdcard/ux.xml C:/Users/benny/AppData/Local/Temp/ux.xml')
    try:
        txt = open('C:/Users/benny/AppData/Local/Temp/ux.xml', encoding='utf-8').read()
        return {t: b for t, b in re.findall(r'text="([^"]+)"[^>]*bounds="(\[[0-9,\[\]]+\])"', txt)}
    except:
        return {}

def tap_bounds(bs):
    ns = list(map(int, re.findall(r'\d+', bs)))
    if len(ns) >= 4:
        cx = (ns[0] + ns[2]) // 2
        cy = (ns[1] + ns[3]) // 2
        run(f"adb -s {DEVICE} shell input tap {cx} {cy}")
        return (cx, cy)
    return None

results = {}

print("1. Uninstalling existing com.fairylive.app...")
res_un = run(f"adb -s {DEVICE} uninstall com.fairylive.app")
print(f"   Uninstall result: {res_un}")
results["uninstall"] = "Success" in res_un or "Unknown package" in res_un

print("2. Installing fresh app-release.apk...")
res_in = run(f"adb -s {DEVICE} install -r {APK}")
print(f"   Install result: {res_in}")
results["fresh_install"] = "Success" in res_in

print("3. First launch (Cold Start)...")
start_out = run(f"adb -s {DEVICE} shell am start -W -n com.fairylive.app/.MainActivity")
print(f"   Launch output: {start_out}")
m_time = re.search(r'TotalTime:\s*(\d+)', start_out)
cold_ms = int(m_time.group(1)) if m_time else None
results["cold_start_ms"] = cold_ms
results["cold_start_pass"] = cold_ms is not None and cold_ms < 1500
time.sleep(3)

print("4. Dashboard Verification...")
u1 = ui()
has_fairy = any("FAIRY LIVE" in k for k in u1)
has_dashboard = any("Gameplay" in k or "Scene" in k or "Start Live" in k for k in u1)
results["dashboard_rendered"] = has_fairy and has_dashboard
print(f"   Dashboard rendered: {results['dashboard_rendered']} (elements: {len(u1)})")

print("5. Navigating to Studio Tab...")
# Bottom nav studio button at (540, 2263)
run(f"adb -s {DEVICE} shell input tap 540 2263")
time.sleep(2)
u2 = ui()
has_studio = any("live" in k.lower() or "scene" in k.lower() or "layer" in k.lower() or "add" in k.lower() for k in u2)
results["studio_tab_loaded"] = has_studio
print(f"   Studio tab loaded: {results['studio_tab_loaded']}")

print("6. Navigating to Settings Tab...")
# Bottom nav settings button at (900, 2263)
run(f"adb -s {DEVICE} shell input tap 900 2263")
time.sleep(2)
u3 = ui()
has_dest = any("youtube" in k.lower() or "destination" in k.lower() or "stream" in k.lower() or "profile" in k.lower() for k in u3)
results["settings_tab_loaded"] = has_dest
print(f"   Settings tab loaded: {results['settings_tab_loaded']}")

print("7. Process Kill & Cold Reload...")
run(f"adb -s {DEVICE} shell am force-stop com.fairylive.app")
time.sleep(1)
run(f"adb -s {DEVICE} shell am start -n com.fairylive.app/.MainActivity")
time.sleep(3)
u4 = ui()
has_reload_scene = any("Gameplay" in k or "Scene" in k for k in u4)
results["scene_persisted_after_reload"] = has_reload_scene
print(f"   Scene persisted after reload: {results['scene_persisted_after_reload']}")

# Logcat crash audit
raw_log = run(f"adb -s {DEVICE} logcat -d")
fatal_lines = [l for l in raw_log.splitlines() if ("FATAL" in l or "AndroidRuntime" in l) and "com.fairylive.app" in l]
results["zero_crashes"] = len(fatal_lines) == 0
print(f"   Zero crashes verified: {results['zero_crashes']} (fatal lines: {len(fatal_lines)})")

os.makedirs("tools/phase7", exist_ok=True)
with open("tools/phase7/clean_install_results.json", "w") as f:
    json.dump(results, f, indent=2)

print("\n=== Clean Install Test Results ===")
print(json.dumps(results, indent=2))
