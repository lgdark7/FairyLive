import json
import subprocess
import time

# Create a rich scene containing the REAL WEB OVERLAY
scene_obj = {
    "id": "scene_gameplay",
    "name": "Gameplay",
    "orderIndex": 0,
    "backgroundMode": "SCREEN_CAPTURE",
    "backgroundColorArgb": -16777216,
    "facecam": {
        "enabled": False,
        "facing": "FRONT",
        "normalizedX": 0.70,
        "normalizedY": 0.04,
        "normalizedWidth": 0.26,
        "normalizedHeight": 0.26,
        "scale": 1.0,
        "mirrorHorizontal": True,
        "isCircle": False,
        "showBorder": False,
        "opacity": 1.0
    },
    "overlays": [
        {
            "id": "web_live_overlay",
            "type": "WEB",
            "normalizedX": 0.10,
            "normalizedY": 0.15,
            "normalizedWidth": 0.50,
            "normalizedHeight": 0.28,
            "scale": 1.0,
            "rotationDegrees": 0.0,
            "opacity": 1.0,
            "visible": True,
            "locked": False,
            "zIndex": 20,
            "cornerRadiusPx": 12.0,
            "webConfig": {
                "url": "https://example.com",
                "transparentBackground": True,
                "refreshIntervalSeconds": 30,
                "customWidth": 800,
                "customHeight": 450
            }
        },
        {
            "id": "yt_sub_counter",
            "type": "YOUTUBE_SUBSCRIBER",
            "normalizedX": 0.65,
            "normalizedY": 0.05,
            "normalizedWidth": 0.30,
            "normalizedHeight": 0.10,
            "scale": 1.0,
            "rotationDegrees": 0.0,
            "opacity": 1.0,
            "visible": True,
            "locked": False,
            "zIndex": 25,
            "cornerRadiusPx": 12.0,
            "youtubeSubscriberConfig": {
                "channelId": "UC123456",
                "channelTitle": "FAIRY GAMING",
                "subscriberCountFormatted": "12.5K",
                "rawSubscriberCount": 12500,
                "accentColorRgba": -65536
            }
        }
    ]
}

scenes_list = [scene_obj]
scenes_json_str = json.dumps(scenes_list)

xml_content = f'''<?xml version=\'1.0\' encoding=\'utf-8\' standalone=\'yes\' ?>
<map>
    <string name="key_active_scene_id">scene_gameplay</string>
    <string name="key_scenes_json">{scenes_json_str.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")}</string>
</map>
'''

with open("tools/fairy_scenes_prefs_web.xml", "w", encoding="utf-8") as f:
    f.write(xml_content)

print("Pushing fairy_scenes_prefs_web.xml...")
subprocess.run("adb push tools/fairy_scenes_prefs_web.xml /data/local/tmp/fairy_scenes_prefs.xml", shell=True)
subprocess.run("adb shell run-as com.fairylive.app cp /data/local/tmp/fairy_scenes_prefs.xml /data/data/com.fairylive.app/shared_prefs/fairy_scenes_prefs.xml", shell=True)
print("Injected active scene with WEB overlay!")
