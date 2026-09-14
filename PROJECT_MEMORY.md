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
  - **M9: Adaptive Bitrate & Network Resilience Engine** (APPROVED by ChatGPT — Checkpoint `1cb6145`)
      - **Network Quality Monitor (`NetworkQualityMonitor.kt`)**: Zero-allocation hot-path sliding window for TCP write latency (`LongArray(16)`), throughput (kbps), socket backpressure, and Android `ConnectivityManager.NetworkCallback` capabilities (WIFI, CELLULAR, UNAVAILABLE). State classification: `EXCELLENT`, `HEALTHY`, `DEGRADED`, `POOR`, `CRITICAL`. Transport transition detection implemented; physical Wi-Fi/cellular streaming migration explicitly noted as not validated on this AVD.
      - **Adaptive Bitrate Controller (`AdaptiveBitrateController.kt`)**: 4-tier ladder (1200, 800, 500, 300 kbps).
        * Intentional rapid downscaling rule: Under acute `POOR` network degradation (throughput < 500 kbps or latency > 350 ms), the controller intentionally drops 2 rungs (`1200 -> 500 kbps`, reason=`POOR_NETWORK`, keyframe requested) to immediately relieve acute channel collapse and protect keyframes. Under mild `DEGRADED` conditions, it steps down 1 rung (`1200 -> 800 kbps`, reason=`DEGRADED_LATENCY` / `QUEUE_BACKPRESSURE`). Under sustained pressure, it drops to floor (`500 -> 300 kbps`).
        * Conservative step-up recovery: Requires 3 consecutive healthy evaluation windows (3000ms) + 3000ms cooldown, stepping up 1 tier at a time: 300 -> 500 -> 800 -> 1200 kbps.
      - **Reconnection Engine (`StreamReconnectionManager.kt`)**: Bounded exponential backoff (1s, 2s, 4s, 8s capped at 4 attempts). Verified clean termination into `RtmpConnectionState.FAILED` with zero resource leaks.
      - **State Correctness**: Strict prevention of false `LIVE` state; `DISCONNECTED`, `RECONNECTING`, `FAILED` explicitly surfaced on UI.
      - **Resource Audit & Metric Clarification**:
        * Clarified that `dumpsys media.player | grep -c MediaCodec` (55) is an enumeration of OS-wide registered codec profiles across all formats.
        * Verified via `dumpsys media.metrics` that Fairy Live creates and holds **strictly 2 active MediaCodec instances** (1 AVC video encoder `HardwareVideoEncoder`, 1 AAC audio encoder `AacMediaCodecEncoder`).
        * Post-GC memory stability: 10-cycle disconnect/reconnect audit (`tools/audit_reconnect_10cycles.json`) proves complete stabilization: Native heap delta across cycles 1-10 is 0 KB (13,385 KB), Dalvik heap delta is 0 KB (7,794 KB), PSS delta is +6 KB (62.3 MB), active codecs strictly 2, sockets return to baseline (10). Zero monotonic growth.
      - **3-Run Controlled A/B Benchmark (Condition A: M8 Compositor Baseline vs Condition B: M9 Active Resilience)**:
        * Identical Pixel 8 AVD, 720p 30fps, 5 overlays + GIF, 12s active duration post-warmup.
        * Condition A Mean (Range): CPU 10.0% [10.0-10.0%], PSS 67,684 KB [67,528 - 67,771 KB], Native 14,415 KB [14,393 - 14,429 KB], Dalvik 6,085 KB [5,956 - 6,156 KB], Threads 28, Sockets 4, Queue 40 KB.
        * Condition B Mean (Range): CPU 10.0% [10.0-10.0%], PSS 67,874 KB [67,799 - 67,927 KB], Native 14,485 KB [14,463 - 14,511 KB], Dalvik 6,011 KB [5,925 - 6,065 KB], Threads 28, Sockets 4, Queue 40 KB.
        * Net Overhead: CPU delta 0.0%, PSS delta +190 KB (+0.28%), zero thread/socket accumulation; no memory reduction claimed.
      - **Decoupled Drop Accounting**:
        * `captureDrops=0`, `encoderDrops=0`, `muxDrops=0`, `rtmpDrops=0`, `serverDrops=0`.
        * `queueEvictions`, `deltaFramesEvicted`, `keyframesEvicted=0` (**strictly 0; keyframes and sequence headers are protected from eviction**).
      - **Full Regression**: `./gradlew clean testDebugUnitTest connectedDebugAndroidTest` exited with code 0 (71/71 tasks executed, 108/108 unit tests pass, 5/5 connected tests pass, APK 6.0 MB).
      - **Documented Limitations**:
        * Viewer-side seamless continuity NOT validated in M9.
        * Physical Wi-Fi <-> Cellular handover NOT validated on AVD.
        * No unsupported hardware encoder claims.
        * No unsupported zero-leak claims beyond measured data.
  - **M10: Camera / Face Cam Integration** (OFFICIALLY APPROVED BY CHATGPT — Checkpoint `95dd044`)
      - **Zero-Copy Camera PIP Compositing**: Native `Camera2` integration streaming direct to a dedicated OpenGL `SurfaceTexture` via `GL_TEXTURE_EXTERNAL_OES` with hardware sensor orientation transform (`uTexMatrix`), spatial layout matrix (`uModel`), PIP scaling, and front-camera selfie mirroring.
      - **Low-Overhead Optimal Sizing**: Pure-Kotlin `CameraSize` selector choosing optimal 640x480 preview resolution instead of 4K/12MP sensor resolutions to strictly conserve GPU memory bandwidth and fill rate.
      - **Lifecycle & Switching Safety**: Dedicated background `FairyCameraThread` with sequential teardown and rebuild (`STOP` -> `RELEASE` -> `SELECT` -> `CONFIGURE` -> `START`), non-fatal error fallback, and state synchronization via `CameraStateNotifier`.
      - **UI & IPC Control**: Creator dashboard controls for Facecam toggle and lens switching, backed by exported `StreamControlReceiver` for background IPC broadcast automation.
      - **Active Codecs Invariant**: Active MediaCodec count held strictly bounded at 2 (1 AVC video, 1 AAC audio) at every measurement point during active capture. Codec instances are cleanly stopped and released upon session teardown, and re-initialized when capture starts.
      - **A/B Benchmark (Camera OFF vs Camera ON under identical conditions)**:
        * Condition A (Camera OFF): CPU 10.0%, PSS 68,212 KB (68.2 MB), Native 14,634 KB, Dalvik 4,292 KB, Threads 36, Sockets 12, Camera FPS 0.0, Compositor FPS 30.0, Encoder FPS 29.8, Drops 0.
        * Condition B (Camera ON): CPU 10.0%, PSS 75,794 KB (75.8 MB), Native 15,130 KB, Dalvik 9,798 KB, Threads 36, Sockets 12, Camera FPS 30.0, Compositor FPS 29.9, Encoder FPS 29.8, Drops 0.
        * Net Overhead: CPU delta 0.0%, PSS delta +7,582 KB (+7.4 MB observed under this test condition; exact allocation sources not independently attributed), Native delta +496 KB, Thread delta 0.
      - **10-Cycle Lifecycle Stability Audit**:
        * 10 complete cycles of START -> CAMERA ON -> STREAM -> CAMERA OFF -> CAMERA ON -> FRONT/REAR SWITCH -> CAMERA OFF -> STOP -> START -> STOP.
        * Cycle 1 to Cycle 10 post-GC: PSS bounded from 74,934 KB to 75,444 KB (+510 KB endpoint delta across 10 complete cycles; non-monotonic trajectory peaking at 77.4 MB at cycle 8 before settling to 75.4 MB post-GC, demonstrating bounded consumption rather than zero leaks), Dalvik heap decreased from 8,249 KB to 8,038 KB (-211 KB), Threads stable (32-36), active codecs bounded at 2.
      - **Full Regression**: `./gradlew clean testDebugUnitTest connectedDebugAndroidTest` passed with exit code 0 (115/115 unit tests pass, 11/11 connected tests pass, 71/71 tasks, build duration 31.8s, APK size 6.0 MB).
  - **M11: Scenes & Scene Management** (IMPLEMENTATION & VERIFICATION COMPLETE — Ready for ChatGPT Review)
      - **Zero-Disruption Scene Architecture**: Built pure-Kotlin scene data models (`BroadcastScene`, `SceneTransition`, `SceneBackgroundMode`), `BroadcastSceneManager` with 3 predefined presets (Gameplay, Just Chatting, Be Right Back), lightweight JSON persistence (`SceneRepository`), and observable event bus (`SceneStateNotifier`).
      - **Runtime Switching Invariant**: Stream, RTMP connection, AVC video MediaCodec, AAC audio MediaCodec, audio pipeline, and network resilience monitor remain 100% continuous and active during scene transitions. Zero reconnects, zero dropped sockets, and zero false `LIVE` -> `IDLE` state transitions.
      - **Compositor Integration & Lightweight Transitions**: Integrated into `OverlayCompositor` and `GlesRenderer` with instant CUT (0ms) and linear opacity interpolation CROSSFADE (300ms) without allocating secondary OpenGL framebuffers, duplicate textures, or offscreen staging surfaces.
      - **UI & IPC Controls**: Dashboard horizontal scene switcher chip bar for one-tap creator switching, backed by exported `StreamControlReceiver` (`ACTION_SWITCH_SCENE`) for headless test automation.
      - **A/B/C Benchmark (`tools/audit_m11_abc_performance.json`)**:
        * Condition A (Gameplay - Screen + Facecam PIP 26% + Watermark): CPU 10.0%, PSS 57,288 KB, Native 13,506 KB, Dalvik 3,273 KB, 2 active codecs, 20 threads.
        * Condition B (Just Chatting - Facecam PIP 40% + Goal Widget + Header): CPU 10.0%, PSS 63,265 KB, Native 13,246 KB, Dalvik 8,049 KB, 2 active codecs, 19 threads.
        * Condition C (Be Right Back - Solid Color + Title + QR): CPU 10.0%, PSS 63,261 KB, Native 13,246 KB, Dalvik 8,049 KB, 2 active codecs, 19 threads.
        * Rapid Switching Condition: CPU 10.0%, PSS 63,262 KB, Native 13,246 KB, 2 active codecs, 19 threads.
      - **10-Cycle Stability Audit (`tools/audit_m11_10cycle_resources.json`)**:
        * 10 complete cycles across Gameplay -> Just Chatting -> BRB -> Gameplay.
        * Post-GC Cycle 1 PSS: 63,173 KB; Cycle 10 PSS: 63,177 KB (net delta +4 KB across 10 cycles, strictly bounded). Native heap delta: 0 bytes (flat at 13,258 KB). Threads bounded at 19. Active codecs strictly bounded at 2.
      - **Full Regression**: `./gradlew clean testDebugUnitTest connectedDebugAndroidTest` passed with exit code 0 (127/127 unit tests pass, 14/14 connected tests pass on Pixel 8 AVD, APK size 6.2 MB).
- **Authorized Milestones**: M11 (Authorized by ChatGPT on 2026-09-14).
- **Locked Milestones**: M12 through M15.

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
- **M10 Bug 1 (android.util.Size JVM Stub in Unit Tests)**:
  - **Issue**: Running unit tests without Robolectric stubbed `android.util.Size.width/height` to 0, failing `testOptimalSizeSelectionConservesGpuBandwidth`.
  - **Fix**: Replaced framework class with pure-Kotlin `data class CameraSize(val width: Int, val height: Int) : Comparable<CameraSize>` in `CameraDeviceInfo.kt`, `CameraCapabilityDetector.kt`, and `CameraCaptureEngine.kt`.
- **M10 Bug 2 (Instrumentation Test Runner Permission Manifest)**:
  - **Issue**: Connected instrumented test APK runner lacked camera permission, causing `CameraCaptureEngine` to fail with SecurityException on startup.
  - **Fix**: Declared `<uses-permission android:name="android.permission.CAMERA" />` in `app/src/androidTest/AndroidManifest.xml` and automated permission grants via `uiAutomation.executeShellCommand` in `@Before`.
- **M10 Bug 3 (Non-Exported Foreground Service Intent IPC)**:
  - **Issue**: Shell commands could not send intents directly to `FairyStreamService` because `android:exported="false"`.
  - **Fix**: Created and registered `StreamControlReceiver` with actions `TOGGLE_FACECAM`, `SWITCH_FACECAM`, and `STOP_CAPTURE`.

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
