import os
import sys
import time
import socket
import struct
import threading
import subprocess
import json
import re

from test_rtmp_server import RtmpChunkParser

ADB = "adb"

def run_cmd(cmd, timeout=30):
    p = subprocess.run(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=timeout)
    return p.stdout.strip()

def read_exact(sock, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError(f"Unexpected EOF reading {n} bytes (got {len(buf)})")
        buf.extend(chunk)
    return bytes(buf)

class ProbeRtmpServer:
    def __init__(self, port=19350, flv_path="tools/test_probe.flv"):
        self.port = port
        self.flv_path = flv_path
        self.server_sock = None
        self.running = False
        self.active_conn = None
        self.audio_tags = []
        self.video_tags = []
        self.flv_file = open(self.flv_path, "wb")
        # FLV header: FLV 1.0, Audio + Video (0x05), Header length 9, PreviousTagSize0 (4 zero bytes)
        self.flv_file.write(b"FLV\x01\x05\x00\x00\x00\x09\x00\x00\x00\x00")

    def start(self):
        self.server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server_sock.bind(("127.0.0.1", self.port))
        self.server_sock.listen(5)
        self.running = True
        self.thread = threading.Thread(target=self._accept_loop, daemon=True)
        self.thread.start()

    def _accept_loop(self):
        while self.running:
            try:
                self.server_sock.settimeout(1.0)
                conn, addr = self.server_sock.accept()
                self.active_conn = conn
                t = threading.Thread(target=self._handle_client, args=(conn,), daemon=True)
                t.start()
            except socket.timeout:
                continue
            except Exception:
                break

    def _handle_client(self, conn):
        try:
            c0_c1 = read_exact(conn, 1537)
            s0_s1_s2 = b"\x03" + os.urandom(1536) + c0_c1[1:]
            conn.sendall(s0_s1_s2)
            c2 = read_exact(conn, 1536)
            parser = RtmpChunkParser(conn)
            while self.running and self.active_conn == conn:
                msg = parser.read_next_message()
                mtype = msg["type"]
                p = msg["payload"]
                pts = msg["timestamp"]

                if mtype in (8, 9, 18):
                    # Write to FLV file
                    tag_type = mtype
                    data_size = len(p)
                    ts_low = pts & 0x00FFFFFF
                    ts_high = (pts >> 24) & 0xFF
                    header = struct.pack(">BBBBBBBBBBB",
                        tag_type,
                        (data_size >> 16) & 0xFF,
                        (data_size >> 8) & 0xFF,
                        data_size & 0xFF,
                        (ts_low >> 16) & 0xFF,
                        (ts_low >> 8) & 0xFF,
                        ts_low & 0xFF,
                        ts_high,
                        0, 0, 0
                    )
                    prev_tag_size = struct.pack(">I", 11 + len(p))
                    self.flv_file.write(header + p + prev_tag_size)
                    self.flv_file.flush()

                if mtype == 20:
                    if b"connect" in p:
                        res = (
                            b"\x02\x00\x07_result\x00\x3f\xf0\x00\x00\x00\x00\x00\x00\x03\x00\x00\x09"
                            b"\x03\x00\x05level\x02\x00\x06status\x00\x04code\x02\x00\x1dNetConnection.Connect.Success\x00\x00\x09"
                        )
                        chunk_header = struct.pack(">B3s3sB4s", 3, b"\x00\x00\x00", struct.pack(">I", len(res))[1:], 20, b"\x00\x00\x00\x00")
                        conn.sendall(chunk_header + res)
                    elif b"createStream" in p:
                        res = b"\x02\x00\x07_result\x00\x40\x10\x00\x00\x00\x00\x00\x00\x05\x00\x3f\xf0\x00\x00\x00\x00\x00\x00"
                        chunk_header = struct.pack(">B3s3sB4s", 3, b"\x00\x00\x00", struct.pack(">I", len(res))[1:], 20, b"\x00\x00\x00\x00")
                        conn.sendall(chunk_header + res)
                elif mtype == 9:
                    self.video_tags.append({"pts": pts, "size": len(p)})
                elif mtype == 8:
                    aac_packet_type = p[1] if len(p) > 1 else -1
                    self.audio_tags.append({
                        "pts": pts,
                        "size": len(p),
                        "aac_type": aac_packet_type,
                        "payload_hex": p[:8].hex()
                    })
        except Exception:
            pass

    def stop(self):
        self.running = False
        if self.active_conn:
            try:
                self.active_conn.close()
            except Exception:
                pass
        if self.server_sock:
            try:
                self.server_sock.close()
            except Exception:
                pass
        try:
            self.flv_file.close()
        except Exception:
            pass

def main():
    server = ProbeRtmpServer()
    server.start()
    run_cmd(f"{ADB} reverse tcp:19350 tcp:19350")
    run_cmd(f"{ADB} shell am force-stop com.fairylive.app")
    time.sleep(1.0)
    run_cmd(f"{ADB} shell am start -n com.fairylive.app/.MainActivity")
    time.sleep(2.0)
    run_cmd(f"{ADB} shell input tap 180 2250")
    time.sleep(0.5)
    run_cmd(f"{ADB} shell input tap 540 1166")
    time.sleep(1.2)
    run_cmd(f"{ADB} shell input tap 893 1703")
    
    print("[PROBE] Streaming for 20 seconds...")
    time.sleep(20)
    
    run_cmd(f"{ADB} shell input tap 540 1166")
    time.sleep(1.0)
    run_cmd(f"{ADB} shell am force-stop com.fairylive.app")
    server.stop()
    
    print(f"Captured {len(server.video_tags)} video frames, {len(server.audio_tags)} audio packets.")
    if server.audio_tags:
        print(f"First audio PTS: {server.audio_tags[0]['pts']} ms, Last: {server.audio_tags[-1]['pts']} ms")
        span = server.audio_tags[-1]['pts'] - server.audio_tags[0]['pts']
        print(f"Audio PTS Span: {span} ms ({span/1000.0:.3f} s)")
        raw_aus = [t for t in server.audio_tags if t['aac_type'] == 1]
        print(f"Raw AAC AUs: {len(raw_aus)}")
        count_dur = len(raw_aus) * 1024 / 44100.0
        print(f"Sample-count duration: {count_dur:.3f} s")
        print(f"Discrepancy: {abs(span/1000.0 - count_dur):.4f} s")

if __name__ == "__main__":
    main()
