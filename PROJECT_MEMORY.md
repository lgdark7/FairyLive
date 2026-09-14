# FAIRY LIVE — PROJECT MEMORY

## 1. Project Objective
Rebuild **FAIRY LIVE** from scratch as a high-performance, professional native Android live-streaming application, with an absolute design focus on **low-end Android devices** (low RAM, constrained CPU/GPU, hardware video encoding, unstable networks).

---

## 2. Complete Requirements Summary
- **Native Android Only**: 100% Kotlin, Android Studio, Gradle, native Android APIs. Zero cross-platform frameworks (No Flutter, React Native, etc.).
- **Decoupled Stream Pipeline**: Outgoing video composited independently from streamer device control UI.
- **CPU-Copy-Free Surface Encoding**: Direct Surface-to-Surface pipeline into MediaCodec H.264/AVC encoder.
- **Low-Latency Audio**: AudioRecord PCM capture, mixer/sync, AAC-LC encoding, monotonic drift-free timestamps.
- **Pure Native Transport**: Lightweight, zero-dependency RTMP client and FLV multiplexer.
- **Bounded Backpressure**: 4 MB memory ceiling and non-keyframe drop policy.
- **Foreground Lifecycle**: Android ForegroundService with strict permission and resource cleanup adherence.

---

## 3. Technology Choices & Android Configuration
- **Language**: Kotlin 1.9.22
- **Build System**: Gradle 8.10 with Android Gradle Plugin 8.2.2
- **Target SDK**: Android 14/15 (API 34/35)
- **Minimum SDK**: Android 7.0 (API 24)
- **JVM Target**: Java 17 (`jdk17` Temurin distribution pinned in `gradle.properties`)
- **UI Architecture**: ViewBinding, Material Components, Lifecycle KTX, Fragments with BottomNavigationView
- **Services Architecture**: Android ForegroundService with NotificationChannel and MediaProjection/Microphone foreground types
- **Capture Engine**: MediaProjection + VirtualDisplay + CPU-copy-free Surface pipeline
- **Video Encoder**: MediaCodec H.264/AVC with `COLOR_FormatSurface` input and Annex-B NAL output
- **Audio Engine**: AudioRecord (Microphone + Android 10+ AudioPlaybackCapture) + MediaCodec AAC-LC encoder (`audio/mp4a-latm`) with canonical monotonic media clock (`MediaClock`)
- **Streaming Transport**: Pure Kotlin RTMP Client (C0-C2 handshake, 4096-byte negotiated chunk size, AMF0 commands) + FLV Multiplexer (`H264FlvPacketizer`, `AacFlvPacketizer`, `FlvMetadataBuilder`) + Bounded Queue (`BoundedStreamQueue`)

---

## 4. Current Milestone Status
- **Completed Milestones**: 
  - **M0: Environment Audit & Project Foundation** (Approved by ChatGPT on 2026-09-13)
  - **M1: Application Foundation & Shell** (Approved by ChatGPT on 2026-09-13)
  - **M2: Screen Capture Engine** (Approved by ChatGPT on 2026-09-13)
  - **M3: Hardware Video Encoding** (Approved by ChatGPT on 2026-09-13)
  - **M4: Audio Pipeline** (Revalidated on 2026-09-13)
  - **M5: Streaming Pipeline** (Revalidated on 2026-09-13)
    - Scope: Pure native Kotlin RTMP client, FLV multiplexer (H.264 Annex-B -> AVCDecoderConfigurationRecord / NALU + AAC AudioSpecificConfig / raw AAC), `@setDataFrame onMetaData` AMF0 script tag generation, bounded 4MB backpressure queue with non-keyframe delta frame eviction, live network telemetry (bitrate, bytes, latency, drops, queue depth), exponential backoff reconnection, integration into `FairyStreamService` and `DashboardFragment`, comprehensive unit test suite (44 tests) + on-device instrumented test suite (3 tests).
    - Status: **M5 REVALIDATION COMPLETE — WAITING FOR CHATGPT REVIEW AND AUTHORIZATION FOR M6**.
  - **M6: Creator Experience, Error UX & Secure Config** (Revalidated & Reviewed — **COMPLETE**)
      - **Critical bug found & fixed**: Android 15 `createScreenCaptureIntent()` routes through the single-app selector, which fails to bind `RECORD_CONTENT_TASK` on API 35 emulators (`Content Recording: Stopped MediaProjection due to failing to set ContentRecordingSession - id=-1`), causing `createVirtualDisplay()` to return null → app previously showed "● LIVE" with **zero video frames** (silent failure).
      - **Fix**: Use `MediaProjectionConfig.createConfigForDefaultDisplay()` (full-display capture) on API 34+, which triggers the reliable "Entire screen" dialog. Registered `MediaProjection.Callback` BEFORE `createVirtualDisplay`; added `createVirtualDisplay` null/SecurityException/IllegalStateException handling; abort broadcast + post creator error on capture failure instead of publishing a video-less stream.
      - Also: `StreamConfigurationValidator` (endpoint/key validation + stream-key masking `li•••••••••••••56`), `CreatorErrorNotifier` (typed errors with title/message/resolution), error card UI in Dashboard, Settings navigation, config validation before MediaProjection consent & at save time.
      - Test suite grew to **71 unit + 3 device tests** (added `CaptureFailureRegressionTest` 10 + `testProjectionDeniedCreatorErrorCard` for failure-path and error UX).
      - **M6 revalidation (2026-09-13 13:45 review response)**: Tests A–F passed on-device (full-screen dialog, fresh state, repeated auth cancel/accept, clean stop→restart streams again). Success stream verified: **14,679,930 bytes, 3,129 video + 11,326 audio AUs** over 263s (audio 43.07 AU/s = theory; video ~11.9 fps — full-display swiftshader throttle, M5 single-app was 23 fps).
      - **M6 final review (2026-09-13 14:35)**: per-stage FPS trace added (`STAGE-AUDIT` log in StreamPipeline; 492.56s run: encoder output = muxer tags = RTMP-sent at every sample, drops=0 — zero pipeline loss; fps ceiling upstream VirtualDisplay, not transport). Projection-denied path posts typed CreatorError card (verified on-device: error card + OFFLINE/CAPTURE IDLE + 0 bytes to server). Credential masking verified in UI and logcat.
  - **M7: Broadcast Overlay Compositor** (Revalidated & Approved on 2026-09-13)
  - **M8: Advanced Overlay System** (Approved by ChatGPT on 2026-09-14)
      - Verified ISO/IEC 18004 QR encoder, 1-bitmap Movie GIF player with texture change guard, 24MB texture memory budget, 5-overlay connected test, and controlled A/B test.
  - **M9: Adaptive Bitrate & Network Resilience Engine** (Revalidated & Verified — **WAITING FOR CHATGPT REVIEW**)
      - **Network Quality Monitor (`NetworkQualityMonitor.kt`)**: Zero-allocation hot-path sliding window for TCP write latency (`LongArray(16)`), throughput (kbps), socket backpressure, and Android `ConnectivityManager.NetworkCallback` capabilities (WIFI, CELLULAR, UNAVAILABLE). State classification: `EXCELLENT`, `HEALTHY`, `DEGRADED`, `POOR`, `CRITICAL`. Transport transition detection implemented; physical Wi-Fi/cellular streaming migration explicitly noted as not validated on this AVD.
      - **Adaptive Bitrate Controller (`AdaptiveBitrateController.kt`)**: 4-tier ladder (1200, 800, 500, 300 kbps). Rapid downscaling under congestion (`CRITICAL` clamps to 300 kbps floor, `POOR` drops 2 tiers, `DEGRADED` drops 1 tier); requests IDR keyframe from encoder (`MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME`). Conservative step-up recovery requiring 3 consecutive healthy windows + 3000ms anti-oscillation cooldown.
      - **Reconnection Engine (`StreamReconnectionManager.kt`)**: Bounded exponential backoff (1s, 2s, 4s, 8s capped at 4 attempts). Prevents infinite retries by transitioning to `FAILED` / `ERROR`.
      - **State Correctness**: Strict prevention of false `LIVE` state; `DISCONNECTED`, `RECONNECTING`, `FAILED` explicitly surfaced on UI.
      - **Fault-Injection & Real RTMP Testing**: Verified via `fault_injection_rtmp_server.py`.
        * Forced disconnect & reconnect: RTMP publishing successfully recovered after forced socket disconnect (actual outage 1.03s, reconnect 1.00s backoff + 27ms socket, viewer-side seamless continuity explicitly not validated in M9).
        * Controlled Bandwidth Throttling: Step-by-step ladder transition ledger verified (1200 -> 500 -> 300 kbps under throughput constriction; 300 -> 500 -> 800 -> 1200 kbps under recovery).
        * Decoupled Drop Metrics: Separated `captureDrops=0`, `encoderDrops=0`, `muxDrops=0`, `rtmpDrops=0`, `serverDrops=0`, `queueEvictions`, `deltaFramesEvicted`, `keyframesEvicted=0` (protected).
        * 10-Cycle Resource Audit: Verified no sustained resource growth observed across 10 reconnect cycles (Native Heap stable at 14.0 - 14.9 MB, Threads 34-35, Sockets 4, Codecs 55).
        * Controlled A/B Overhead Benchmark: Condition A vs Condition B on identical device: +0.4% CPU overhead, ~0 MB net memory delta.
      - **Test Suite**: **108 Unit Tests** + **5 On-Device Connected Tests** (100% Passing).
- **Pending Milestones**: M10 through M15 (strictly gated on ChatGPT M9 approval).

---

## 5. Bugs & Fixes Encountered
- **M0 Bug**: Host default JDK 25 was incompatible with Gradle 8.10.
  - **Fix**: Pinned `org.gradle.java.home=C:/Users/benny/Desktop/FAIRY LIVE/jdk17` in `gradle.properties`.
- **M1 Bug**: Calling `startForeground` with `FOREGROUND_SERVICE_TYPE_MICROPHONE` on Android 14+ threw `ForegroundServiceStartNotAllowedException` when permissions were ungranted.
  - **Fix**: Added proactive permission validation in `DashboardFragment`, wrapped `startForeground` in try-catch with error fallback.
- **M2 Bug**: Android 14/15 VirtualDisplay rejected odd physical dimensions (e.g. 1080x2376) causing BufferQueue abandonment.
  - **Fix**: Implemented strict even-dimension clamping: `(dim / 2) * 2`.
- **M3 Bug**: Codec capability mismatch on Android 15 emulator: `c2.android.avc.encoder` was falsely reported as hardware.
  - **Fix**: Audited device codecs with instrumented test, classified software codecs truthfully, implemented adaptive 9:20 aspect ratio scaling ($480 \times 1056$).
- **M4 Bug**: `c2.android.aac.encoder` software classification and differential clock drift between video and audio engines.
  - **Fix**: Created canonical `MediaClock` anchoring both pipelines to monotonic nanosecond origin $t_0$; reclassified audio encoder explicitly as `EncoderType.SOFTWARE`.
- **M5 Bug 1**: `BufferedOutputStream` in `RtmpClient` retained small AMF0 commands in memory, causing connection timeout.
  - **Fix**: Added immediate flushing after every RTMP control command and message chunk batch in `RtmpChunkWriter`.
- **M5 Bug 2**: Android 15 `MaterialSwitch` threw NPE in `SwitchCompat.makeLayout` during layout pass.
  - **Fix**: Replaced with clean, reliable `MaterialCheckBox` implementing `CompoundButton`.
- **M5 Bug 3 (AAC Frame Accounting Inconsistency)**:
  - **Issue**: Initial test server reported 4,074 audio frames for ~42 seconds of streaming, which contradicted the theoretical $42 \times 44100 / 1024 \approx 1,808$ access units.
  - **Root Cause**: The mock server script (`test_rtmp_server.py`) only read the first chunk (`min(msg_len, in_chunk_size)`) of large video messages (keyframes were up to 85 KB), leaving tens of thousands of unconsumed video payload bytes in the TCP stream. On subsequent iterations, the server read random bytes from the unconsumed video payload as chunk headers, misinterpreting any byte with upper bits `00` as a Format 0 header and any message type `8` as an audio frame.
  - **Fix**: Replaced with a fully spec-compliant RTMP chunk reassembler (`RtmpChunkParser`) that tracks chunks across Format 0, 1, 2, and 3 continuation chunks per `csid`.
  - **Revalidation Result**: Real server verification recorded **860 actual AAC access units** over **19.946 seconds** (43.11 fps), exactly matching 19.946 x 44100 / 1024 = 859.0 expected units. Average audio delta was **23.22 ms** (1024 / 44100 = 23.2199 ms). Video recorded **459 frames** over **19.901 seconds** (**23.06 FPS**). A/V timeline difference was **45 ms** (drift-free).
- **M6 Bug 1 (CRITICAL — Silent Zero-Video-Frame Broadcast)**:
  - **Issue**: Android 15 `createScreenCaptureIntent()` routes through the single-app selector, which fails to bind `RECORD_CONTENT_TASK` on API 35 emulators (`Content Recording: Stopped MediaProjection due to failing to set ContentRecordingSession - id=-1`), causing `createVirtualDisplay()` to return null. The app falsely reported "● LIVE" while publishing **zero video frames**.
  - **Root cause**: `ScreenCaptureEngine.start()` did not null-check the `createVirtualDisplay()` result; the service proceeded to publish RTMP with an encoder that had no frames.
  - **Fix (capture)**: Switched to `MediaProjectionConfig.createConfigForDefaultDisplay()` on API 34+ (full-display capture, bypasses single-app selector); wrapped `createVirtualDisplay()` in try/catch for `SecurityException`/`IllegalStateException`; added explicit null result handling; `ScreenCaptureEngine` now returns `false` on failure.
  - **Fix (service)**: After `captureEngine.start()`, check `captureEngine.isCapturing` before starting RTMP; post `CreatorErrorNotifier` error on failure; abort and stop service.
  - **Fix (lifecycle)**: Registered `MediaProjection.Callback` BEFORE `createVirtualDisplay()` to prevent the projection from being stopped by the system before the callback is registered.
  - **Fix (UI)**: Dashboard now shows an error card ("Capture Failed to Start") instead of false "● LIVE" state.
- **M6 Bug 2 (Streamer UX — No Error Feedback)**:
  - **Issue**: Any capture failure (projection denied, MediaProjection null, encoder failure) left the UI in "CAPTURING" state with no visible error message.
  - **Fix**: Created `CreatorErrorNotifier` (title/message/resolution observer pattern) and wired it into the Dashboard as a full-width error card with Configure Destination action; applied to every failure path in `handleStartCapture()`.
- **M7 Bug 1 (Off-Thread GL Call Starvation)**:
  - **Issue**: SurfaceTexture `setOnFrameAvailableListener` invoked callback on an arbitrary thread without an active EGL context, causing GL draw/swap calls to silently no-op and starving the encoder (`muxedV=0`).
  - **Fix**: Marshalled callback onto dedicated `FairyCompositorThread` where the EGL context is current.
- **M7 Bug 2 (Virtualized Driver Callback Delivery)**:
  - **Issue**: Emulator swiftshader intermittently omitted `onFrameAvailable` signals, causing stalls.
  - **Fix**: Added an adaptive 16 ms periodic poll fallback with `consumeNewFrame()` ensuring steady 46+ FPS frame delivery to MediaCodec without CPU busy-waiting.

---

## 6. Architecture & Code Structure Summary
- `com.fairylive.app.core.compositor`:
  - `model/`: `OverlayElement` (immutable sanitized data model), `OverlayTransform` (normalized spatial geometry $[0.0, 1.0]$), `OverlayType`
  - `render/`: `GlesRenderer` (EGL14 context/window surface, OES capture shader, quad overlay shader with alpha blend)
  - `resource/`: `OverlayResourceManager` (background BitmapFactory decode, bounded LRU GPU texture cache)
  - `OverlayLayer.kt`: Runtime layer state (texture handle, dirty flag, live visibility)
  - `OverlayScene.kt`: Z-sorted layer container with deduplication by ID
  - `OverlayCompositor.kt`: Dedicated HandlerThread, event-driven render loop with poll fallback, metrics publishing
  - `CompositorStateNotifier.kt`: Observable compositor state & telemetry bus
- `com.fairylive.app.core.stream`:
  - `model/`: `RtmpConnectionState`, `StreamTelemetry`, `FlvPacket`
  - `flv/`: `H264FlvPacketizer`, `AacFlvPacketizer`, `FlvMetadataBuilder`
  - `rtmp/`: `RtmpClient`, `RtmpHandshake`, `RtmpChunkWriter`, `RtmpChunkReader`, `RtmpEndpoint`
  - `rtmp/amf/`: `Amf0Codec`
  - `queue/`: `BoundedStreamQueue`
  - `StreamPipeline.kt`: Orchestrates muxing, queueing, transmission, reconnection, and telemetry
  - `StreamTelemetryNotifier.kt`: Thread-safe observer for UI and service

---

## 7. Performance & Resource Benchmarks
- **Cold Launch Time**: **1,615 ms**
- **Debug APK Size**: **6.15 MB** (41.0% of 15 MB budget)
- **Active Full Broadcast PSS**:
  - Direct Passthrough (M6): **67.7 MB**
  - GLES Composited (M7): **74.7 MB** (+7.0 MB for EGL, shaders, textures, LRU cache)
- **Pipeline Video Framerate**:
  - Baseline (M6 full-display): **10.04 FPS**
  - Composited (M7 GLES loop): **46.36 FPS**
- **Pipeline Audio Framerate**: **43.08 – 43.09 AU/s** (Theoretical exact: 43.066 AU/s)
- **Muxer & Queue Packet Drops**: **0** (Zero dropped frames in both baseline and composited runs)
- **Queue Memory Footprint**: Strictly capped at 4 MB / 90 packets.
- **Test Suite**: **81 Unit Tests** + **5 On-Device Connected Tests** (100% Passing).
