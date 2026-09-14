import socket
import struct
import sys
import time
import argparse

def read_exact(sock, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError(f"Unexpected EOF reading {n} bytes (got {len(buf)})")
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

            needed = partial["length"] - partial["bytes_read"]
            to_read = min(needed, self.in_chunk_size)
            chunk = read_exact(self.sock, to_read)
            partial["buf"][partial["bytes_read"]:partial["bytes_read"] + len(chunk)] = chunk
            partial["bytes_read"] += len(chunk)

            if partial["bytes_read"] == partial["length"]:
                if partial["type"] == 1:
                    new_size = struct.unpack(">I", partial["buf"])[0]
                    self.in_chunk_size = new_size
                return {
                    "csid": partial["csid"],
                    "type": partial["type"],
                    "timestamp": partial["timestamp"],
                    "payload": bytes(partial["buf"])
                }

def handle_handshake(sock):
    c0c1 = read_exact(sock, 1537)
    s0s1s2 = bytearray(3073)
    s0s1s2[0] = 3
    s0s1s2[1537:3073] = c0c1[1:1537]
    sock.sendall(s0s1s2)
    read_exact(sock, 1536)

def run_fault_server():
    parser = argparse.ArgumentParser(description="FAIRY LIVE M9 Fault-Injection RTMP Server")
    parser.add_argument("--port", type=int, default=19350)
    parser.add_argument("--throttle-kbps", type=float, default=0.0, help="Throttle reception to simulate low bandwidth")
    parser.add_argument("--disconnect-after", type=float, default=0.0, help="Force socket disconnect after N seconds")
    parser.add_argument("--disconnect-count", type=int, default=0, help="Number of times to force disconnect")
    parser.add_argument("--fail-reconnect", action="store_true", help="Reject reconnect attempts")
    parser.add_argument("--max-sessions", type=int, default=1, help="Max connection sessions before exit")
    parser.add_argument("--total-duration", type=float, default=120.0, help="Max total server runtime")
    args = parser.parse_args()

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("0.0.0.0", args.port))
    server.listen(5)
    server.settimeout(max(args.total_duration, 60.0))
    print(f"[FAULT_SERVER] Listening on 0.0.0.0:{args.port}", flush=True)
    print(f"[FAULT_SERVER] Config: throttle={args.throttle_kbps}kbps, disconnect_after={args.disconnect_after}s, disconnect_count={args.disconnect_count}, fail_reconnect={args.fail_reconnect}", flush=True)

    session_num = 0
    disconnects_triggered = 0
    global_start = time.time()

    try:
        while session_num < args.max_sessions and (time.time() - global_start) < args.total_duration:
            print(f"[FAULT_SERVER] Waiting for connection (session {session_num + 1}/{args.max_sessions})...", flush=True)
            try:
                conn, addr = server.accept()
            except socket.timeout:
                print("[FAULT_SERVER] Server accept timed out", flush=True)
                break

            session_num += 1
            print(f"[FAULT_SERVER] Session #{session_num} accepted from {addr}", flush=True)

            if session_num > 1 and args.fail_reconnect:
                print(f"[FAULT_SERVER] Session #{session_num}: FAILING RECONNECT AS CONFIGURED (immediate close)", flush=True)
                conn.close()
                continue

            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            handle_handshake(conn)
            chunk_parser = RtmpChunkParser(conn)

            session_start = time.time()
            total_bytes = 0
            video_packets = 0
            audio_packets = 0
            first_v_ts = None
            last_v_ts = None
            throttle_bytes_window = 0
            throttle_time_window = time.time()

            try:
                while True:
                    if (time.time() - global_start) >= args.total_duration:
                        print("[FAULT_SERVER] Total runtime limit reached", flush=True)
                        break

                    # Check forced disconnect
                    if args.disconnect_after > 0 and (time.time() - session_start) >= args.disconnect_after:
                        if disconnects_triggered < args.disconnect_count or args.disconnect_count == 0:
                            disconnects_triggered += 1
                            print(f"[FAULT_SERVER] FORCING DISCONNECT #{disconnects_triggered} after {time.time() - session_start:.2f}s", flush=True)
                            conn.close()
                            break

                    # Artificial bandwidth throttling
                    if args.throttle_kbps > 0:
                        max_bytes_per_sec = (args.throttle_kbps * 1000) / 8.0
                        now = time.time()
                        window_elapsed = now - throttle_time_window
                        if window_elapsed >= 0.1:
                            expected_time = throttle_bytes_window / max_bytes_per_sec
                            if expected_time > window_elapsed:
                                sleep_sec = min(expected_time - window_elapsed, 0.5)
                                time.sleep(sleep_sec)
                            throttle_bytes_window = 0
                            throttle_time_window = time.time()

                    msg = chunk_parser.read_next_message()
                    payload_len = len(msg["payload"])
                    total_bytes += payload_len
                    throttle_bytes_window += payload_len

                    mtype = msg["type"]
                    p = msg["payload"]

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
                            print(f"[FAULT_SERVER] Session #{session_num}: Stream published and active!", flush=True)

                    elif mtype == 9: # Video
                        video_packets += 1
                        if first_v_ts is None:
                            first_v_ts = msg["timestamp"]
                        last_v_ts = msg["timestamp"]

                    elif mtype == 8: # Audio
                        audio_packets += 1

                    if video_packets > 0 and video_packets % 30 == 0:
                        el = time.time() - session_start
                        curr_kbps = (total_bytes * 8) / (el * 1000) if el > 0 else 0
                        print(f"[FAULT_SERVER] Session #{session_num} active: {total_bytes} bytes ({curr_kbps:.1f} kbps), {video_packets} video, {audio_packets} audio", flush=True)

            except Exception as e:
                print(f"[FAULT_SERVER] Session #{session_num} disconnected: {e}", flush=True)
            finally:
                conn.close()
                el = time.time() - session_start
                print(f"[FAULT_SERVER] Session #{session_num} summary: duration={el:.2f}s, bytes={total_bytes}, video={video_packets}, audio={audio_packets}", flush=True)
                if first_v_ts is not None and last_v_ts is not None:
                    print(f"[FAULT_SERVER]   video_ts: first={first_v_ts}ms, last={last_v_ts}ms, span={last_v_ts - first_v_ts}ms", flush=True)

    finally:
        server.close()
        print(f"[FAULT_SERVER] Server finished. Total sessions: {session_num}, Disconnects forced: {disconnects_triggered}", flush=True)

if __name__ == "__main__":
    run_fault_server()
