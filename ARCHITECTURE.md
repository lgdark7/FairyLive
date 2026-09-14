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

---

## 10. Camera / Face Cam Integration Architecture (Milestone 10)

### A. Subsystem Topology & Pipeline
```text
[ Physical Camera (Front/Rear) ]
   │
   │ (Pure Camera2 API / TEMPLATE_PREVIEW)
   ▼
[ SurfaceTexture (GL_TEXTURE_EXTERNAL_OES) ]
   │
   │ (Zero-Copy GPU Direct Mapping)
   ▼
[ GlesRenderer PIP Pass (uTexMatrix + uModel transform) ]
   │
   │ (Overlaid on Background Screen Capture)
   ▼
[ MediaCodec Video Encoder Input Surface ]
```

### B. Hardware Query & Optimal Preview Sizing (`CameraCapabilityDetector`)
- Queries `CameraManager` for device facing (`LENS_FACING_FRONT`, `LENS_FACING_BACK`), hardware support level, sensor orientation, and supported `SurfaceTexture` output resolutions.
- Pure-Kotlin `CameraSize(width, height)` wrapper decouples size selection logic from Android framework `Size` class for seamless unit testing and JVM isolation.
- Optimal resolution selection: Constrained to standard 640x480 preview dimension rather than full sensor resolutions (4K/12MP) to strictly conserve GPU texture fill rate and memory bandwidth on budget devices.

### C. Zero-Copy GLES PIP Compositing (`GlesRenderer`)
- Dedicated external OES texture (`cameraTextureId`) and `cameraSurfaceTexture` allocated on the GL thread.
- Native vertex/fragment shaders for external OES camera textures:
  * Dynamic `uTexMatrix`: Applies hardware sensor rotation directly in shader space.
  * Dynamic `uModel`: Maps normalized PIP position ($x, y$), dimension ($w, h$), aspect ratio preservation, and horizontal selfie mirroring (`scaleX = -1.0`) for front-facing camera.
- Composite rendering sequence:
  1. Base Pass: Background Screen Capture (`GL_TEXTURE_EXTERNAL_OES`)
  2. PIP Pass: Camera Facecam Layer (`GL_TEXTURE_EXTERNAL_OES`)
  3. Overlay Pass: Ordered Graphic Layers (Text, Goal, Stats, QR, Alerts, GIFs) with alpha blending (`GL_BLEND`)
- Zero CPU readback or bitmap conversions in hot path.

### D. Concurrency & Switching Lifecycle (`CameraCaptureEngine`)
- Dedicated background HandlerThread (`FairyCameraThread`) isolates camera operations from Android main/UI thread.
- Sequential teardown & rebuild protocol prevents camera driver deadlocks during front/rear lens switching:
  `STOP` -> `CLOSE_SESSION` -> `CLOSE_DEVICE` -> `RELEASE` -> `SELECT_LENS` -> `OPEN_DEVICE` -> `CONFIGURE_SESSION` -> `START_PREVIEW`.
- Thread-safe state synchronization via `CameraStateNotifier` tracking states: `IDLE`, `INITIALIZING`, `CONFIGURING`, `STREAMING`, `SWITCHING`, `STOPPING`, `ERROR`.

### E. IPC & Creator Controls
- `StreamControlReceiver`: Broadcast receiver handling `TOGGLE_FACECAM`, `SWITCH_FACECAM`, and `STOP_CAPTURE` enabling external automation and decoupled UI dispatch.
- Integrated dashboard controls (`fragment_dashboard.xml` and `DashboardFragment.kt`) for creator toggle, lens flip, and real-time state telemetry.

---

## 11. Multi-Scene Management & Seamless Switching Architecture (Milestone 11)

### A. Core Architecture & Zero-Disruption Invariant
Milestone 11 introduces multi-scene capabilities allowing creators to seamlessly switch between distinct visual configurations (Gameplay, Just Chatting, Be Right Back, and custom scenes) without interrupting the broadcast.
- **Strict Invariant**: Scene switching does not tear down or re-initialize the RTMP transport socket, MediaCodec AVC video encoder, AAC audio encoder, or audio pipeline clock. The streaming session remains unbroken (`LIVE`) throughout all transitions.
- **Rendering Thread Integration**: Scene swapping executes directly within the `OverlayCompositor` render loop on `FairyCompositorThread`.

### B. Scene Data Models (`com.fairylive.app.core.scene.model`)
- `BroadcastScene`: Immutable model encapsulating:
  * `id`: Unique string identifier.
  * `name`: Human-readable display label.
  * `orderIndex`: Sorting index in the UI switcher.
  * `overlayElements`: Ordered collection of active overlay layers (Text, Sub Goal, Stats, QR, Image, Watermark).
  * `facecamConfig`: Normalized PIP transform, placement coordinates, and camera enabled flag.
  * `backgroundMode`: `SCREEN_CAPTURE` (passthrough phone screen) or `SOLID_COLOR` (clear to ARGB color, e.g. for BRB screen).
  * `backgroundColorArgb`: 32-bit color integer for solid backgrounds.
- `SceneTransition`: Defines transition behavior (`CUT` with 0ms duration vs `CROSSFADE` with customizable duration, e.g. 300ms).
- `SceneStateNotifier`: Observable state distributor publishing active scene updates, transition events, and normalized progress `[0.0, 1.0]`.

### C. Lightweight Zero-Allocation Transitions
- **Crossfade Opacity Interpolation**: The compositor computes normalized progress $t \in [0.0, 1.0]$ based on elapsed transition time.
- During crossfade, outgoing scene layers render with opacity $\alpha_{out} = \alpha_{base} \cdot (1 - t)$, while incoming scene layers render with $\alpha_{in} = \alpha_{base} \cdot t$.
- No secondary OpenGL Framebuffer Objects (FBOs), intermediate textures, or CPU image buffers are allocated. This ensures zero additional GPU memory consumption and zero frame drops on constrained devices.

### D. Built-In Presets & Persistence
- Built-in factory presets in `BroadcastSceneManager`:
  1. **Scene 1 ("Gameplay")**: Screen capture base, top-right 26% Facecam PIP, stream watermark.
  2. **Scene 2 ("Just Chatting")**: Screen capture base, large 40% Facecam PIP, donation/sub goal widget, header text.
  3. **Scene 3 ("Be Right Back")**: Solid dark background (`#101420`), centered BRB banner, compact QR code (`fairy.live`), camera disabled.
- `SceneRepository`: Serializes scene configurations to JSON in Android `SharedPreferences` for seamless persistence across app launches.

---

## 12. Low-End Device Optimization Architecture (Milestone 12)

### A. Architectural Philosophy
Low-end Android smartphones (2GB-4GB RAM, MediaTek Helio, Unisoc, Qualcomm Snapdragon 4xx/6xx) suffer from acute thermal throttling, aggressive OS out-of-memory process kills, and GPU fill rate saturation. Milestone 12 introduces a dedicated reactive optimization engine (`com.fairylive.app.core.optimization`) designed to proactively degrade workload before the Android OS throttles or terminates the process.

### B. Proactive Thermal Mitigation (`ThermalMitigationManager`)
- Implements `PowerManager.OnThermalStatusChangedListener` on Android 10+ (API 29+) with backward-compatible broadcast monitoring on older versions.
- Four-tier reactive mitigation ladder matched directly to ABR rungs:
  1. `NOMINAL` (Thermal status 0/1): 30 FPS target, 1200 kbps bitrate ceiling.
  2. `MODERATE` (Thermal status 2): 24 FPS target, 800 kbps bitrate ceiling.
  3. `SEVERE` (Thermal status 3): 20 FPS target, 500 kbps bitrate ceiling, disabled non-critical UI effects.
  4. `CRITICAL` / `EMERGENCY` (Thermal status 4+): 15 FPS target, 300 kbps bitrate ceiling, absolute CPU/GPU conservation.
- Transparent recovery: As thermal sensors cool, the system automatically restores nominal frame rates and bitrates without disconnecting the live broadcast.

### C. System Memory Pressure & OOM Protection (`MemoryPressureGuard`)
- Listens to Android `ComponentCallbacks2.onTrimMemory(level)`.
- Multi-stage cache eviction:
  * `TRIM_MEMORY_RUNNING_LOW`: Purges the oldest 50% of cached overlay bitmap textures in `OverlayResourceManager`.
  * `TRIM_MEMORY_RUNNING_CRITICAL`: Completely evicts 100% of cached textures and triggers proactive system garbage collection hints.
- Safe Resource Recreation: Active layer bitmaps required on the next rendering pass are lazily recreated on-demand via factory suppliers without throwing GLES errors or crashing the compositor pipeline.

### D. Dynamic FPS Throttler (`DynamicFpsThrottler`)
- Integrated directly into the `OverlayCompositor` render pipeline.
- Continuously measures GLES frame draw duration over a rolling 10-frame window.
- If render duration consistently exceeds 32ms (threatening the 33.3ms budget of 30 FPS), the throttler steps down render presentation interval (30 -> 24 -> 20 -> 15 FPS) to eliminate frame queue stalls and GPU pipeline backpressure.
- Clamped Recovery: Recovers back toward nominal framerate only up to `thermalCeilingFps` when render latency drops below 18ms for 30 consecutive frames.

### E. Device Profile Detection (`DeviceProfileDetector`)
- Automatically classifies hardware at startup into `LOW_END`, `MID_RANGE`, or `FLAGSHIP` based on total RAM, core count, and `ActivityManager.isLowRamDevice`.
- Tailors initial default encoding profiles and buffer depths to prevent device overload.

### F. Controller Coordination & Precedence Hierarchy
To prevent controller hunting, oscillation, or feedback loops between network and hardware controllers:
1. **Thermal Mitigation** establishes the upper performance ceilings: `thermalBitrateCeiling` and `thermalFpsCeiling`.
2. **Network ABR (`AdaptiveBitrateController`)** dynamically adjusts bitrate inside the range `[300, thermalBitrateCeiling]` based on network packet health, socket latency, and buffer backpressure. ABR cannot upscale above the thermal ceiling during network recovery.
3. **Dynamic FPS Throttler** dynamically throttles frame presentation inside `[15, thermalFpsCeiling]` based on actual GLES render latency. It cannot upscale above the thermal FPS ceiling during low-latency frames.
- **Mathematical Invariant**:
  `Active Bitrate = min(ABR Evaluated Bitrate, Thermal Bitrate Ceiling)`
  `Active Frame Rate = min(Render Latency Target FPS, Thermal FPS Ceiling)`

---

## 13. Stress Testing & Reliability Verification Architecture (Milestone 13)

### A. Testing Philosophy & Empirical Verification Gate
Milestone 13 validates application survivability, memory boundedness, resource cleanup, and timeline integrity under sustained operational stress. All evaluations adhere to strict empirical measurement on Android 15 (Google Pixel 8 AVD, API 35) with zero simulated or fabricated metrics.

### B. Core Stress Test Harnesses (`tools/`)
1. **Long-Duration Soak Harness (`tools/run_m13_30min_soak_test.py`)**:
   - Manages a 30-minute continuous RTMP streaming session against a spec-compliant FLV/RTMP chunk reassembly server.
   - Integrates `RtmpChunkParser` for spec-compliant chunk multiplexing across all basic header formats (fmt 0, 1, 2, 3).
   - Samples 30 comprehensive minute-by-minute snapshots capturing:
     * Memory: PSS, Native Heap, Dalvik Heap, RSS.
     * System: CPU %, active threads, open sockets, active MediaCodec instances.
     * Media: Video FPS, Audio AU rate, total frames sent.
     * Synchronization: FLV video/audio PTS timestamps, A/V delta, cumulative drift.
2. **Start/Stop Lifecycle Stress Harness (`tools/run_m13_start_stop_stress.py`)**:
   - Drives 20 full cycles of `START` -> `STREAM` -> `STOP` -> `RELEASE` -> `GC`.
   - Validates zero monotonic growth across PSS, native allocations, thread counts, and socket descriptors.
3. **Reconnection & Network Stress Harness (`tools/run_m13_reconnect_10cycles.py`)**:
   - Executes 10 consecutive forced TCP socket drops to verify automated client re-handshake and stream resumption.
   - Executes server unreachability backoff exhaustion (1s -> 2s -> 4s -> 8s) verifying clean transition to `FAILED` with zero resource leaks.

### C. A/V Synchronization & Explicit Gap-Handling Architecture
- Audio timeline span: $\Delta T_{audio} = PTS_{audio\_last} - PTS_{audio\_first}$
- Video timeline span: $\Delta T_{video} = PTS_{video\_last} - PTS_{video\_first}$
- Cumulative Timeline Drift: $|\Delta T_{video} - \Delta T_{audio}|$
- **Explicit Gap Handling via Timestamp-Consistent Silence Frame Synthesis (`AudioCaptureEngine.kt`)**:
  1. **Continuous Micro-Spacing**: Derived directly from byte-count accumulation ($1024 \text{ samples} / 44100 \text{ Hz} \approx 23.2199 \text{ ms}$ per AAC frame), eliminating thread-scheduling timestamp slippage during normal execution.
  2. **Explicit Silence Bridging**:
     $$\text{driftUs} = \text{wallClockPtsUs} - \text{accumulatedPtsUs}$$
     When the Android audio HAL stalls or drops PCM chunks under system contention ($\text{driftUs} > 40,000\,\mu\text{s}$), the engine explicitly calculates missing frames ($\text{missingFrames} = (\text{driftUs} \times \text{sampleRate}) / 1,000,000$) and synthesizes pre-allocated zero-filled PCM silence chunks (bounded at 3.0s per event).
     Each chunk is queued into `AacMediaCodecEncoder` with canonical sample-continuous PTS.
  3. **Media Continuity Guarantee**:
     - **PTS Continuity**: Every AAC frame follows the preceding frame by exactly $1024 / 44100 \approx 23.22\text{ ms}$. Zero multi-second timestamp jumps.
     - **Sample Continuity**: Total AAC frames $\times 1024 / 44100$ equals the audio PTS span down to the millisecond.
     - **Audible Continuity**: Prevents player buffer underruns, click artifacts, or decoder lockups; replaced with clean, transient silence.
     - **A/V Synchronization**: Audio and video timelines remain strictly locked to the session origin (`MediaClock.sessionOriginNs`).







