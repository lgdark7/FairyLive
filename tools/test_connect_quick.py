import socket
import threading
import time
import subprocess
import os

from test_rtmp_server import RtmpChunkParser

server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
server.bind(("0.0.0.0", 19350))
server.listen(5)

video_count = 0
audio_count = 0
running = True

def handle_client(sock):
    global video_count, audio_count, running
    print("[SERVER] Client connected!")
    # Handshake
    c0c1 = sock.recv(1537)
    sock.sendall(b"\x03" + c0c1[1:] + c0c1[1:]) # S0+S1+S2
    c2 = sock.recv(1536)
    print("[SERVER] Handshake completed.")
    parser = RtmpChunkParser(sock)
    while running:
        msg = parser.read_next_message()
        if not msg:
            break
        if msg["type"] == 9:
            video_count += 1
        elif msg["type"] == 8:
            audio_count += 1

t = threading.Thread(target=lambda: [handle_client(server.accept()[0])])
t.daemon = True
t.start()

subprocess.run(["adb", "reverse", "tcp:19350", "tcp:19350"])
subprocess.run(["adb", "shell", "am", "force-stop", "com.fairylive.app"])
time.sleep(1.0)
subprocess.run(["adb", "shell", "am", "start", "-n", "com.fairylive.app/.MainActivity"])
time.sleep(2.0)
# Tap Go Live
subprocess.run(["adb", "shell", "input", "tap", "540", "1166"])
time.sleep(1.5)
# Tap Start on MediaProjection dialog
subprocess.run(["adb", "shell", "input", "tap", "893", "1703"])

print("Streaming for 10 seconds...")
for i in range(10):
    time.sleep(1)
    print(f"Sec {i+1}: Video={video_count}, Audio={audio_count}")

running = False
server.close()
subprocess.run(["adb", "shell", "am", "force-stop", "com.fairylive.app"])
