import socket
import struct
import sys
import time

def read_exact(sock, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("EOF while reading")
        buf.extend(chunk)
    return bytes(buf)

def run_server(port=19350):
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", port))
    server.listen(1)
    print(f"[RTMP_SERVER] Listening on 127.0.0.1:{port}", flush=True)

    conn, addr = server.accept()
    print(f"[RTMP_SERVER] Connection accepted from {addr}", flush=True)

    # 1. Read C0 + C1
    c0 = read_exact(conn, 1)
    c1 = read_exact(conn, 1536)
    print(f"[RTMP_SERVER] C0 version: {c0[0]}, C1 len: {len(c1)}", flush=True)

    # 2. Send S0 + S1 + S2
    s0 = b"\x03"
    s1 = b"\x00" * 1536
    s2 = c1
    conn.sendall(s0 + s1 + s2)
    print(f"[RTMP_SERVER] Sent S0 + S1 + S2", flush=True)

    # 3. Read C2
    c2 = read_exact(conn, 1536)
    print(f"[RTMP_SERVER] Handshake complete! Reading RTMP chunks...", flush=True)

    # 4. Process incoming chunks
    total_bytes = 0
    video_frames = 0
    audio_frames = 0
    in_chunk_size = 128
    start_time = time.time()

    conn.settimeout(10.0)

    try:
        while True:
            # Read chunk basic header
            b = conn.recv(1)
            if not b:
                break
            total_bytes += 1
            b_val = b[0]
            fmt = (b_val >> 6) & 0x03
            csid = b_val & 0x3F

            # If format 0 (11 bytes header)
            if fmt == 0:
                header = read_exact(conn, 11)
                total_bytes += 11
                msg_len = (header[3] << 16) | (header[4] << 8) | header[5]
                msg_type = header[6]

                # If Set Chunk Size (type 1)
                if msg_type == 1:
                    payload = read_exact(conn, msg_len)
                    total_bytes += msg_len
                    in_chunk_size = struct.unpack(">I", payload)[0] & 0x7FFFFFFF
                    print(f"[RTMP_SERVER] Inbound Chunk Size updated to: {in_chunk_size}", flush=True)
                elif msg_type == 20: # AMF0 Command
                    payload = read_exact(conn, msg_len)
                    total_bytes += msg_len
                    # Check for connect
                    if b"connect" in payload:
                        print("[RTMP_SERVER] Received 'connect' command. Responding with _result...", flush=True)
                        # Respond with _result for transaction 1.0
                        res = b"\x03\x00\x00\x00\x00\x00\x45\x14\x00\x00\x00\x00" # Chunk header
                        # AMF0: "_result", 1.0, object, info
                        amf_res = (
                            b"\x02\x00\x07_result"
                            b"\x00\x3f\xf0\x00\x00\x00\x00\x00\x00" # 1.0
                            b"\x03\x00\x00\x09" # empty object
                            b"\x03\x00\x05level\x02\x00\x06status\x00\x04code\x02\x00\x1dNetConnection.Connect.Success\x00\x00\x09"
                        )
                        chunk_header = struct.pack(">B3s3sB4s", 3, b"\x00\x00\x00", struct.pack(">I", len(amf_res))[1:], 20, b"\x00\x00\x00\x00")
                        conn.sendall(chunk_header + amf_res)
                    elif b"createStream" in payload:
                        print("[RTMP_SERVER] Received 'createStream' command. Responding with streamId=1...", flush=True)
                        amf_res = (
                            b"\x02\x00\x07_result"
                            b"\x00\x40\x10\x00\x00\x00\x00\x00\x00" # 4.0
                            b"\x05" # null
                            b"\x00\x3f\xf0\x00\x00\x00\x00\x00\x00" # 1.0 (streamId)
                        )
                        chunk_header = struct.pack(">B3s3sB4s", 3, b"\x00\x00\x00", struct.pack(">I", len(amf_res))[1:], 20, b"\x00\x00\x00\x00")
                        conn.sendall(chunk_header + amf_res)
                    elif b"publish" in payload:
                        print("[RTMP_SERVER] Received 'publish' command! Stream is now LIVE.", flush=True)
                elif msg_type == 18: # Metadata
                    payload = read_exact(conn, msg_len)
                    total_bytes += msg_len
                    print(f"[RTMP_SERVER] Received metadata script tag ({msg_len} bytes)", flush=True)
                elif msg_type == 9: # Video
                    to_read = min(msg_len, in_chunk_size)
                    payload = read_exact(conn, to_read)
                    total_bytes += to_read
                    video_frames += 1
                elif msg_type == 8: # Audio
                    to_read = min(msg_len, in_chunk_size)
                    payload = read_exact(conn, to_read)
                    total_bytes += to_read
                    audio_frames += 1
                else:
                    payload = read_exact(conn, min(msg_len, in_chunk_size))
                    total_bytes += len(payload)
            else:
                # Format 1, 2, or 3
                data = conn.recv(min(in_chunk_size, 4096))
                if not data:
                    break
                total_bytes += len(data)

            if total_bytes > 0 and (time.time() - start_time) > 1.0:
                elapsed = time.time() - start_time
                kbps = (total_bytes * 8) / (elapsed * 1000)
                if video_frames % 20 == 0:
                    print(f"[RTMP_SERVER] Active streaming: {total_bytes} bytes ({kbps:.1f} kbps), {video_frames} video frames, {audio_frames} audio frames", flush=True)

    except Exception as e:
        print(f"[RTMP_SERVER] Session ended: {e}", flush=True)
    finally:
        conn.close()
        server.close()
        print(f"[RTMP_SERVER] Closed. Summary: {total_bytes} bytes received.", flush=True)

if __name__ == "__main__":
    run_server()
