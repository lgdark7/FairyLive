import sys
import struct
import json

flv_path = sys.argv[1] if len(sys.argv) > 1 else "tools/v11_broadcast.flv"
with open(flv_path, "rb") as f:
    data = f.read()

total_bytes = len(data)
if total_bytes < 9:
    print(f"File too small: {total_bytes}")
    sys.exit(1)

header = data[:9]
sig = header[:3].decode("ascii", errors="ignore")
version = header[3]
flags = header[4]
has_audio = bool(flags & 0x04)
has_video = bool(flags & 0x01)
offset = struct.unpack(">I", header[5:9])[0]

print(f"FLV Header: Signature={sig}, Version={version}, Audio={has_audio}, Video={has_video}, DataOffset={offset}")

pos = offset
prev_tag_size = struct.unpack(">I", data[pos:pos+4])[0]
pos += 4

video_tags = 0
audio_tags = 0
script_tags = 0
keyframe_count = 0
interframe_count = 0
video_pts_list = []
audio_pts_list = []

while pos + 11 <= total_bytes:
    tag_type = data[pos]
    data_size = (data[pos+1] << 16) | (data[pos+2] << 8) | data[pos+3]
    ts_lower = (data[pos+4] << 16) | (data[pos+5] << 8) | data[pos+6]
    ts_upper = data[pos+7]
    timestamp = (ts_upper << 24) | ts_lower
    stream_id = (data[pos+8] << 16) | (data[pos+9] << 8) | data[pos+10]
    
    tag_payload_start = pos + 11
    tag_payload_end = tag_payload_start + data_size
    
    if tag_payload_end > total_bytes:
        print(f"Truncated tag at {pos}: payload needs {data_size} bytes, file ends")
        break
        
    if tag_type == 9: # Video
        video_tags += 1
        video_pts_list.append(timestamp)
        if data_size > 0:
            frame_type = (data[tag_payload_start] >> 4) & 0x0F
            codec_id = data[tag_payload_start] & 0x0F
            if frame_type == 1:
                keyframe_count += 1
            elif frame_type == 2:
                interframe_count += 1
    elif tag_type == 8: # Audio
        audio_tags += 1
        audio_pts_list.append(timestamp)
    elif tag_type == 18: # Script / Metadata
        script_tags += 1
        
    pos = tag_payload_end
    if pos + 4 <= total_bytes:
        pos += 4 # PreviousTagSize
    else:
        break

duration_s = (max(video_pts_list + audio_pts_list) - min(video_pts_list + audio_pts_list)) / 1000.0 if video_pts_list or audio_pts_list else 0

fps = (video_tags / duration_s) if duration_s > 0 else 0
av_sync_delta_ms = abs(video_pts_list[0] - audio_pts_list[0]) if video_pts_list and audio_pts_list else 0

results = {
    "flv_file": flv_path,
    "file_size_bytes": total_bytes,
    "duration_seconds": round(duration_s, 2),
    "video_tags": video_tags,
    "audio_tags": audio_tags,
    "script_tags": script_tags,
    "keyframe_count": keyframe_count,
    "interframe_count": interframe_count,
    "fps": round(fps, 2),
    "av_sync_delta_ms": av_sync_delta_ms,
    "has_audio": has_audio,
    "has_video": has_video
}

print(json.dumps(results, indent=2))
with open("tools/v11_acceptance_metrics.json", "w") as out_f:
    json.dump(results, out_f, indent=2)
