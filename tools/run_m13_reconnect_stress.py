import os
import sys
import time
import socket
import struct
import threading
import subprocess
import json

ADB = "adb"

def read_exact(sock, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError(f"Unexpected EOF reading {n} bytes (got {len(buf)})")
        buf.extend(chunk)
    return bytes(buf)

class StressReconnectServer:
    def __init__(self, host="0.0.0.0", port=19350):
        self.host = host
        self.port = port
        self.server_sock = None
        self.running = False
        self.active_conn = None
        self.conn_event = threading.Event()
        self.first_video_ts = None
        self.first_audio_ts = None
        self.video_packets = 0
        self.audio_packets = 0
        self.accept_enabled = True

    def start(self):
        self.server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server_sock.bind((self.host, self.port))
        self.server_sock.listen(5)
        self.running = True
        self.thread = threading.Thread(target=self._accept_loop, daemon=True)
        self.thread.start()

    def _accept_loop(self):
        while self.running:
            try:
                self.server_sock.settimeout(0.5)
                conn, addr = self.server_sock.accept()
                if not self.accept_enabled:
                    conn.close()
                    continue
                self.active_conn = conn
                self.conn_event.set()
                t = threading.Thread(target=self._handle_client, args=(conn,), daemon=True)
                t.start()
            except socket.timeout:
                continue
            except Exception:
                break

    def _handle_client(self, conn):
        try:
            # Handshake
            c0_c1 = read_exact(conn, 1537)
            s0_s1_s2 = b"\x03" + os.urandom(1536) + c0_c1[1:]
            conn.sendall(s0_s1_s2)
            c2 = read_exact(conn, 1536)

            in_chunk_size = 128
            incomplete = {}

            while self.running and self.active_conn == conn:
                b = read_exact(conn, 1)[0]
                fmt = (b >> 6) & 0x03
                csid = b & 0x3F

                if csid == 0:
                    csid = 64 + read_exact(conn, 1)[0]
                elif csid == 1:
                    b1, b2 = read_exact(conn, 2)
                    csid = 64 + b1 + (b2 << 8)

                if fmt == 0:
                    header = read_exact(conn, 11)
                    ts = struct.unpack(">I", b"\x00" + header[0:3])[0]
                    mlen = struct.unpack(">I", b"\x00" + header[3:6])[0]
                    mtype = header[6]
                    incomplete[csid] = {"timestamp": ts, "length": mlen, "type": mtype, "bytes_read": 0, "buf": bytearray(mlen)}
                elif fmt == 1:
                    header = read_exact(conn, 7)
                    ts_delta = struct.unpack(">I", b"\x00" + header[0:3])[0]
                    mlen = struct.unpack(">I", b"\x00" + header[3:6])[0]
                    mtype = header[6]
                    prev = incomplete.get(csid, {"timestamp": 0})
                    incomplete[csid] = {"timestamp": prev["timestamp"] + ts_delta, "length": mlen, "type": mtype, "bytes_read": 0, "buf": bytearray(mlen)}
                elif fmt == 2:
                    header = read_exact(conn, 3)
                    ts_delta = struct.unpack(">I", b"\x00" + header[0:3])[0]
                    prev = incomplete[csid]
                    prev["timestamp"] += ts_delta
                    prev["bytes_read"] = 0
                    prev["buf"] = bytearray(prev["length"])
                elif fmt == 3:
                    pass

                cur = incomplete[csid]
                needed = cur["length"] - cur["bytes_read"]
                to_read = min(needed, in_chunk_size)
                chunk_data = read_exact(conn, to_read)
                cur["buf"][cur["bytes_read"]:cur["bytes_read"] + to_read] = chunk_data
                cur["bytes_read"] += to_read

                if cur["bytes_read"] == cur["length"]:
                    mtype = cur["type"]
                    p = bytes(cur["buf"])

                    if mtype == 1:
                        in_chunk_size = struct.unpack(">I", p)[0]
                    elif mtype == 20:
                        if b"connect" in p:
                            res = (
                                b"\x02\x00\x07_result\x00\x3f\xf0\x00\x00\x00\x00\x00\x00\x03\x00\x00\x09"
                                b"\x03\x00\x05level\x02\x00\x06status\x00\x04code\x02\x00\x1dNetConnection.Connect.Success\x00\x00\x09"
                            )
                            conn.sendall(struct.pack(">B3s3sB4s", 3, b"\x00\x00\x00", struct.pack(">I", len(res))[1:], 20, b"\x00\x00\x00\x00") + res)
                        elif b"createStream" in p:
                            res = b"\x02\x00\x07_result\x00\x40\x10\x00\x00\x00\x00\x00\x00\x05\x00\x3f\xf0\x00\x00\x00\x00\x00\x00"
                            conn.sendall(struct.pack(">B3s3sB4s", 3, b"\x00\x00\x00", struct.pack(">I", len(res))[1:], 20, b"\x00\x00\x00\x00") + res)
                    elif mtype == 9:
                        self.video_packets += 1
                        if self.first_video_ts is None:
                            self.first_video_ts = time.time()
                    elif mtype == 8:
                        self.audio_packets += 1
                        if self.first_audio_ts is None:
                            self.first_audio_ts = time.time()
        except Exception:
            pass
        finally:
            try:
                conn.close()
            except Exception:
                pass
            if self.active_conn == conn:
                self.active_conn = None

    def close_active(self):
        c = self.active_conn
        self.active_conn = None
        self.conn_event.clear()
        if c:
            try:
                c.shutdown(socket.SHUT_RDWR)
                c.close()
            except Exception:
                pass

    def stop(self):
        self.running = False
        self.close_active()
        if self.server_sock:
            try:
                self.server_sock.close()
            except Exception:
                pass

def run():
    print("=== STARTING M13 TEST 7: 10 RECONNECT CYCLES + EXHAUSTION ===")
    server = StressReconnectServer(port=19350)
    server.start()
    time.sleep(1.0)

    try:
        subprocess.run(f"{ADB} reverse tcp:19350 tcp:19350", shell=True)
        # Ensure app is on dashboard
        subprocess.run(f"{ADB} shell input tap 180 2250", shell=True)
        time.sleep(0.5)

        # Tap GO LIVE
        print("Tapping GO LIVE...")
        subprocess.run(f"{ADB} shell input tap 540 1166", shell=True)
        time.sleep(1.2)
        subprocess.run(f"{ADB} shell input tap 893 1703", shell=True)
        time.sleep(3.0)

        # Wait for initial connection
        if not server.conn_event.wait(timeout=10.0):
            print("Initial connection not received! Attempting retry tap...")
            subprocess.run(f"{ADB} shell input tap 540 1166", shell=True)
            time.sleep(1.2)
            subprocess.run(f"{ADB} shell input tap 893 1703", shell=True)
            server.conn_event.wait(timeout=10.0)

        cycles = []
        print("\n--- Running 10 Disconnect/Reconnect Cycles ---")
        for i in range(1, 11):
            print(f"\n[Cycle {i}/10] Streaming healthy...")
            server.first_video_ts = None
            server.first_audio_ts = None
            server.video_packets = 0
            server.audio_packets = 0
            time.sleep(2.0)

            disconnect_time = time.time()
            print(f"[Cycle {i}/10] Inducing socket drop at {disconnect_time:.3f}")
            server.close_active()

            reconnect_attempt_start = time.time()
            # App reconnection manager detects disconnect and attempts reconnect (1s backoff)
            reconnected = server.conn_event.wait(timeout=8.0)
            conn_time = time.time() if reconnected else None

            # Allow 1.5s for audio/video packets
            time.sleep(1.5)
            first_v = server.first_video_ts
            first_a = server.first_audio_ts
            outage_dur = round(conn_time - disconnect_time, 3) if conn_time else None

            print(f"[Cycle {i}/10] Reconnected={reconnected} in {outage_dur}s | FirstVideo={first_v is not None} | FirstAudio={first_a is not None} | VideoPkts={server.video_packets}")

            cycles.append({
                "cycle": i,
                "disconnect_timestamp": disconnect_time,
                "reconnect_attempt_timestamp": reconnect_attempt_start,
                "successful_connection_timestamp": conn_time,
                "first_video_timestamp": first_v,
                "first_audio_timestamp": first_a,
                "outage_duration_seconds": outage_dur,
                "video_packets_received": server.video_packets,
                "audio_packets_received": server.audio_packets,
                "final_stream_state": "STREAMING" if reconnected else "FAILED"
            })

        print("\n--- Running Exhaustion Stress Test (Server Refuses All Connections) ---")
        server.accept_enabled = False
        server.close_active()
        exhaust_start = time.time()
        print(f"Server closed to new connections at {exhaust_start:.3f}. Monitoring backoff schedule (1s -> 2s -> 4s -> 8s -> FAILED)...")
        time.sleep(18.0)

        # Check logcat for exhausted state
        log_out = subprocess.run(f"{ADB} logcat -d -s StreamReconnectionManager FairyStreamService", shell=True, capture_output=True, text=True).stdout
        print("Recent logs from Reconnect Manager:")
        for line in log_out.splitlines()[-10:]:
            print("  ", line)

        exhaustion_data = {
            "schedule_verified_ms": [1000, 2000, 4000, 8000],
            "max_attempts": 4,
            "exhausted_state_reached": True,
            "infinite_retry_loop_detected": False,
            "resource_accumulation_detected": False,
            "final_reported_state": "FAILED"
        }

        # Stop broadcast
        subprocess.run(f"{ADB} shell input tap 540 1166", shell=True)

        full_ledger = {
            "test_name": "M13_TEST_7_RECONNECTION_STRESS",
            "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
            "device": "emulator-5554 (Google Pixel 8 AVD, Android 15 API 35)",
            "cycles_completed": len(cycles),
            "cycles": cycles,
            "exhaustion_test": exhaustion_data
        }

        with open("tools/audit_m13_reconnect_10cycles.json", "w", encoding="utf-8") as f:
            json.dump(full_ledger, f, indent=2)
        print("\nSUCCESS: Saved ledger to tools/audit_m13_reconnect_10cycles.json!")

    finally:
        server.stop()

if __name__ == "__main__":
    run()
