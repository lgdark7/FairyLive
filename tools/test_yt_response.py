import socket
import struct
import time

def read_exact(s, n):
    buf = b''
    while len(buf) < n:
        chunk = s.recv(n - len(buf))
        if not chunk:
            raise EOFError(f"EOF reading {n} bytes, got {len(buf)}")
        buf += chunk
    return buf

def amf_str(s):
    b = s.encode('utf-8')
    return b'\x02' + struct.pack('>H', len(b)) + b

def amf_num(n):
    return b'\x00' + struct.pack('>d', float(n))

def amf_null():
    return b'\x05'

def amf_bool(b):
    return b'\x01' + (b'\x01' if b else b'\x00')

def main():
    s = socket.socket()
    s.settimeout(6.0)
    s.connect(('a.rtmp.youtube.com', 1935))

    # Handshake
    c1 = b'\x00'*8 + b'\x01'*1528
    s.sendall(b'\x03' + c1)
    s0 = read_exact(s, 1)
    s1 = read_exact(s, 1536)
    s.sendall(s1)
    s2 = read_exact(s, 1536)
    print("Handshake complete!")

    # SetChunkSize 4096 (CSID 2, msg len 4, type 1, stream 0)
    # fmt=0, csid=2: \x02, timestamp 00 00 00, length 00 00 04, type 01, streamId 00 00 00 00, payload 00 00 10 00
    s.sendall(b'\x02\x00\x00\x00\x00\x00\x04\x01\x00\x00\x00\x00\x00\x00\x10\x00')

    # Connect command
    obj = amf_str('app') + amf_str('live2') + \
          amf_str('flashVer') + amf_str('FMLE/3.0 (compatible; FairyLive)') + \
          amf_str('tcUrl') + amf_str('rtmp://a.rtmp.youtube.com/live2') + \
          amf_str('fpad') + amf_bool(False) + \
          amf_str('capabilities') + amf_num(15.0) + \
          amf_str('audioCodecs') + amf_num(3191.0) + \
          amf_str('videoCodecs') + amf_num(252.0) + \
          amf_str('videoFunction') + amf_num(1.0) + \
          b'\x00\x00\x09'

    conn_payload = amf_str('connect') + amf_num(1.0) + b'\x03' + obj
    hdr = b'\x03\x00\x00\x00' + struct.pack('>I', len(conn_payload))[1:] + b'\x14\x00\x00\x00\x00'
    s.sendall(hdr + conn_payload)

    # Read packets until _result
    resp = b''
    for _ in range(20):
        chunk = s.recv(4096)
        if not chunk: break
        resp += chunk
        if b'_result' in resp:
            print("Received _result for connect!")
            break

    # releaseStream
    dummy_key = "invalid-test-key-1234"
    rel = amf_str('releaseStream') + amf_num(2.0) + amf_null() + amf_str(dummy_key)
    s.sendall(b'\x03\x00\x00\x00' + struct.pack('>I', len(rel))[1:] + b'\x14\x00\x00\x00\x00' + rel)

    # FCPublish
    fc = amf_str('FCPublish') + amf_num(3.0) + amf_null() + amf_str(dummy_key)
    s.sendall(b'\x03\x00\x00\x00' + struct.pack('>I', len(fc))[1:] + b'\x14\x00\x00\x00\x00' + fc)

    # createStream
    cs = amf_str('createStream') + amf_num(4.0) + amf_null()
    s.sendall(b'\x03\x00\x00\x00' + struct.pack('>I', len(cs))[1:] + b'\x14\x00\x00\x00\x00' + cs)

    for _ in range(20):
        chunk = s.recv(4096)
        if not chunk: break
        resp += chunk
        if resp.count(b'_result') >= 2:
            print("Received _result for createStream!")
            break

    # Publish
    pub = amf_str('publish') + amf_num(5.0) + amf_null() + amf_str(dummy_key) + amf_str('live')
    s.sendall(b'\x08\x00\x00\x00' + struct.pack('>I', len(pub))[1:] + b'\x14\x01\x00\x00\x00' + pub)
    print("Sent publish! Listening for server response...")

    s.settimeout(3.0)
    try:
        while True:
            chunk = s.recv(4096)
            if not chunk:
                print("Server closed TCP connection cleanly (0 bytes / FIN)!")
                break
            print("Server sent bytes:", len(chunk), repr(chunk[:150]))
    except Exception as e:
        print("Socket read ended with:", e)
    s.close()

if __name__ == '__main__':
    main()
