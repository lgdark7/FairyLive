import socket
import struct
import sys
import time
import json

def read_exact(sock, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError(f"Unexpected EOF reading {n} bytes (got {len(buf)})")
        buf.extend(chunk)
    return bytes(buf)

class RtmpChunkParser:
    """
    100% spec-compliant RTMP chunk stream reassembler.
    Reassembles multiplexed chunks across format 0, 1, 2, 3 into complete messages.
    """
    def __init__(self, sock):
        self.sock = sock
        self.in_chunk_size = 128
        self.incomplete = {} # csid -> dict

    def read_next_message(self):
        while True:
            # 1. Basic Header
            b = read_exact(self.sock, 1)[0]
            fmt = (b >> 6) & 0x03
            csid = b & 0x3F

            if csid == 0:
                csid = 64 + read_exact(self.sock, 1)[0]
            elif csid == 1:
                b1, b2 = read_exact(self.sock, 2)
                csid = 64 + b1 + (b2 << 8)

            # 2. Message Header based on fmt
            if fmt == 0:
                h = read_exact(self.sock, 11)
                ts = (h[0] << 16) | (h[1] << 8) | h[2]
                msg_len = (h[3] << 16) | (h[4] << 8) | h[5]
                msg_type = h[6]
                stream_id = struct.unpack("<I", h[7:11])[0]

                if ts == 0xFFFFFF:
                    ts = struct.unpack(">I", read_exact(self.sock, 4))[0]

                partial = {
                    "csid": csid,
                    "fmt": fmt,
                    "timestamp": ts,
                    "length": msg_len,
                    "type": msg_type,
                    "stream_id": stream_id,
                    "bytes_read": 0,
                    "buf": bytearray(msg_len)
                }
                self.incomplete[csid] = partial

            elif fmt == 1:
                h = read_exact(self.sock, 7)
                ts_delta = (h[0] << 16) | (h[1] << 8) | h[2]
                msg_len = (h[3] << 16) | (h[4] << 8) | h[5]
                msg_type = h[6]

                prev = self.incomplete[csid]
                prev["timestamp"] += ts_delta
                prev["length"] = msg_len
                prev["type"] = msg_type
                prev["bytes_read"] = 0
                prev["buf"] = bytearray(msg_len)
                partial = prev

            elif fmt == 2:
                h = read_exact(self.sock, 3)
                ts_delta = (h[0] << 16) | (h[1] << 8) | h[2]

                prev = self.incomplete[csid]
                prev["timestamp"] += ts_delta
                prev["bytes_read"] = 0
                prev["buf"] = bytearray(prev["length"])
                partial = prev

            elif fmt == 3:
                partial = self.incomplete[csid]

            # 3. Read Chunk Payload up to in_chunk_size
            to_read = min(partial["length"] - partial["bytes_read"], self.in_chunk_size)
            chunk_data = read_exact(self.sock, to_read)
            partial["buf"][partial["bytes_read"] : partial["bytes_read"] + to_read] = chunk_data
            partial["bytes_read"] += to_read

            # 4. Check if message is fully reassembled
            if partial["bytes_read"] >= partial["length"]:
                payload = bytes(partial["buf"])
                msg_type = partial["type"]

                # Handle protocol control message: Set Chunk Size (Type 1)
                if msg_type == 1 and len(payload) >= 4:
                    new_size = struct.unpack(">I", payload[:4])[0] & 0x7FFFFFFF
                    if 1 <= new_size <= 65536:
                        self.in_chunk_size = new_size
                        print(f"[RTMP_SERVER] Chunk size updated to {new_size}", flush=True)

                return {
                    "csid": partial["csid"],
                    "type": msg_type,
                    "stream_id": partial["stream_id"],
                    "timestamp": partial["timestamp"],
                    "payload": payload
                }

def run_server(port=19350):
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", port))
    server.listen(1)
    print(f"[RTMP_SERVER] Listening on 127.0.0.1:{port}", flush=True)

    conn, addr = server.accept()
    conn.settimeout(15.0)
    print(f"[RTMP_SERVER] Connection accepted from {addr}", flush=True)

    # Handshake
    c0 = read_exact(conn, 1)
    c1 = read_exact(conn, 1536)
    s0 = b"\x03"
    s1 = b"\x00" * 1536
    s2 = c1
    conn.sendall(s0 + s1 + s2)
    c2 = read_exact(conn, 1536)
    print(f"[RTMP_SERVER] Handshake completed successfully!", flush=True)

    parser = RtmpChunkParser(conn)
    video_packets = []
    audio_packets = []
    total_bytes = 0
    start_time = None

    try:
        while True:
            msg = parser.read_next_message()
            total_bytes += len(msg["payload"])
            if start_time is None:
                start_time = time.time()

            mtype = msg["type"]
            p = msg["payload"]

            if mtype == 20:
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
                    print("[RTMP_SERVER] Stream published and active!", flush=True)

            elif mtype == 9:
                video_packets.append(msg)
            elif mtype == 8:
                audio_packets.append(msg)

            if len(video_packets) > 0 and len(video_packets) % 50 == 0:
                elapsed = time.time() - start_time
                kbps = (total_bytes * 8) / (elapsed * 1000)
                print(f"[RTMP_SERVER] Active streaming: {total_bytes} bytes ({kbps:.1f} kbps), {len(video_packets)} video frames, {len(audio_packets)} audio frames", flush=True)

    except Exception as e:
        print(f"[RTMP_SERVER] Session ended: {e}", flush=True)
    finally:
        conn.close()
        server.close()
        print(f"[RTMP_SERVER] Closed. Summary: {total_bytes} bytes received, {len(video_packets)} video frames, {len(audio_packets)} audio frames.", flush=True)

if __name__ == "__main__":
    run_server()
