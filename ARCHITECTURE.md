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
      ├── NetworkQualityMonitor (TCP write latency, throughput sliding window, ConnectivityManager)
      ├── AdaptiveBitrateController (1200->800->500->300 kbps, hysteresis, IDR keyframe requests)
      ├── StreamReconnectionManager (1s, 2s, 4s, 8s backoff, state safety, attempt capping)
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
- **Reconnection Architecture & Adaptive Bitrate**:
  * Automatic retry with exponential backoff on unexpected socket drops (1s, 2s, 4s, 8s).
  * Capped at 4 attempts (15s total retry window) before transitioning to `FAILED` / `ERROR`.
  * Dynamic ABR ladder: 1200 kbps, 800 kbps, 500 kbps, 300 kbps minimum floor.
  * Rapid congestion downscaling and conservative 3-window step-up with 3000ms anti-oscillation cooldown.

---

## 6. Service & UI Integration
- Hosted in foreground service `FairyStreamService` (`foregroundServiceType="mediaProjection|microphone"`).
- Dashboard Fragment: Real-time telemetry cards for Device Capability, Screen Capture, Video Encoder, Audio Subsystem, and RTMP Streaming Transport.
- Masked Stream Key: Plaintext stream keys are never exposed in UI or logcat.

---

## 7. Broadcast Overlay Compositor Architecture (Milestone 7)

### A. Dual-Domain Decoupling
FAIRY LIVE enforces strict separation between:
1. **Creator UI Domain**: The Android Activity, Fragments, and Views displayed on the streamer's device. Controls overlays and monitors live metrics.
2. **Broadcast Composition Domain**: The outgoing media pipeline. Overlays are composited purely on the GPU into the H.264 encoder input Surface without appearing over the creator's phone screen.

### B. GPU-based Composition Pipeline (No CPU Pixel Copies/Readbacks in the Live Path)
```text
[ MediaProjection ]
       │
       ▼
[ VirtualDisplay ] ──► [ SurfaceTexture (OES Texture) ]
                              │
                              ▼ (GLES2 Composition on Render Thread)
                       [ GlesRenderer ]
                              │ ──► Samples capture texture (GL_TEXTURE_EXTERNAL_OES)
                              │ ──► Blends cached overlay textures (GL_TEXTURE_2D)
                              │ ──► Alpha blending (SRC_ALPHA, ONE_MINUS_SRC_ALPHA)
                              ▼
                       [ EGL Window Surface ] ──► [ MediaCodec H.264 Input Surface ]
```
- **GPU-based composition with no CPU pixel copies/readbacks in the live composition path**: Display output remains entirely on GPU textures. No Bitmaps or CPU pixel copies are performed during the frame rendering loop.
- **Dedicated Render Thread**: `OverlayCompositor` runs a dedicated `HandlerThread` ("FairyCompositorThread") owning the EGL context, display, and window surface.
- **Hybrid Event/Poll Drive**: Event-driven on SurfaceTexture `onFrameAvailable` with an adaptive 16 ms poll fallback ensuring continuous frame delivery even on virtualized/emulated GPU drivers without duplicate frame encoding.

### C. Normalized Coordinate & Overlay Model
- **Normalized Coordinates**: All overlay transforms ($x, y, w, h$) are defined in normalized range $[0.0, 1.0]$.
  - $0.0, 0.0$ represents top-left; $1.0, 1.0$ represents bottom-right of the active broadcast resolution.
  - Overlays survive adaptive resolution changes (e.g. 480x1056 -> 720x1584) without distortion or clipping.
- **Model Hierarchy**:
  - `OverlayElement`: Declarative immutable data model (id, type, enabled, visible, opacity, transform, resourcePath). Sanitized against out-of-bounds coordinates and opacity.
  - `OverlayTransform`: Normalized spatial properties (x, y, width, height, scale, rotation, z-index).
  - `OverlayLayer`: Mutable runtime rendering state holding GPU texture handles, live visibility, and dirty flags.
  - `OverlayScene`: Thread-safe container maintaining layers sorted by ascending z-index.
  - `OverlayResourceManager`: Background-thread PNG decoder with bounded LRU memory caching and texture management.
  - `OverlayCompositor`: Top-level coordinator managing render thread lifecycle, EGL state, and metrics publishing.

---

## 8. Advanced Multi-Type Overlay System (Milestone 8)

### A. Extensible Overlay Architecture
FAIRY LIVE M8 establishes a modular, decoupled overlay architecture that supports multiple broadcast asset categories without modifying the underlying GLES compositor loop or RTMP streaming transport:
- **`IMAGE` / `PNG`**: Static branding, watermarks, sponsor logos.
- **`TEXT`**: Streamer titles, announcements, labels with customizable typography and background padding.
- **`GOAL`**: Follower, subscriber, and donation progress meters with dynamic percentages and colored progress tracks.
- **`LOWER_THIRD`**: Broadcast news/talk-show style lower-third banners with title, subtitle, and accent stripes.
- **`ALERT`**: Dynamic event alerts (new follower, subscriber) with entry graphics, text, customizable duration, and auto-dismissal.
- **`QR`**: On-screen QR codes for links, tips, or social media.
- **`WIDGET`**: Real-time diagnostic readouts (FPS, bitrate, resolution) and custom counters.
- **`GIF`**: Bounded-memory animated graphics.

### B. Modular Rasterizer & Animation Architecture
- **Zero-Allocation Live Rendering**:
  - Elements are rasterized once upon configuration change by dedicated rasterizers: `TextOverlayRasterizer`, `GoalOverlayRasterizer`, `LowerThirdOverlayRasterizer`, `QrOverlayRasterizer`, `AlertOverlayRasterizer`, `WidgetOverlayRasterizer`.
  - Rasterized Bitmaps are uploaded to OpenGL as `GL_TEXTURE_2D` and cached in `OverlayResourceManager`.
  - During the live composition loop, only GPU textures and uniform matrices are bound. Zero CPU Bitmaps are created or copied per frame.
- **Bounded-Memory GIF Playback (`GifStreamPlayer`)**:
  - Employs native Android framework `Movie` / Skia engine without any third-party dependencies (0 KB APK impact, zero license overhead).
  - Allocates exactly **one reusable Bitmap buffer** per active GIF, updating only when elapsed broadcast time advances the frame.
- **Independent Alert Sound Path (`AlertSoundController`)**:
  - Sound effects for alerts are managed via `SoundPool` on an independent asynchronous audio channel.
  - Completely decoupled from the GLES render thread (never blocks composition) and the microphone `AudioRecord` pipeline.

### C. Low-End Device Limits (`CompositorLimits`)
- `MAX_ACTIVE_OVERLAYS = 10`: Strict ceiling on simultaneous scene layers.
- `MAX_CACHED_TEXTURES = 32`: Maximum texture LRU cache capacity.
- `MAX_TEXTURE_DIMENSION = 1024`: Clamped maximum texture resolution to protect 2GB RAM devices.
- `MAX_TEXTURE_MEMORY_BYTES = 24 MiB`: Global bitmap-cache admission budget with LRU eviction accounting.
- GIF playback uses exactly one reusable Bitmap per active GIF; there is no decoded-frame ring buffer.
- `MAX_ACTIVE_GIFS = 2`: Concurrent animated-overlay ceiling.
- `MAX_CONCURRENT_ALERTS = 3`: Queue ceiling for broadcast alerts.

---

## 9. Adaptive Bitrate & Network Resilience Architecture (Milestone 9)

### A. Network Quality Telemetry (`NetworkQualityMonitor`)
- **Zero-Allocation Hot-Path Primitives**:
  - Employs a pre-allocated circular array (`LongArray(16)`) to track socket write latencies.
  - Throughput is calculated via a sliding byte accumulator over 1000 ms windows with zero object allocation during packet transmission.
- **Health Classification Hierarchy**:
  - `EXCELLENT`: Latency 1–49 ms, 0 slow writes, queue fill $\le$ 5%, throughput $\ge$ 1,000 kbps.
  - `HEALTHY`: Normal operating conditions meeting baseline transmission pace.
  - `DEGRADED`: Latency $\ge$ 150 ms, slow writes $\ge$ 1, queue fill $\ge$ 25%, or throughput 500–800 kbps.
  - `POOR`: Latency $\ge$ 350 ms, slow writes $\ge$ 3, queue fill $\ge$ 50%, or throughput 250–500 kbps.
  - `CRITICAL`: Failures $> 0$, OS network unavailable, latency $\ge$ 500 ms, queue fill $\ge$ 75%, or throughput 1–250 kbps.
- **ConnectivityManager Integration**: Tracks OS-level interfaces (`WIFI`, `CELLULAR`, `ETHERNET`, `UNAVAILABLE`). Physical Wi-Fi $\leftrightarrow$ Cellular seamless handover is explicitly documented as not validated on this AVD.

### B. Adaptive Bitrate Controller (`AdaptiveBitrateController`)
- **Discrete Bitrate Ladder**: 1200 kbps (Tier 0) $
ightarrow$ 800 kbps (Tier 1) $
ightarrow$ 500 kbps (Tier 2) $
ightarrow$ 300 kbps (Tier 3 floor).
- **Asymmetric Downscaling State-Machine**:
  - `CRITICAL`: Immediate emergency clamp to 300 kbps floor (`reason=CRITICAL_CONGESTION`).
  - `POOR`: 2-tier rapid downscale (`targetTier = (currentTierIndex + 2).coerceAtMost(3)`). From 1200 kbps, intentionally transitions to 500 kbps (`reason=POOR_NETWORK`) to rapidly relieve acute network collapse and protect keyframes from buffer explosion, skipping intermediate 800 kbps.
  - `DEGRADED`: 1-tier downscale (`targetTier = (currentTierIndex + 1).coerceAtMost(3)`), transitioning 1200 $
ightarrow$ 800 kbps (`reason=DEGRADED_LATENCY` or `QUEUE_BACKPRESSURE`).
  - **Keyframe Synchronization**: Requests an IDR sync keyframe from the encoder (`PARAMETER_KEY_REQUEST_SYNC_FRAME`) upon significant downward transitions.
- **Conservative Recovery with Hysteresis**:
  - Requires 3 consecutive sustained healthy evaluation windows (3000 ms) AND a 3000 ms anti-oscillation cooldown before stepping up 1 tier at a time (300 $
ightarrow$ 500 $
ightarrow$ 800 $
ightarrow$ 1200 kbps).

### C. Stream Reconnection & Lifecycle Safety (`StreamReconnectionManager`)
- **Bounded Exponential Backoff**: Retries at 1s, 2s, 4s, 8s intervals capped at 4 maximum attempts.
- **Terminal State Safety**: Transitions cleanly to `RtmpConnectionState.FAILED`, halting background threads and preventing reconnect storms or false `● LIVE` reporting.
- **Session Continuity**: Retains encoder instances and sequence headers; viewer-side seamless player continuity is explicitly not validated in M9.

### D. Codec Instance Lifecycle & Memory Guarantees
- **Active Codec Invariant**: Exactly **2 active MediaCodec instances** exist in the process (1 AVC video encoder `HardwareVideoEncoder`, 1 AAC audio encoder `AacMediaCodecEncoder`). Reconnection preserves these instances without recreation or leaks.
- **System Profile Enumeration Clarification**: The 55 count reported in system dumps represents registered OS MIME profiles (`dumpsys media.player`), not application instances. Application instances are verified via `dumpsys media.metrics`.



