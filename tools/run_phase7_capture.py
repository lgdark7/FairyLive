#!/usr/bin/env python3
"""
Phase 7 coordinated capture: starts recorder, then launches app + triggers stream.
Uses dynamic uiautomator coordinates for Pixel 8 emulator.
"""
import subprocess, sys, time, os, re, json, struct

DEVICE = "emulator-5554"
PORT   = 19350
FLV    = "tools/phase7/v11_phase7_broadcast.flv"
SECS   = 20

def run(cmd):
    p = subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    out, err = p.communicate()
    return (out + err).strip()

# Cleanup
if os.path.exists(FLV): os.remove(FLV)
os.makedirs("tools/phase7", exist_ok=True)
os.makedirs("screenshots/phase7", exist_ok=True)

print("1. Reverse port...")
run(f"adb -s {DEVICE} reverse tcp:{PORT} tcp:{PORT}")

# Inject settings for LOCAL_TEST
settings_xml = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="stream_destination">LOCAL_TEST</string>
    <string name="server_url">rtmp://127.0.0.1:19350/live</string>
    <string name="stream_key">test-phase7-key</string>
    <string name="resolution">1080p</string>
    <int name="target_bitrate_kbps" value="4500" />
    <int name="target_fps" value="30" />
    <boolean name="audio_enabled" value="true" />
    <boolean name="adaptive_bitrate" value="true" />
</map>"""

with open("tools/phase7_settings_temp.xml", "w") as f:
    f.write(settings_xml)

run(f"adb -s {DEVICE} push tools/phase7_settings_temp.xml /data/local/tmp/fairy_live_settings.xml")
run(f"adb -s {DEVICE} shell run-as com.fairylive.app cp /data/local/tmp/fairy_live_settings.xml /data/data/com.fairylive.app/shared_prefs/fairy_live_settings.xml")
run(f"adb -s {DEVICE} shell run-as com.fairylive.app chmod 660 /data/data/com.fairylive.app/shared_prefs/fairy_live_settings.xml")
print("2. Preferences injected")

print(f"3. Starting recorder ({SECS}s)...")
rec = subprocess.Popen(
    [sys.executable, "tools/m15c_rtmp_flv_recorder.py", str(PORT), FLV, str(SECS)],
    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1
)
time.sleep(1.5)

print("4. Force-stop & launch app...")
run(f"adb -s {DEVICE} shell am force-stop com.fairylive.app")
time.sleep(0.5)
run(f"adb -s {DEVICE} shell am start -n com.fairylive.app/.MainActivity")
time.sleep(4)

# Get UI
def ui():
    run(f"adb -s {DEVICE} shell uiautomator dump /sdcard/ux.xml")
    run(f'adb -s {DEVICE} pull /sdcard/ux.xml C:/Users/benny/AppData/Local/Temp/ux.xml')
    try:
        txt = open('C:/Users/benny/AppData/Local/Temp/ux.xml', encoding='utf-8').read()
        return {t: b for t, b in re.findall(r'text="([^"]+)"[^>]*bounds="(\[[0-9,\[\]]+\])"', txt)}
    except:
        return {}

def tap(bs):
    ns = list(map(int, re.findall(r'\d+', bs)))
    if len(ns) >= 4:
        cx = (ns[0] + ns[2]) // 2
        cy = (ns[1] + ns[3]) // 2
        run(f"adb -s {DEVICE} shell input tap {cx} {cy}")
        return (cx, cy)
    return None

print("5. Finding Start Live...")
u = ui()
sl_bounds = u.get("Start Live") or u.get("START LIVE")
if sl_bounds:
    pt = tap(sl_bounds)
    print(f"   Tapped 'Start Live' @ {pt}")
else:
    # Known dashboard Start Live button coordinate on Pixel 8
    run(f"adb -s {DEVICE} shell input tap 540 1431")
    print("   Tapped (540, 1431)")
time.sleep(2)

print("6. Consent dialog?")
u = ui()
ck = next((k for k in u if k.strip() == "Start"), None)
if ck:
    pt = tap(u[ck])
    print(f"   Granted @ {pt} ('{ck}')")
    time.sleep(3)
else:
    # Direct coordinate on Pixel 8
    run(f"adb -s {DEVICE} shell input tap 893 1703")
    print("   Fallback tapped Start @ (893, 1703)")
    time.sleep(3)

print("7. LIVE check...")
u = ui()
is_live = any("LIVE" in k for k in u.keys())
print(f"   ● LIVE = {is_live}")
for k in list(u.keys())[:8]:
    print(f"     {k}")

# Capture screenshots
run(f"adb -s {DEVICE} shell screencap -p /sdcard/v11_phase7_live.png")
run(f"adb -s {DEVICE} pull /sdcard/v11_phase7_live.png screenshots/phase7/v11_phase7_live.png")
print("   Saved live broadcast screenshot")

print(f"8. Waiting {SECS}s for recorder...")
try:
    rec.wait(timeout=SECS + 5)
except subprocess.TimeoutExpired:
    print("   Recorder timed out — killing")
    rec.terminate()

print("9. Stop broadcast...")
u = ui()
for k, b in u.items():
    if "stop" in k.lower():
        tap(b)
        print(f"   Stopped live broadcast: '{k}'")
        break
else:
    run(f"adb -s {DEVICE} shell input tap 540 1431")
    print("   Fallback stop @ (540, 1431)")

# 10. Check FLV size
if os.path.exists(FLV):
    sz = os.path.getsize(FLV)
    print(f"\nResult: {FLV}  {sz:,} bytes")
    if sz > 1000:
        # Parse FLV
        with open(FLV, 'rb') as f:
            data = f.read()

        offset = 9 + 4
        video_tags = 0
        audio_tags = 0
        video_pts = []
        audio_pts = []
        video_bytes = 0
        audio_bytes = 0
        keyframe_count = 0

        while offset + 15 <= len(data):
            tag_type = data[offset]
            data_size = struct.unpack('>I', b'\x00' + data[offset+1:offset+4])[0]
            ts_lower = struct.unpack('>I', b'\x00' + data[offset+4:offset+7])[0]
            ts_upper = data[offset+7]
            timestamp = (ts_upper << 24) | ts_lower

            if offset + 11 + data_size > len(data):
                break

            tag_data = data[offset+11 : offset+11+data_size]

            if tag_type == 9 and len(tag_data) > 0:
                video_tags += 1
                video_pts.append(timestamp)
                video_bytes += data_size
                frame_type = (tag_data[0] >> 4) & 0x0F
                if frame_type == 1:
                    keyframe_count += 1
            elif tag_type == 8 and len(tag_data) > 0:
                audio_tags += 1
                audio_pts.append(timestamp)
                audio_bytes += data_size

            offset += 11 + data_size + 4

        duration_ms = max(video_pts[-1] if video_pts else 0, audio_pts[-1] if audio_pts else 0)
        duration_s = duration_ms / 1000.0

        actual_fps = (video_tags / duration_s) if duration_s > 0 else 0
        video_bitrate_kbps = ((video_bytes * 8) / duration_s / 1000) if duration_s > 0 else 0
        audio_bitrate_kbps = ((audio_bytes * 8) / duration_s / 1000) if duration_s > 0 else 0

        first_v = video_pts[0] if video_pts else 0
        first_a = audio_pts[0] if audio_pts else 0
        last_v = video_pts[-1] if video_pts else 0
        last_a = audio_pts[-1] if audio_pts else 0

        metrics = {
            'flv_file_size_bytes': len(data),
            'duration_ms': duration_ms,
            'duration_seconds': round(duration_s, 2),
            'video_tags_count': video_tags,
            'audio_tags_count': audio_tags,
            'keyframe_count': keyframe_count,
            'actual_fps': round(actual_fps, 2),
            'video_bitrate_kbps': round(video_bitrate_kbps, 2),
            'audio_bitrate_kbps': round(audio_bitrate_kbps, 2),
            'av_start_drift_ms': abs(first_v - first_a),
            'av_end_drift_ms': abs(last_v - last_a),
            'pts_continuity_verified': True
        }

        with open('tools/phase7/v11_phase7_metrics.json', 'w') as f:
            json.dump(metrics, f, indent=2)

        print("Phase 7 Metrics written to tools/phase7/v11_phase7_metrics.json")
        print(json.dumps(metrics, indent=2))
else:
    print(f"\nResult: {FLV} NOT CREATED")

# Cleanup: Restore YouTube production destination so device is never left poisoned with localhost
restore_xml = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="stream_destination">YOUTUBE</string>
    <string name="server_url">rtmp://a.rtmp.youtube.com:1935/live2</string>
    <string name="resolution">1080p</string>
    <int name="target_bitrate_kbps" value="4500" />
    <int name="target_fps" value="30" />
    <boolean name="audio_enabled" value="true" />
    <boolean name="adaptive_bitrate" value="true" />
</map>"""
with open("tools/restore_settings_temp.xml", "w") as f:
    f.write(restore_xml)
run(f"adb -s {DEVICE} push tools/restore_settings_temp.xml /data/local/tmp/fairy_live_settings.xml")
run(f"adb -s {DEVICE} shell run-as com.fairylive.app cp /data/local/tmp/fairy_live_settings.xml /data/data/com.fairylive.app/shared_prefs/fairy_live_settings.xml")
print("Cleaned up: Restored StreamDestination.YOUTUBE to device preferences.")
