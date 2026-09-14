# FAIRY LIVE — PRODUCT REQUIREMENTS SPECIFICATION

## 1. MUST HAVE (Core & Non-Negotiable)
1. **Pure Native Android Architecture**:
   - 100% Kotlin codebase.
   - Built with Android Studio, Gradle, and native Android SDK APIs.
   - Zero Flutter, React Native, or cross-platform framework code.
2. **Low-End Performance First**:
   - Smooth streaming on 2GB-3GB RAM devices and quad/octa-core budget SoCs.
   - CPU-copy-free surface rendering pipeline.
   - Strict memory management, avoiding allocations in frame rendering and audio processing loops.
3. **Screen Capture Subsystem**:
   - MediaProjection API with proper Android 14/15 foreground service permissions and user consent handling.
   - Resilient VirtualDisplay handling with orientation changes and aspect-ratio preservation.
4. **Video Encoding Subsystem**:
   - MediaCodec H.264/AVC encoding with direct Surface input (`COLOR_FormatSurface`).
   - Hardware acceleration preferred with transparent software fallback (`c2.android.avc.encoder`).
   - Configurable resolution (adaptive 9:20 and 16:9), framerate (24, 30, 60 fps), and bitrate.
5. **Robust Audio Pipeline**:
   - Low-latency AudioRecord microphone capture (16-bit PCM, 44.1kHz/48kHz).
   - Audio mute/unmute with continuous silence generation for timestamp preservation.
   - AAC-LC audio encoding (`audio/mp4a-latm`, 96-128 kbps).
   - Monotonic, drift-free timestamp synchronization with video.
6. **Decoupled Overlay Composition**:
   - Stream compositor rendering overlays directly into the encoder surface without polluting streamer UI.
   - Static PNG/WebP overlays and dynamic text overlays with position, scale, and opacity controls.
7. **Network Resilience & RTMP Client**:
   - Reliable streaming transport (RTMP/FLV).
   - Adaptive Bitrate (ABR ladder: 1200->800->500->300 kbps) with dynamic MediaCodec parameter tuning and sync keyframe insertion.
   - Bounded exponential backoff reconnect logic (1s, 2s, 4s, 8s, capped at 4 attempts).
   - Adaptive frame-dropping / backpressure handling to prevent memory exhaustion under poor network conditions.
8. **Foreground Service & Android Lifecycle**:
   - Compliant Android Foreground Service ensuring uninterrupted background streaming while playing games.
   - Clean resource release on stream termination.

---

## 2. SHOULD HAVE (Enhanced Usability & Polish)
1. **Internal Device Audio Capture**:
   - Android 10+ (API 29+) `AudioPlaybackCaptureConfiguration` for game/media sound capture.
2. **Multiple Simultaneous Overlays**:
   - Multi-layer composition with z-ordering.
3. **Scene Management**:
   - Switchable scenes (e.g. Starting, Gameplay, Chat, Ending) without interrupting active stream connection.
4. **Camera / Face Cam**:
   - Pure Camera2 integration rendering face-cam into GLES compositor pipeline via zero-copy `GL_TEXTURE_EXTERNAL_OES` (Completed in M10).
5. **Dynamic Telemetry & Adaptive Quality**:
   - Monitoring of encoder FPS, dropped frames, network throughput, and device battery/thermal state.
6. **Custom Creator Presets**:
   - Saving and loading scene layouts and stream server profiles.

---

## 3. FUTURE (Extended Features)
1. Animated overlay formats (GIF, WebP animations, Lottie/vector animations).
2. Creator soundboard and interactive audio triggers.
3. Stream chat overlay rendering.

---

## 4. Milestone 9 Verification Standards & Constraints
1. **ABR Downscaling Dynamics**:
   - Discrete ladder: 1200 -> 800 -> 500 -> 300 kbps.
   - Under acute `POOR` network conditions (throughput < 500 kbps or write latency > 350 ms), the controller intentionally executes a 2-tier rapid downscale (1200 -> 500 kbps) to prevent buffer explosion and protect keyframes, skipping intermediate 800 kbps.
   - Under mild `DEGRADED` conditions (500–800 kbps), it executes a 1-tier step-down (1200 -> 800 kbps).
   - Keyframes and sequence headers are strictly protected (`keyframesEvicted = 0`).
2. **Decoupled Metric Accounting**:
   - Complete architectural decoupling between pipeline-stage drops (`captureDrops`, `encoderDrops`, `muxDrops`, `rtmpDrops`, `serverDrops`) and buffer queue evictions (`queueEvictions`, `deltaFramesEvicted`, `keyframesEvicted`, `unbufferedDrops`).
3. **Controlled A/B Evaluation**:
   - Evaluation protocol requires 3 runs per condition with identical parameters, reporting run-by-run measurements along with means and ranges for CPU, PSS, Native Heap, Dalvik Heap, Threads, Sockets, and Queue Memory. No memory-reduction claims are permitted.
4. **Codec & Resource Audit Ground Truth**:
   - Active encoder instances are strictly bounded to 2 (1 video, 1 audio), confirmed via `dumpsys media.metrics`. System-level codec profile enumeration (`dumpsys media.player`) is disambiguated.
   - Post-GC memory stability must be verified to prevent conflating transient allocation with memory leaks.
5. **Operational Limitations Preserved**:
   - Viewer-side seamless player continuity is explicitly not validated in M9.
   - Physical Wi-Fi <-> Cellular interface migration is explicitly not validated on AVD.
   - No unsupported claims of hardware acceleration or zero memory leaks beyond empirical evidence.

---

## 5. Milestone 10 Verification Standards & Constraints
1. **Zero-Copy Pipeline Integrity**:
   - Camera frames must flow directly from `CameraCaptureSession` to an Android `Surface` backed by a dedicated GLES `SurfaceTexture` (`GL_TEXTURE_EXTERNAL_OES`). No CPU memory copies, pixel readbacks, or `Bitmap` allocations are permitted in the hot path.
2. **GPU Bandwidth Conservation**:
   - Camera preview dimensions must be constrained to bandwidth-optimized dimensions (640x480) for PIP rendering; full sensor resolution capture (4K/12MP) is prohibited to prevent GPU fill rate and memory exhaustion on low-end hardware.
3. **Safe Lifecycle & Non-Monotonic Growth**:
   - Camera teardown and rebuild must execute sequentially on a dedicated background thread without deadlocks or unclosed sessions.
   - 10-cycle camera toggle stability audit must verify non-monotonic memory growth post-GC, with active MediaCodec instances held strictly at 2.
4. **Decoupled IPC & State Safety**:
   - Camera toggling and lens switching must never mutate RTMP stream state or cause false `● LIVE` reporting.

---

## 6. Milestone 11 Verification Standards & Constraints
1. **Zero-Disruption Runtime Switching**:
   - Scene transitions (e.g. between Gameplay, Just Chatting, and Be Right Back) must NEVER tear down, restart, or recreate the RTMP client, MediaCodec video/audio encoders, or audio capture pipeline.
   - The stream status must remain unbroken (`LIVE`) with zero socket reconnects or reset signals.
2. **Lightweight Transitions**:
   - Instant cuts (0ms) and crossfade transitions (e.g. 300ms) must execute on the existing EGL compositor render thread via linear opacity modulation without creating extra FBOs, offscreen staging textures, or CPU image buffers.
3. **Resource Boundedness & Codec Invariant**:
   - Active MediaCodec instances must be strictly held at 2 during scene switches.
   - 10-cycle scene toggle stability audit must demonstrate no meaningful monotonic resource growth post-GC (net PSS delta +4 KB across 10 cycles, 0 byte native heap delta).
4. **Live Same-Session RTMP Continuity Protocol**:
   - Scene switching continuity must be verified with active MediaCodec video/audio streaming over a persistent TCP socket.
   - Verification requires: 1 single persistent TCP socket, 0 RTMP disconnects, 0 RTMP reconnect attempts, 0 stream restarts, 0 false LIVE transitions, 0 pipeline frame drops, and strictly monotonic video and audio timestamps across all scene switches.

---

## 7. Milestone 12 Verification Standards & Constraints
1. **Low-End Optimization Protocol**:
   - Must strictly follow `BASELINE -> MEASURE -> IDENTIFY BOTTLENECK -> OPTIMIZE -> MEASURE -> COMPARE -> REGRESSION TEST`. Blind optimizations without baseline metrics are prohibited.
2. **Honesty Regarding Target Hardware**:
   - Testing conducted on Android 15 Pixel 8 AVD must never be presented as low-end physical hardware validation. The physical hardware absence must be explicitly acknowledged in all reports and documentation.
3. **Thermal Mitigation Invariant**:
   - Thermal status callbacks must dynamically govern render frame rates (30 -> 24 -> 20 -> 15 FPS) and encoder bitrates (1200 -> 800 -> 500 -> 300 kbps) across `NOMINAL`, `MODERATE`, `SEVERE`, and `CRITICAL` states, with smooth automatic recovery upon cooling.
4. **System Memory & OOM Protection**:
   - The application must handle `ComponentCallbacks2.onTrimMemory` signals: purging 50% of cached overlay bitmap textures on `TRIM_MEMORY_RUNNING_LOW` and 100% on `TRIM_MEMORY_RUNNING_CRITICAL`. Active textures required for ongoing composition must safely recreate on demand with zero GLES errors.
5. **Dynamic Frame Throttler Integrity**:
   - Compositor frame interval must adapt dynamically when measured frame draw latencies exceed 32ms, preventing compositor thread stalling and buffer queue backpressure. Frame recovery must be strictly bounded by the thermal FPS ceiling.
6. **Controller Coordination & Precedence**:
   - Explicit precedence hierarchy: Thermal mitigation sets maximum bitrate and FPS ceilings; Network ABR operates dynamically within the thermal bitrate ceiling; Dynamic FPS Throttler operates dynamically within the thermal FPS ceiling. Controllers must never oscillate against one another.
7. **Codec and Architecture Preservation**:
   - The 2-active-MediaCodec invariant (1 AVC, 1 AAC) must be strictly maintained. M0-M11 architecture (RTMP, FLV muxer, audio clock, camera pipeline, scene switcher) must remain fully preserved.

---

## Milestone 13: Stress Testing Requirements
1. **TEST 1 — Long-Duration Stream Test**:
   - Minimum 30 minutes continuous RTMP streaming without manual intervention.
   - Telemetry sampled at fixed 60s intervals: CPU, PSS, Native Heap, Dalvik Heap, RSS, Threads, Sockets, Codecs, FPS, Audio Rate, drops, ABR state.
   - Bounded resources: Zero runaway memory, zero descriptor leaks, strictly 2 active MediaCodecs, zero ANRs, zero crashes.
2. **TEST 2 — A/V Synchronization Stress**:
   - Continuous audio/video timeline tracking.
   - Reconciled timeline duration vs sample count tracking at Start (1m), Middle (15m), and End (30m).
   - AudioCaptureEngine explicit gap-handling synthesizes timestamp-consistent silence PCM frames whenever AudioRecord stalls (`driftUs > 40ms`), bounding end-to-end A/V delta under 4.0s with flat post-recovery drift rate (1.0 ms/min) and strict sample-to-PTS parity.
3. **TEST 3 — Full Overlay Stress**:
   - All overlay types rendering concurrently (Image, Text, Goal, Lower Third, QR, Alert, Widget, GIF).
   - High-frequency mutation and rapid scene updates without OpenGL pipeline stall or texture corruption.
4. **TEST 4 & 9 — Camera + Stream Stress & Lifecycle**:
   - Simultaneous camera and screen composition under sustained streaming.
   - Rapid camera switching (FRONT <-> BACK) and facecam toggling across 10+ cycles with clean teardown.
5. **TEST 5 & 6 — Network Degradation & ABR Cycling**:
   - Controlled network throttling across full ladder: 1200 <-> 800 <-> 500 <-> 300 kbps.
   - Asymmetric recovery: rapid congestion step-down vs 3s cooldown / 3-sample hysteresis upward recovery.
6. **TEST 7 — Reconnection Cycling & Exhaustion**:
   - 10 consecutive forced disconnect/reconnect cycles with bounded outage duration (~4.5s).
   - Exhaustion sequence (1s -> 2s -> 4s -> 8s) terminating in `FAILED` without infinite retry loops.
7. **TEST 8 — 20 Start/Stop Cycles Lifecycle Stress**:
   - 20 complete start broadcast -> stream -> stop -> release cycles.
   - Monotonic resource growth audit: 0 socket leaks, 0 thread leaks, flat PSS.
8. **TEST 10 & 11 — Memory Pressure & Thermal Transitions**:
   - OS memory trim signals (`RUNNING_LOW`, `RUNNING_CRITICAL`) handled with cache eviction and on-demand recreation.
   - Thermal state transitions (NOMINAL -> MODERATE -> SEVERE -> CRITICAL -> NOMINAL) imposing strict ceilings.
9. **TEST 12 — Failure Injection**:
   - Resilient recovery from socket resets, encoder resets, and pipeline stalls without process crash.




