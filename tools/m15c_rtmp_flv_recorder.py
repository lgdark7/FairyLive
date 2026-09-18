#!/usr/bin/env python3
"""
tools/m15c_rtmp_flv_recorder.py

A spec-compliant RTMP Ingest Server and FLV Stream Recorder for Milestone 15-C.
Captures incoming video (H.264 AVC) and audio (AAC) frames from FAIRY LIVE,
saves them to an FLV file (tools/m15c_fresh_broadcast.flv), and computes exact
PTS continuity, A/V synchronization, and transport metrics.
"""

import os
import sys
import time
import socket
import struct
import json

def read_exact(sock, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError(f"EOF reached reading {n} bytes (got {len(buf)})")
        buf.extend(chunk)
    return bytes(buf)

class RtmpChunkParser:
    def __init__(self, sock):
        self.sock = sock
        self.in_chunk_size = 128
        self.incomplete = {}

    def read_next_message(self):
        while True:
            b = read_exact(self.sock, 1)[0]
            fmt = (b >> 6) & 0x03
            csid = b & 0x3F

            if csid == 0:
                csid = 64 + read_exact(self.sock, 1)[0]
            elif csid == 1:
                b1, b2 = read_exact(self.sock, 2)
                csid = 64 + b1 + (b2 << 8)

            if fmt == 0:
                h = read_exact(self.sock, 11)
                ts = (h[0] << 16) | (h[1] << 8) | h[2]
                msg_len = (h[3] << 16) | (h[4] << 8) | h[5]
                msg_type = h[6]
                stream_id = struct.unpack("<I", h[7:11])[0]
                if ts == 0xFFFFFF:
                    ts = struct.unpack(">I", read_exact(self.sock, 4))[0]
                partial = {
                    "csid": csid, "fmt": fmt, "ts": ts, "ts_delta": 0,
                    "len": msg_len, "type": msg_type, "stream_id": stream_id,
                    "payload": bytearray(), "ext": (ts == 0xFFFFFF)
                }
                self.incomplete[csid] = partial

            elif fmt == 1:
                prev = self.incomplete.get(csid)
                h = read_exact(self.sock, 7)
                delta = (h[0] << 16) | (h[1] << 8) | h[2]
                msg_len = (h[3] << 16) | (h[4] << 8) | h[5]
                msg_type = h[6]
                ext = (delta == 0xFFFFFF)
                if ext:
                    delta = struct.unpack(">I", read_exact(self.sock, 4))[0]
                ts = (prev["ts"] + delta) if prev else delta
                partial = {
                    "csid": csid, "fmt": fmt, "ts": ts, "ts_delta": delta,
                    "len": msg_len, "type": msg_type, "stream_id": prev["stream_id"] if prev else 0,
                    "payload": bytearray(), "ext": ext
                }
                self.incomplete[csid] = partial

            elif fmt == 2:
                prev = self.incomplete.get(csid)
                h = read_exact(self.sock, 3)
                delta = (h[0] << 16) | (h[1] << 8) | h[2]
                ext = (delta == 0xFFFFFF)
                if ext:
                    delta = struct.unpack(">I", read_exact(self.sock, 4))[0]
                ts = (prev["ts"] + delta) if prev else delta
                partial = {
                    "csid": csid, "fmt": fmt, "ts": ts, "ts_delta": delta,
                    "len": prev["len"] if prev else 0,
                    "type": prev["type"] if prev else 0,
                    "stream_id": prev["stream_id"] if prev else 0,
                    "payload": bytearray(), "ext": ext
                }
                self.incomplete[csid] = partial

            elif fmt == 3:
                prev = self.incomplete.get(csid)
                if not prev:
                    raise ConnectionError(f"Protocol error: Format 3 chunk received for inactive CSID {csid}")
                if len(prev["payload"]) == 0:
                    prev["ts"] += prev["ts_delta"]
                partial = prev

            needed = partial["len"] - len(partial["payload"])
            chunk_size = min(needed, self.in_chunk_size)
            chunk = read_exact(self.sock, chunk_size)
            partial["payload"].extend(chunk)

            if len(partial["payload"]) == partial["len"]:
                if partial["type"] == 1:
                    new_size = struct.unpack(">I", partial["payload"])[0]
                    self.in_chunk_size = new_size
                res = {
                    "type": partial["type"],
                    "timestamp": partial["ts"],
                    "stream_id": partial["stream_id"],
                    "payload": bytes(partial["payload"]),
                    "csid": csid
                }
                partial["payload"] = bytearray()
                return res

def write_flv_tag(flv_file, tag_type, timestamp, payload):
    data_size = len(payload)
    ts_lower = timestamp & 0x00FFFFFF
    ts_upper = (timestamp >> 24) & 0xFF
    header = bytearray(11)
    header[0] = tag_type
    header[1] = (data_size >> 16) & 0xFF
    header[2] = (data_size >> 8) & 0xFF
    header[3] = data_size & 0xFF
    header[4] = (ts_lower >> 16) & 0xFF
    header[5] = (ts_lower >> 8) & 0xFF
    header[6] = ts_lower & 0xFF
    header[7] = ts_upper
    header[8] = 0
    header[9] = 0
    header[10] = 0
    flv_file.write(header)
    flv_file.write(payload)
    flv_file.write(struct.pack(">I", data_size + 11))

def run_server(port=19350, flv_path="tools/m15c_fresh_broadcast.flv", metrics_path="tools/m15c_broadcast_metrics.json", max_duration_s=45):
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", port))
    server.listen(1)
    server.settimeout(60.0)
    print(f"[RTMP_RECORDER] Listening on 127.0.0.1:{port}...", flush=True)

    try:
        conn, addr = server.accept()
        print(f"[RTMP_RECORDER] Connection accepted from {addr}", flush=True)
    except Exception as e:
        print(f"[RTMP_RECORDER] Accept failed: {e}", flush=True)
        server.close()
        return

    flv = open(flv_path, "wb")
    # FLV Header: Signature 'FLV', Version 1, Flags (0x05 = Audio + Video), HeaderSize 9
    flv.write(b"FLV\x01\x05\x00\x00\x00\x09\x00\x00\x00\x00")

    # RTMP Handshake
    c0 = read_exact(conn, 1)[0]
    c1 = read_exact(conn, 1536)
    s0 = b"\x03"
    s1 = os.urandom(1536)
    s2 = c1
    conn.sendall(s0 + s1 + s2)
    c2 = read_exact(conn, 1536)
    print("[RTMP_RECORDER] Handshake complete", flush=True)

    parser = RtmpChunkParser(conn)
    total_bytes = 0
    video_packets = []
    audio_packets = []
    start_time = None

    try:
        while True:
            msg = parser.read_next_message()
            total_bytes += len(msg["payload"])
            if start_time is None:
                start_time = time.time()

            mtype = msg["type"]
            p = msg["payload"]
            ts = msg["timestamp"]

            if mtype == 20: # AMF0 Command
                if b"connect" in p:
                    res = (
                        b"\x02\x00\x07_result"
                        b"\x00\x3f\xf0\x00\x00\x00\x00\x00\x00"
                        b"\x03\x00\x00\x09"
                        b"\x03\x00\x05level\x02\x00\x06status\x00\x04code\x02\x00\x1dNetConnection.Connect.Success\x00\x00\x09"
                    )
                    chunk_header = struct.pack(">B3s3sB4s", 3, b"\x00\x00\x00", struct.pack(">I", len(res))[1:], 20, b"\x00\x00\x00\x00")
                    conn.sendall(chunk_header + res)
                elif b"createStream" in p:
                    res = (
                        b"\x02\x00\x07_result"
                        b"\x00\x40\x10\x00\x00\x00\x00\x00\x00"
                        b"\x05"
                        b"\x00\x3f\xf0\x00\x00\x00\x00\x00\x00"
                    )
                    chunk_header = struct.pack(">B3s3sB4s", 3, b"\x00\x00\x00", struct.pack(">I", len(res))[1:], 20, b"\x00\x00\x00\x00")
                    conn.sendall(chunk_header + res)
                elif b"publish" in p:
                    print("[RTMP_RECORDER] Stream PUBLISH command received! Broadcast LIVE.", flush=True)

            elif mtype == 9: # Video
                is_seq = (len(p) > 1 and p[1] == 0)
                video_packets.append({"ts": ts, "size": len(p), "is_seq": is_seq})
                write_flv_tag(flv, 9, ts, p)
            elif mtype == 8: # Audio
                is_seq = (len(p) > 1 and p[1] == 0)
                audio_packets.append({"ts": ts, "size": len(p), "is_seq": is_seq})
                write_flv_tag(flv, 8, ts, p)

            if len(video_packets) > 0 and len(video_packets) % 30 == 0:
                elapsed = time.time() - start_time
                kbps = (total_bytes * 8) / (elapsed * 1000)
                print(f"[RTMP_RECORDER] Elapsed: {elapsed:.1f}s | Video: {len(video_packets)} frames | Audio: {len(audio_packets)} AUs | Rate: {kbps:.1f} kbps", flush=True)

            if start_time and (time.time() - start_time) > max_duration_s:
                print(f"[RTMP_RECORDER] Target recording duration {max_duration_s}s reached", flush=True)
                break

    except Exception as e:
        print(f"[RTMP_RECORDER] Stream connection closed: {e}", flush=True)
    finally:
        flv.close()
        conn.close()
        server.close()

    elapsed = (time.time() - start_time) if start_time else 0
    print(f"\n[RTMP_RECORDER] Recording complete: {len(video_packets)} video, {len(audio_packets)} audio, {total_bytes} bytes in {elapsed:.2f}s", flush=True)

    # Calculate exact media metrics
    first_v_ts = video_packets[0]["ts"] if video_packets else 0
    last_v_ts = video_packets[-1]["ts"] if video_packets else 0
    v_span = last_v_ts - first_v_ts

    first_a_ts = audio_packets[0]["ts"] if audio_packets else 0
    last_a_ts = audio_packets[-1]["ts"] if audio_packets else 0
    a_span = last_a_ts - first_a_ts

    # AAC PTS continuity check across raw audio access units
    raw_audio_packets = [p for p in audio_packets if not p.get("is_seq", False)]
    check_packets = raw_audio_packets if raw_audio_packets else audio_packets

    a_gaps = []
    a_gaps_over_40ms = 0
    for i in range(1, len(check_packets)):
        delta = check_packets[i]["ts"] - check_packets[i-1]["ts"]
        a_gaps.append(delta)
        if delta > 40:
            a_gaps_over_40ms += 1

    max_a_gap = max(a_gaps) if a_gaps else 0
    avg_a_gap = (sum(a_gaps) / len(a_gaps)) if a_gaps else 0

    # Sample-derived audio duration: 1024 samples per AAC frame @ 44100Hz = 23.21995ms
    sample_derived_duration_ms = len(audio_packets) * (1024.0 / 44100.0) * 1000.0
    discrepancy_ms = abs(a_span - sample_derived_duration_ms) if audio_packets else 0
    av_sync_delta_ms = abs(last_v_ts - last_a_ts) if (video_packets and audio_packets) else 0

    metrics = {
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
        "duration_seconds": round(elapsed, 2),
        "total_bytes_received": total_bytes,
        "average_bitrate_kbps": round((total_bytes * 8) / (elapsed * 1000), 2) if elapsed > 0 else 0,
        "video": {
            "total_frames": len(video_packets),
            "first_pts_ms": first_v_ts,
            "last_pts_ms": last_v_ts,
            "pts_span_ms": v_span,
            "fps": round(len(video_packets) / (v_span / 1000.0), 2) if v_span > 0 else 0,
            "total_bytes": sum(p["size"] for p in video_packets)
        },
        "audio": {
            "total_access_units": len(audio_packets),
            "first_pts_ms": first_a_ts,
            "last_pts_ms": last_a_ts,
            "pts_span_ms": a_span,
            "sample_derived_duration_ms": round(sample_derived_duration_ms, 2),
            "span_vs_sample_discrepancy_ms": round(discrepancy_ms, 2),
            "max_consecutive_pts_gap_ms": max_a_gap,
            "gaps_over_40ms": a_gaps_over_40ms,
            "avg_pts_delta_ms": round(avg_a_gap, 2),
            "total_bytes": sum(p["size"] for p in audio_packets)
        },
        "av_sync": {
            "final_delta_ms": av_sync_delta_ms,
            "status": "IN_SYNC" if av_sync_delta_ms < 60 else "DRIFT_DETECTED"
        },
        "flv_file": flv_path
    }

    with open(metrics_path, "w") as f:
        json.dump(metrics, f, indent=2)
    print(f"[RTMP_RECORDER] Metrics saved to {metrics_path}")

if __name__ == "__main__":
    dur = int(sys.argv[1]) if len(sys.argv) > 1 else 35
    flv = sys.argv[2] if len(sys.argv) > 2 else "tools/m15c_fresh_broadcast.flv"
    metrics = sys.argv[3] if len(sys.argv) > 3 else "tools/m15c_broadcast_metrics.json"
    run_server(port=19350, flv_path=flv, metrics_path=metrics, max_duration_s=dur)
