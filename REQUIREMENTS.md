# FAIRY LIVE — PRODUCT REQUIREMENTS SPECIFICATION

## 1. MUST HAVE (Core & Non-Negotiable)
1. **Pure Native Android Architecture**:
   - 100% Kotlin codebase.
   - Built with Android Studio, Gradle, and native Android SDK APIs.
   - Zero Flutter, React Native, or cross-platform framework code.
2. **Low-End Performance First**:
   - Smooth streaming on 2GB-3GB RAM devices and quad/octa-core budget SoCs.
   - Zero-copy surface rendering pipeline.
   - Strict memory management, avoiding allocations in frame rendering loops.
3. **Screen Capture Subsystem**:
   - MediaProjection API with proper Android 14/15 foreground service permissions and user consent handling.
   - Resilient VirtualDisplay handling with orientation changes.
4. **Hardware Video Encoding**:
   - MediaCodec H.264/AVC hardware-accelerated video encoding with Surface input.
   - Configurable resolution (480p, 720p, 1080p), framerate (24, 30, 60 fps), and bitrate.
5. **Robust Audio Pipeline**:
   - Low-latency AudioRecord microphone capture (16-bit PCM, 44.1kHz/48kHz).
   - Audio mute/unmute and volume control.
   - AAC audio encoding with timestamps synchronized with video.
6. **Decoupled Overlay Composition**:
   - Stream compositor rendering overlays directly into the encoder surface without polluting streamer UI.
   - Static PNG/WebP overlays and dynamic text overlays with position, scale, and opacity controls.
7. **Network Resilience & RTMP Client**:
   - Reliable streaming transport (RTMP/FLV).
   - Exponential backoff reconnect logic.
   - Adaptive frame-dropping / backpressure handling to prevent memory exhaustion under poor network conditions.
8. **Foreground Service & Android Lifecycle**:
   - Compliant Android Foreground Service ensuring uninterrupted background streaming while playing games.
   - Clean resource release on stream termination.

---

## 2. SHOULD HAVE (Enhanced Usability & Polish)
1. **Multiple Simultaneous Overlays**:
   - Multi-layer composition with z-ordering.
2. **Scene Management**:
   - Switchable scenes (e.g. Starting, Gameplay, Chat, Ending) without interrupting active stream connection.
3. **Camera / Face Cam**:
   - CameraX / Camera2 integration rendering face-cam into the compositor pipeline.
4. **Dynamic Telemetry & Adaptive Quality**:
   - Monitoring of encoder FPS, dropped frames, network throughput, and device battery/thermal state.
5. **Custom Creator Presets**:
   - Saving and loading scene layouts and stream server profiles.

---

## 3. FUTURE (Extended Features)
1. Animated overlay formats (GIF, WebP animations, Lottie/vector animations).
2. Internal device/game audio capture (Android 10+ AudioPlaybackCapture API integration where allowed by target apps).
3. Creator soundboard and interactive audio triggers.
4. Stream chat overlay rendering.
