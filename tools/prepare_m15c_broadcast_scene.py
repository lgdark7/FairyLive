#!/usr/bin/env python3
"""
tools/prepare_m15c_broadcast_scene.py

Populates fairy_scenes_prefs.xml with a broadcast scene that contains all 9 required elements:
1. Screen / Gameplay Capture (BackgroundMode: SCREEN_CAPTURE)
2. Text Overlay (FAIRY LIVE BROADCAST)
3. Image Overlay (overlay_logo.png)
4. GIF Overlay (sample_animation.gif)
5. Stream Goal Overlay (Followers: 350 / 500)
6. Lower Third Overlay (Alex Live / Live on Android 15)
7. QR Code Overlay (https://fairy.live)
8. Diagnostic Stats Widget (480p @ 24fps)
9. Facecam PIP (Enabled: true, front camera)
"""

import json
import subprocess
import time

scenes = [
    {
        "id": "scene_gameplay",
        "name": "Creator Studio Broadcast",
        "orderIndex": 0,
        "backgroundMode": "SCREEN_CAPTURE",
        "backgroundColorArgb": -16777216,
        "facecam": {
            "enabled": True,
            "normalizedX": 0.70,
            "normalizedY": 0.04,
            "normalizedWidth": 0.26,
            "normalizedHeight": 0.26,
            "scale": 1.0,
            "mirrorHorizontal": True
        },
        "overlays": [
            {
                "id": "txt_broadcast_title",
                "type": "TEXT",
                "normalizedX": 0.04,
                "normalizedY": 0.04,
                "normalizedWidth": 0.45,
                "opacity": 0.95,
                "visible": True,
                "zIndex": 10,
                "text": "FAIRY LIVE BROADCAST"
            },
            {
                "id": "img_logo",
                "type": "IMAGE",
                "normalizedX": 0.78,
                "normalizedY": 0.88,
                "normalizedWidth": 0.18,
                "opacity": 0.85,
                "visible": True,
                "zIndex": 12,
                "resourcePath": "asset://overlay_logo.png"
            },
            {
                "id": "gif_stream_orbit",
                "type": "GIF",
                "normalizedX": 0.04,
                "normalizedY": 0.76,
                "normalizedWidth": 0.14,
                "opacity": 0.90,
                "visible": True,
                "zIndex": 14,
                "resourcePath": "asset://sample_animation.gif"
            },
            {
                "id": "goal_followers",
                "type": "GOAL",
                "normalizedX": 0.04,
                "normalizedY": 0.12,
                "normalizedWidth": 0.45,
                "opacity": 0.90,
                "visible": True,
                "zIndex": 15
            },
            {
                "id": "l3_streamer_badge",
                "type": "LOWER_THIRD",
                "normalizedX": 0.04,
                "normalizedY": 0.86,
                "normalizedWidth": 0.60,
                "opacity": 0.95,
                "visible": True,
                "zIndex": 16
            },
            {
                "id": "qr_channel_link",
                "type": "QR",
                "normalizedX": 0.76,
                "normalizedY": 0.68,
                "normalizedWidth": 0.18,
                "opacity": 0.95,
                "visible": True,
                "zIndex": 18
            },
            {
                "id": "widget_perf_stats",
                "type": "WIDGET",
                "normalizedX": 0.04,
                "normalizedY": 0.68,
                "normalizedWidth": 0.38,
                "opacity": 0.85,
                "visible": True,
                "zIndex": 20
            }
        ]
    },
    {
        "id": "scene_chatting",
        "name": "Just Chatting",
        "orderIndex": 1,
        "backgroundMode": "SCREEN_CAPTURE",
        "backgroundColorArgb": -16777216,
        "facecam": {
            "enabled": True,
            "normalizedX": 0.55,
            "normalizedY": 0.05,
            "normalizedWidth": 0.40,
            "normalizedHeight": 0.40,
            "scale": 1.0,
            "mirrorHorizontal": True
        },
        "overlays": [
            {
                "id": "txt_chat_header",
                "type": "TEXT",
                "normalizedX": 0.05,
                "normalizedY": 0.04,
                "normalizedWidth": 0.50,
                "opacity": 0.95,
                "visible": True,
                "zIndex": 10,
                "text": "💬 CHATTING & QA"
            }
        ]
    },
    {
        "id": "scene_brb",
        "name": "Be Right Back",
        "orderIndex": 2,
        "backgroundMode": "SOLID_COLOR",
        "backgroundColorArgb": -15723488,
        "facecam": {
            "enabled": False,
            "normalizedX": 0.70,
            "normalizedY": 0.05,
            "normalizedWidth": 0.26,
            "normalizedHeight": 0.26,
            "scale": 1.0,
            "mirrorHorizontal": True
        },
        "overlays": [
            {
                "id": "txt_brb_title",
                "type": "TEXT",
                "normalizedX": 0.25,
                "normalizedY": 0.42,
                "normalizedWidth": 0.50,
                "opacity": 1.0,
                "visible": True,
                "zIndex": 20,
                "text": "☕ BE RIGHT BACK!"
            },
            {
                "id": "qr_social",
                "type": "QR",
                "normalizedX": 0.38,
                "normalizedY": 0.55,
                "normalizedWidth": 0.24,
                "opacity": 0.95,
                "visible": True,
                "zIndex": 20
            }
        ]
    }
]

scenes_json = json.dumps(scenes)
escaped_json = scenes_json.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")

xml_content = f"""<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="key_scenes_json">{escaped_json}</string>
    <string name="key_active_scene_id">scene_gameplay</string>
</map>
"""

with open("tools/fairy_scenes_prefs_prepared.xml", "w", encoding="utf-8") as f:
    f.write(xml_content)

print("[PREPARE_SCENE] Wrote tools/fairy_scenes_prefs_prepared.xml")

# Push to device
subprocess.run(["adb", "push", "tools/fairy_scenes_prefs_prepared.xml", "/data/local/tmp/fairy_scenes_prefs.xml"], check=True)
subprocess.run(["adb", "shell", "run-as com.fairylive.app cp /data/local/tmp/fairy_scenes_prefs.xml shared_prefs/fairy_scenes_prefs.xml"], check=True)
print("[PREPARE_SCENE] Pushed to app shared_prefs!")
