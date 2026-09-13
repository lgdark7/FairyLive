# FAIRY LIVE — ARCHITECTURAL SPECIFICATION

## 1. Architectural Philosophy
Fairy Live is a pure native Android streaming application engineered from the ground up to achieve professional, rock-solid broadcast stability on low-end Android smartphones (2GB–4GB RAM, MediaTek Helio / Unisoc / low-tier Snapdragon SoCs).

### Core Pillars:
1. **Zero-Copy Surface Video Pipeline**: Display rendering directly writes to the MediaCodec input Surface via VirtualDisplay. No Bitmaps, no CPU frame copies, no memory leaks.
2. **Deterministic Monotonic Timing**: Single monotonic reference origin (`MediaClock`) anchored to `SystemClock.elapsedRealtimeNanos()` at stream initiation. Video and audio derive identical relative timestamps ($PTS_{FLV} = PTS_{\mu s} / 1000$).
3. **Pure Native Kotlin Transport**: Zero external bloated C++/Java streaming libraries. Pure Kotlin RTMP client, FLV multiplexer, and AMF0 codec.
4. **Bounded Backpressure Buffering**: Strict 4 MB / 90-packet ceiling. Selective eviction of non-keyframe delta video frames under network congestion; absolute protection of sequence headers and keyframes.
5. **Radical Technical Honesty**: Codecs are audited and badged with their genuine hardware/software capability.

---

## 2. Global Pipeline Architecture

```text
[ ScreenCaptureEngine ]
   │
   │ (Direct Hardware Surface Render)
   ▼
[ HardwareVideoEncoder (MediaCodec H.264) ] ───► EncodedFrame (Annex-B)
                                                       │
[ AudioCaptureEngine (AudioRecord 44.1kHz PCM) ]        │
   │                                                   │
   ▼                                                   │
[ AacMediaCodecEncoder (MediaCodec AAC-LC) ] ──► EncodedAudioFrame (Raw AAC)
                                                       │
              ┌────────────────────────────────────────┘
              ▼
   [ StreamPipeline ]
      ├── H264FlvPacketizer (Annex-B -> AVCDecoderConfigurationRecord + Length-prefixed NALUs)
      ├── AacFlvPacketizer (Raw AAC -> AudioSpecificConfig + FLV Audio Tags)
      ├── FlvMetadataBuilder (@setDataFrame onMetaData Script Tags)
      │
      ▼
   [ BoundedStreamQueue (Backpressure: Drops delta video frames on congestion) ]
      │
      ▼
   [ RtmpClient ]
      ├── RtmpHandshake (C0/C1 -> S0/S1 -> C2 -> S2)
      ├── RtmpChunkWriter (4096-byte chunk negotiation, CSID multiplexing)
      ├── AMF0 Codec (connect, createStream, publish)
      └── TCP Socket (tcpNoDelay=true, bounded network buffer)
            │
            ▼
   [ RTMP Ingest Server (YouTube, Twitch, NGINX-RTMP, etc.) ]
```

---

## 3. Video Pipeline Specification
- **Capture**: `MediaProjection` -> `VirtualDisplay` -> `MediaCodec.createInputSurface()`.
- **Dimensions**: Adaptive scaling locked to even integers: Low (480x1056), Balanced (720x1584), High (1080x2376).
- **Profile**: Baseline profile (no B-frames) ensuring Decode Time == Presentation Time (CompositionTime = 0).
- **Keyframe Interval**: 1-second GOP with on-demand IDR sync keyframe insertion via `PARAMETER_KEY_REQUEST_SYNC_FRAME`.

---

## 4. Audio Pipeline Specification
- **Ingestion**: `AudioRecord` at 44.1 kHz, 16-bit Mono Linear PCM, standard buffer size (2,816 bytes).
- **Encoder**: MediaCodec `audio/mp4a-latm` (AAC-LC) at 96 kbps CBR.
- **Mute Strategy**: Silence generation (zeroed PCM array) to maintain uninterrupted sample accumulation and prevent downstream PTS discontinuities.

---

## 5. Streaming Transport & FLV Multiplexing Specification
- **Container**: Adobe FLV format tags mapped directly into RTMP chunk stream.
- **FLV Video Tag**:
  * Sequence Header: `0x17 0x00 0x00 0x00 0x00` + `AVCDecoderConfigurationRecord` (SPS/PPS).
  * Video NALU: `0x17` (Key) or `0x27` (Inter) + `0x01` + CTS (3 bytes 0x00) + 4-byte big-endian length-prefixed NALUs.
- **FLV Audio Tag**:
  * Sequence Header: `0xAE 0x00` + `AudioSpecificConfig` (2 bytes, `0x12 0x08` for 44.1kHz mono AAC-LC).
  * Raw AAC: `0xAE 0x01` + AAC access unit payload.
- **FLV Metadata Tag**:
  * Tag Type 18 (ScriptData), AMF0 string `@setDataFrame`, AMF0 string `onMetaData`, AMF0 ECMA array with dimensions, bitrates, frame rates, and codec IDs.
- **RTMP Protocol**:
  * Handshake: Standard C0+C1 (1537 bytes) -> S0+S1 (1537 bytes) -> C2 (1536 bytes) -> S2 (1536 bytes).
  * Chunk Streams: Protocol Control (CSID 2), Commands (CSID 3), Audio (CSID 4), Metadata (CSID 5), Video (CSID 6).
  * Chunk Sizing: Negotiated from default 128 bytes to 4096 bytes via `SetChunkSize` (reduces chunk header overhead by >95%).
  * Commands: AMF0 `connect`, `releaseStream`, `FCPublish`, `createStream`, `publish(streamKey, "live")`.
- **Backpressure & Queue Policy**:
  * Capacity: 90 packets / 4 MB memory ceiling.
  * Overflow Eviction: Evicts oldest non-keyframe delta video packets, preserving audio and keyframes.
  * Telemetry: Exposes `droppedFrames`, `queueDepth`, `bitrateBps`, `bytesSent`, `durationMs`.
- **Reconnection Architecture**:
  * Automatic retry with exponential backoff on unexpected socket drops (1s, 2s, 4s).
  * Capped at 3 attempts before signaling terminal error to prevent battery draining tight loops.

---

## 6. Service & UI Integration
- Hosted in foreground service `FairyStreamService` (`foregroundServiceType="mediaProjection|microphone"`).
- Dashboard Fragment: Real-time telemetry cards for Device Capability, Screen Capture, Video Encoder, Audio Subsystem, and RTMP Streaming Transport.
- Masked Stream Key: Plaintext stream keys are never exposed in UI or logcat.
