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
   - CameraX / Camera2 integration rendering face-cam into the compositor pipeline.
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
