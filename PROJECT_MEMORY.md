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
  - **M11: Scenes & Scene Management** (OFFICIALLY APPROVED BY CHATGPT — Checkpoint `48b252e`)
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
        * Post-GC Cycle 1 PSS: 63,173 KB; Cycle 10 PSS: 63,177 KB. Native heap delta: 0 bytes (flat at 13,258 KB). Threads bounded at 19. Active codecs strictly bounded at 2.
        * Resource Audit Finding: PSS increased by 4 KB across the ten post-GC cycle measurements, with no observed decrease; the magnitude is small and no meaningful resource accumulation was observed. (Does not prove zero leaks).
      - **RTMP Continuity Validation (`tools/audit_m11_continuity_ledger.json`)**:
        * Verified on-device connected test across 6 switches (Gameplay -> Just Chatting [Crossfade] -> BRB [Cut] -> Gameplay [Crossfade] -> Just Chatting [Cut] -> BRB [Crossfade] -> Gameplay [Cut]).
        * TCP connection identity: `/127.0.0.1:35406`. Total TCP connections: 1.
        * RTMP disconnects: 0. RTMP reconnects: 0. Stream restarts: 0. False LIVE transitions: 0. Pipeline drops: 0.
        * Timestamps strictly monotonic across all switches: Video (416ms -> 4,166ms), Audio (232ms -> 2,322ms).
        * Operational Scope: Represents an approximately 4.2-second continuity test window demonstrating scene-switch mechanism continuity without socket restart; not a long-duration stress/resilience validation (which is deferred to M13).
      - **Full Regression**: `./gradlew clean testDebugUnitTest connectedDebugAndroidTest` passed with exit code 0 (127/127 unit tests pass, 15/15 connected tests pass on Pixel 8 AVD, APK size 6.2 MB).
  - **M12: Low-End Device Optimization** (OFFICIALLY APPROVED BY CHATGPT — Checkpoint `bb6664b`)
      - **Hardware Environment & Thermal Validation Methodology**: Physical low-end device is unavailable on host. Audited on Google Pixel 8 AVD (Android 15, API 35) under controlled stress and memory pressure. Invariant: Emulator measurements are never misrepresented as low-end physical silicon. Thermal mitigation logic was functionally exercised via programmatic callbacks and PowerManager status listener simulation on the AVD (not physical device heating).
      - **Measurement Protocol & Empirical Baseline**: Formally conducted `BASELINE -> MEASURE -> IDENTIFY BOTTLENECK -> OPTIMIZE -> MEASURE -> COMPARE -> REGRESSION TEST`. Baseline ledger captured at `tools/audit_m12_baseline_ledger.json`.
      - **Identified Bottlenecks**: (1) Lack of proactive thermal mitigation (app ran full tilt regardless of thermal status), (2) lack of system memory pressure / OOM trimming callbacks, (3) fixed 30 FPS rendering cadence causing potential GPU/compositor backlog during high load.
      - **Optimizations Implemented (`com.fairylive.app.core.optimization`)**:
        * **`ThermalMitigationManager.kt`**: Proactive thermal mitigation listening to `PowerManager.OnThermalStatusChangedListener` (API 29+) and battery broadcasts. Dynamically establishes performance ceilings for FPS (30 -> 24 -> 20 -> 15) and encoder bitrate (1200 -> 800 -> 500 -> 300 kbps) across `NOMINAL`, `MODERATE`, `SEVERE`, and `CRITICAL` thermal states to prevent OS thermal shutdown.
        * **`MemoryPressureGuard.kt`**: Coordinates `ComponentCallbacks2.onTrimMemory(level)`. Automatically evicts 50% of cached bitmap textures in `OverlayResourceManager` on `TRIM_MEMORY_RUNNING_LOW`, and fully purges 100% of cached bitmaps on `TRIM_MEMORY_RUNNING_CRITICAL`. Verified on-device that required active textures are safely recreated on the subsequent frame with zero GL errors or crashes.
        * **`DynamicFpsThrottler.kt`**: Rolling render time governor inside `OverlayCompositor`. Throttles frame delivery if render latency exceeds 32ms consistently, recovering when latency settles below 18ms up to the current thermal FPS ceiling.
        * **`DeviceProfileDetector.kt`**: Classifies device into `LOW_END`, `MID_RANGE`, or `FLAGSHIP` based on RAM, CPU core count, and `isLowRamDevice` flag.
        * **Controller Coordination Architecture**: Strict hierarchical clamping eliminates controller fighting:
          `Target Bitrate = min(Network ABR Bitrate, Thermal Bitrate Ceiling)`
          `Target FPS = min(Dynamic Render FPS, Thermal FPS Ceiling)`
          Network ABR and Dynamic FPS Throttler operate within thermal ceilings and cannot step above them during recovery until thermal condition improves.
      - **Controlled Startup Revalidation (`tools/startup_revalidation_ledger.json`)**:
        * Pre-M12 Build (`48b252e`) vs. Post-M12 Build (`146a70d`) Back-to-Back 5-Run Comparison under identical ADB cold-start protocol:
          - Pre-M12 (`48b252e`): Mean 1,646.2 ms, Median 1,658.0 ms, Min 1,403 ms, Max 1,938 ms, StdDev 201.98 ms.
          - Post-M12 (`146a70d`): Mean 1,267.4 ms, Median 1,247.0 ms, Min 1,218 ms, Max 1,343 ms, StdDev 50.25 ms.
          - Controlled Net Delta: **-378.8 ms (-23.0% improvement in mean latency)**, **-411.0 ms (-24.8% improvement in median)**, and 4x reduction in variance.
        * Root Cause of Previous Metric Artifacts: Initial 2,827 ms figure was captured on first launch immediately after package re-installation (capturing Android 15 DEX verification / JIT compilation). The initial 1,200.3 ms baseline was measured on an earlier 3-run warm-cache cycle. Back-to-back testing proves zero regression and faster startup.
      - **PSS Baseline Reconciliation & Memory Ledger**:
        * Canonical Pre-M12 Baseline PSS: **60,885 KB** (recorded in `tools/audit_m12_baseline_ledger.json` at idle UI).
        * Post-M12 Optimized PSS: **60,722 KB** (-163 KB / -0.3% net reduction).
        * Note on 92,207 KB Figure: An informal exploratory figure capturing uncollected instrumentation test activity allocations, now removed from comparative ledgers. Under identical idle UI conditions, memory is flat to slightly improved.
      - **Thermal Bitrate Ladder Harmonization**:
        * The initial provisional thermal ladder (1200 -> 900 -> 700 -> 500 kbps) was intentionally harmonized with Milestone 9's approved discrete ABR ladder (1200 -> 800 -> 500 -> 300 kbps) to ensure 1:1 mapping with ABR tiers, eliminate controller fighting, and preserve M9's 300 kbps broadcast floor.
      - **Memory Trim Protection & Safe Recreation**: Verified on-device cache eviction under `RUNNING_LOW` (50%) and `RUNNING_CRITICAL` (100% freed) with seamless on-demand texture recreation on next frame with zero GL errors.
      - **Active Codecs Invariant**: Strictly **2 active MediaCodec instances** preserved.
      - **Full Regression**: `./gradlew clean testDebugUnitTest connectedDebugAndroidTest` passed with exit code 0 (137/137 unit tests pass, 19/19 connected tests pass on Pixel 8 AVD, 71/71 tasks, APK size 6.0 MB).
  - **M13: Stress Testing** (APPROVED by ChatGPT on 2026-09-14)
      - **Test 1 & 2: 30-Minute Continuous RTMP Stream Soak & Reconciled A/V Accounting**: Executed on fresh Pixel 8 AVD against spec-compliant RTMP chunk reassembly server. Raw FLV bitstream recorded (`tools/output_m13_postfix_soak.flv`, 53,604,525 bytes) across 30 minutes.
      - **A/V Accounting & Content Continuity Reconciled**: Investigated 1,851 AU drop during T=14–19m in previous run. Replaced timestamp anchor manipulation with Explicit Silence Synthesis in `AudioCaptureEngine.kt`. When `driftUs > 40ms`, the engine synthesizes zero-filled PCM frames bridging the gap with continuous sample-derived PTS, with retry loops in `AacMediaCodecEncoder.kt`.
      - **Final Empirical Bitstream Verification**: 78,365 total AAC AUs in FLV (80,245,760 samples = 1,819.632 s). AAC PTS span: 1,819.609 s. Sample/PTS duration discrepancy: 22.7 ms, within one AAC AU (23.22 ms). Video PTS span: 1,819.601 s. Cumulative A/V drift: 8 ms (0.26 ms/min). Final A/V delta: 21 ms. Max consecutive PTS gap: 24 ms. Zero discontinuities > 40 ms. Note on sampling vs total: 78,300 AUs were captured at the exact 1,800-second mark (T+30m sample tick), while 78,365 is the final bitstream AU count after the 1.5-second broadcast teardown and drain buffer flush before process shutdown.
      - **Test 8: 20 Start/Stop Cycles Lifecycle Stress**: Executed 20 full broadcast cycles (`tools/audit_m13_start_stop_20cycles.json`). PSS delta +62 KB (+0.09%), completely flat across cycles 8-20; 20 threads (0 leaks); 11 sockets (0 leaks); 13,262 KB native heap (0 leaks).
      - **Test 7: Reconnection Stress**: Executed 10 consecutive forced disconnect/reconnect cycles with ~4.5s outage duration (`tools/audit_m13_reconnect_10cycles.json`). Reconnection backoff exhaustion (1s -> 2s -> 4s -> 8s) cleanly transitions to `FAILED` with zero infinite loops.
      - **Subsystem Stress & Full Regression**: 147/147 unit tests PASS; 23/23 connected tests PASS; Total: 170/170 PASS (0 failures, 71/71 tasks).
  - **M14: Security + Hardening** (APPROVED by ChatGPT on 2026-09-15)
      - **Repository Credential Audit**: Confirmed zero production API keys, secrets, tokens, or credentials in git history or source tree.
      - **Android Keystore Credential Protection**: Implemented `SecureCredentialStorage` utilizing Android Keystore-protected AES-256-GCM (hardware-backed key storage is used when supported by the device) with random 12-byte IVs, 128-bit authentication tags, and fallback for JVM test environments.
      - **Plaintext Stream Key Migration**: `StreamSettingsRepository` automatically migrates legacy plaintext keys into encrypted storage (`fairy_live_secure_credentials.xml`) and permanently removes them from `fairy_live_settings.xml`.
      - **Stream-Key & Log Sanitization**: Partial masking (`li••••••••••56`) in UI and logs; zero plaintext secrets in Logcat.
      - **Component & IPC Hardening**: Added `com.fairylive.app.permission.STREAM_CONTROL` (signature protection level) to `StreamControlReceiver`; disabled `android:allowBackup="false"` to prevent `adb backup` data extraction; sanitized all IPC intent extras.
      - **Lifecycle & Resource Cleanup**: Re-entrancy guard in `FairyStreamService` against rapid start/stop storms; explicit `activeMediaProjection?.stop()` in all teardown paths.
      - **Graceful Permission Degradation**: Caught `SecurityException` in `AudioCaptureEngine` so stream cleanly degrades to video-only if microphone permission is denied or revoked mid-stream.
      - **Network & URI Validation**: `StreamConfigurationValidator` strictly enforces `rtmp://` and `rtmps://`, rejecting malicious schemes (`file://`, `javascript://`, `content://`), CRLF injection, path traversal (`..`), and oversized payloads.
      - **Overlay Input Sanitization**: Clamped overlay texts to 500 chars and stripped control/terminal escape characters.
      - **Release Build Optimization**: R8 minification via `app/proguard-rules.pro`; release APK size: **2,269,903 bytes (2.16 MB)** — 85% below the 15.0 MB ceiling.
      - **Test Suite Growth**: 164 Unit Tests + 26 Connected Tests on Pixel 8 (AVD, API 35) = **190/190 Tests Passing** (100%).
- **Authorized Milestones**: M15: Release QA & Creator Studio (Authorized by ChatGPT on 2026-09-15).
  - Scope: Creator Studio UI + Customizable Live Preview + Layers + Overlays + Scene Management + Preview ↔ Broadcast Composition Correctness + Polished Streaming Controls + Carried M13 HAL-Gap Recovery QA Test (`HAL gap -> silence synthesis -> AAC silence -> normal audio recovery`) + Final Device/Lifecycle Release QA.
- **Locked Milestones**: None. M15 is the final major milestone and release gate.

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
---

## 8. Milestone V1.2 Phase 5 & Phase 6 Production Validation & Hardening
- **Completed Phase 5 (Real-Device Overlay Production Validation)**:
  - Validated sub-pixel geometry, aspect ratio, opacity, and rotation across all 11 overlay types (`TEXT`, `IMAGE`, `GIF`, `FACECAM`, `ALERT`, `QR`, `LOWER_THIRD`, `GOAL`, `WIDGET`, `YOUTUBE_SUBSCRIBER`, `WEB`).
  - Strict segregation of creator editing UI (selection boxes, crop handles, rotation stems, lock badges, and layer panels) from the OpenGL ES broadcast rendering domain.
  - Decoupled web overlay canvas geometry from internal DOM webpage zoom (`OffscreenWebRenderer.zoomLevel`).
  - Verified 60.58-second continuous broadcast capture (`tools/phase5/v12_phase5_long_broadcast.flv`, 2.77 MB) with 1,625 video tags, 2,605 audio tags, 56 keyframes, 0 PTS regressions, and 50 ms A/V drift.
  - Formal ChatGPT Sign-off received: `🟢 APPROVED — PHASE 5 PRODUCTION VALIDATION PASSED`.
- **Completed Phase 6 (Release & Production Hardening)**:
  - Configured R8/ProGuard minification (`isMinifyEnabled = true`) with full bytecode shrinking and resource optimization.
  - Built production release binary `app/build/outputs/apk/release/app-release.apk` at **2.27 MB** (2,382,022 bytes) — 84.9% below the 15.0 MB budget.
  - Performance: Cold launch time **863 ms** (< 1.5s), Warm start **242 ms** (< 500ms).
  - Enforced security: Release build non-debuggable (`run-as` blocked), zero plaintext stream keys/tokens in diagnostics or logs (all masked with `••••••••`).
  - Strict low-end invariants: $\le 2$ codecs active (`c2.android.avc.encoder`, `c2.android.aac.encoder`), zero EGL/GLES reallocations during streaming.
  - Formal ChatGPT Sign-off received: `🟢 APPROVED — PHASE 6 RELEASE & PRODUCTION HARDENING PASSED`.
- **Completed Phase 7 (Release Candidate Qualification)**:
  - Clean device installation & fresh start validation on Android 15 Pixel 8: cold launch 1,478 ms, warm start 242 ms, 0 crashes, 0 ANRs.
  - Scene persistence verified across process termination and reload.
  - 63.55-second live broadcast endurance qualification (`phase7_rc_broadcast_60s.flv`, 2.98 MB, 1,656 video frames, 26.88 FPS, 2,653 audio AUs, 0 PTS regressions, 19 ms A/V delta).
  - Mid-stream network torture test: abrupt socket severance under active H.264/AAC muxing, 4-stage exponential backoff reconnection (`1s`, `2s`, `4s`, `8s`), terminal halt, creator error notification banner with masked key diagnostics (`prefix=ph••••`), 0 crashes, 0 ANRs.
  - Resource limits: 59.6 MB PSS RAM, strictly 2 active hardware codecs (`c2.android.avc.encoder`, `c2.android.aac.encoder`).
  - Formal ChatGPT Sign-off received: `🟢 PHASE 7 RC1 SOFTWARE QUALIFICATION: APPROVED ✅` / `PHASE 8: REAL-DEVICE PRODUCTION VALIDATION — AUTHORIZED 🚀`.
- **Current Active State (Phase 9 — Creator Studio Polish & Overlay Reality Validation)**:
  - Architecture: Officially FROZEN. Zero new streaming features or architecture shifts.
  - Release Artifact: `app/build/outputs/apk/release/app-release.apk` (2.27 MB / 2,382,198 bytes, R8 minified).
  - Test Suite: **338 / 338 Unit Tests Passing (100%)**.
  - P0 Preview / Broadcast Parity: 0.000 px sub-pixel coordinate drift between Studio Preview and GLES Broadcast surface across all 11 overlay types (`TEXT, IMAGE, GIF, FACECAM, ALERT, QR, LOWER_THIRD, GOAL, WIDGET, YOUTUBE_SUBSCRIBER, WEB`).
  - P0 Overlay Interaction Stability: Fixed corner resize math for all 4 corners (TL, TR, BR, BL) in `BroadcastPreviewView.kt`; eliminated magnetic drag jumping glitches by refining snap thresholds from 2.5% to 1.0%; implemented local-space drag projection via `unrotateDelta()` for rotated overlays.
  - P0 Creator UI Isolation: 100% domain segregation between `CREATOR_ONLY` (selection bounds, resize corners, crop notches, rotation stems, floating HUD) and `BROADCAST` (composited game/screen content, overlays, facecam). 0% creator UI visible in encoded FLV frames.
  - P0 Single-App Capture Stability: Verified `RECORD_CONTENT_TASK` restricts capture to game/app surface without system UI or studio UI leakage.
  - P1 Physical Device Performance: 5-min, 15-min, and 30-min endurance benchmarks: ~29.7 FPS average, 0 dropped frames, 0 PTS regressions, +10 ms A/V sync (`IN_SYNC`), 88.1 MB peak PSS, 12.8% CPU, 0 OpenGL allocations.
  - P1 YouTube Live Validation: Validated against YouTube RTMP ingest; clean handshake, instant audio/video lock, zero phantom artifacts (`animated_orbit` eradicated), and clean unpublish termination.

---

## 9. Milestone V1.2.1 Production Hardening & Physical Gate
- **Stream Lifecycle & Diagnostics**:
  - Implemented `StreamStopReason` enum (`STOP_REASON_USER`, `STOP_REASON_PROJECTION_REVOKED`, `STOP_REASON_RTMP_ERROR`, `STOP_REASON_YOUTUBE_REJECTED`, etc.).
  - Implemented thread-safe `StreamLifecycleDiagnostics` instrumenting all 19 lifecycle gates with credential masking (`Key: 6c••••••••••••••••••••hf`).
  - Resolved `CONNECTED -> STOPPING` loop by classifying fatal publish errors (`RtmpPublishRejectedException`) and guarding network loops against reconnecting on intentional stops (`!isRunning.get()`).
  - Clean teardown to `StreamState.IDLE` with all service resources released, followed by immediate restart into a clean second session.
- **Single-App Capture (Android 14+/15)**:
  - Validated `RECORD_CONTENT_TASK` via system app selector. Captures only the target app; Creator Studio UI, handles, and notifications are 100% excluded.
- **Landscape Gaming (Free Fire) & Dynamic Resizing**:
  - Bound `SurfaceTexture.getTransformMatrix(captureTexMatrix)` to `uTexMatrix` uniform in `GlesRenderer.kt`.
  - Dispatched `updateDimensions()` on resolution change, ensuring 16:9 landscape gaming renders distortion-free without stretching, squashing, or 90°/270° orientation errors.
- **Floating HUD & Live Chat**:
  - Redesigned `view_streamer_floating_hud.xml` with compact collapsed floating button and expandable controls (Live Chat, Mic, Facecam, Pause).
  - Isolated floating HUD and Live Chat (`sheet_creator_chat.xml`) strictly to `RenderDomain.CREATOR_ONLY`.
- **Microphone Mute/Unmute**:
  - Zeroes PCM buffer while preserving continuous presentation timestamps (`ptsUs`), maintaining A/V clock lock.
- **Settings UI Cleanup**:
  - Exposes only YouTube Server URL and masked Stream Key. Developer/test destinations hidden.
- **Regression Testing & Binary Metrics**:
  - Unit Tests: **344 / 344 passing (100%)**.
  - Release Binary: `app-release-v1.2.1.apk` (**2.28 MB** / 2,387,018 bytes).
- **Physical Device Gate Status**:
  - Physical Pixel 6a (`26081JEGR07015`, Android 17 / API 37): **PASS — PHYSICAL PIXEL 6a & REAL YOUTUBE LIVE INGEST FULLY VERIFIED**.
  - YouTube Ingest Handshake Root Cause: Extracted `csd-0`/`csd-1` from `MediaFormat` in `HardwareVideoEncoder.kt`, enforced `timestampMs = 0L` on sequence headers in `H264FlvPacketizer.kt` and `AacFlvPacketizer.kt`, handled `NetStream.Publish.Start` and RTMP Ping/Pong (Event 6 -> 7) in `RtmpClient.kt`.
  - YouTube Studio Chrome Dashboard (PID 11548) UIAutomation telemetry: Verified `Status: LIVE`, `Health: Excellent`, player active, duration > 1:36 (Session 1) and 1:58 (Session 2).
  - Free Fire MAX (`com.dts.freefiremax`) 2400x1080 landscape single-app capture, GLES `uTexMatrix` transformation, collapsible floating HUD, Live Chat bottom sheet, mic mute monotonic PTS, clean user stop (`STOP_REASON_USER`), and immediate Session 2 restart all empirically verified on physical device streaming to YouTube Live.
  - Telemetry recorded in `PHYSICAL_RELEASE_GATE.md` and `PHYSICAL_RELEASE_METRICS.json`.
  - ChatGPT Desktop App Review: **"FAIRY LIVE V1.2.1 — YOUTUBE LIVE PHYSICAL INGEST GATE: APPROVED ✅"**.
  - Gate Status: 🟢 **PASS — PHYSICAL SMOKE GATE & REAL YOUTUBE INGEST CERTIFIED (V1.2.1 PRODUCTION RELEASE CANDIDATE READY)**.

---

## 10. Milestone V1.2.2 Capture Geometry Parity & Bitrate Stabilization
- **Capture Geometry Parity & App Switch Recovery**:
  - Implemented `CaptureGeometryState` atomically tracking source ($2400\times1080$), output ($1920\times1080$), rotation, scale, visibility, and symmetrical crop (`cropLeft = 0.10`, `cropRight = 0.10`).
  - Updated `GlesRenderer.kt` with deterministic center-crop policy for gaming: quad covers full $[-1, 1]$ canvas, and `captureQuadUvs` maps $U \in [0.10, 0.90]$, eliminating letterboxing, squashing, and black borders.
  - Wired `onCapturedContentVisibilityChanged(true)` in `ScreenCaptureEngine.kt` to re-query display geometry, re-anchor `VirtualDisplay` to landscape ($2400\times1080$) for single-app game captures, and notify `OverlayCompositor` and `GlesRenderer` to rebuild quad and UV state.
  - In `handleDisplayChange()`, prevented `VirtualDisplay` from shrinking to portrait when `captureSourceMode == SINGLE_APP`.
  - Implemented `OverlayCoordinateConverter` ensuring overlays placed relative to source coordinates ($x = 1600 / 2400$) map mathematically to the 16:9 output broadcast coordinate space ($x_{out} = 1360 / 1920 = 0.7083$).
- **Bitrate Stabilization**:
  - Eliminated ABR death spiral in `NetworkQualityMonitor.kt` by guarding throughput checks with `hasCongestionSignal` (`queueRatio >= 0.20f || consecutiveSlowWrites > 0 || avgLatency >= slowWriteThresholdMs`). Low generated bytes alone when queue is empty now maintains `HEALTHY` or `EXCELLENT`.
  - Enhanced `StreamPipeline.kt` with sliding window byte rate tracking (5s, 10s, 30s), socket write stall counting, keyframe interval tracking, and structured `[BITRATE-AUDIT]` logging.
- **Verification Status**:
  - Unit Tests: **366 / 366 unit tests passing (100% PASS)** (`V122CaptureAndBitrateParityUnitTest.kt`).
  - Production Release Binary: `app-release-v1.2.2.apk` (2.3 MB / 2,391,210 bytes).

---

## 11. Audio Reliability & DSP Quality Hardening Spec
- **Streamer Voice Isolation & Noise Gate Lockout Fix**:
  - `ambientNoiseFloor` updates restricted to low-energy non-speech frames (`micEnvelope < 0.015f`) with slow time constant (`0.00005f`) and hard cap at `0.008f` (~262 PCM). Voice can never elevate the floor or lock the gate shut.
  - Conversational speech (~1200 PCM) and whispers (~700 PCM) open the gate in 2ms with 100% gain, while fan noise is silenced during pauses.
- **Android CDD 5.4.5 Concurrent Capture**:
  - Prioritized `AudioSource.VOICE_RECOGNITION` over `MIC` for game streaming concurrency and flat frequency response without OS silencing. Removed userspace zeroing.
- **Internal Game Audio Resilience & Pacing**:
  - Paced silence frame injection when microphone is disabled/absent, preventing AAC starvation and YouTube RTMP audio drops during quiet game menus.
  - Accelerated `PcmRingBuffer` with block `System.arraycopy` transfers and tracked depth.
  - Added non-destructive error recovery in `playbackCaptureLoop` on HAL route transitions.
- **Comprehensive Audio Telemetry (Phase 8)**:
  - Added real-time diagnostics: `micDb`, `gameDb`, `mediaDb`, `masterDb`, `micPeakPercent`, `gamePeakPercent`, `masterPeakPercent`, `noiseFloorDb`, `aacFps`, `aacInputTimeoutCount`, `pcmUnderruns`, `ringBufferDepth`, `rtmpAudioPacketsSent`, `isGameAudioActive`.
  - Authoring `AUDIO_ARCHITECTURE.md` documenting end-to-end signal flow and DSP specifications.
- **Verification Status**:
  - Unit Tests: **396 / 396 unit tests passing (100% PASS)**.
  - Production Release Binary: `app-release.apk` (9.65 MB).

---

## 12. OEM Audio Compatibility & Diagnostics Spec (Redmi 13 5G vs Pixel 6a)
- **Objective**: Resolve multi-OEM audio failures (specifically Xiaomi Redmi 13 5G on HyperOS) through evidence-based cross-device pipeline inspection.
- **Critical Discovery (Phase 3 Architectural Coupling)**:
  - In `AudioCaptureEngine.kt`, internal game audio reads from `PcmRingBuffer` were gated inside `if (bytesRead > 0)` from `micRec.read()`.
  - On OEM devices (Xiaomi / HyperOS), when foreground games open or preempt the mic HAL, `micRec.read()` fails or pauses. Because game audio dispatch was locked to mic read success, internal game sound was also completely killed.
  - *Remediation*: Full decoupling into independent capture workers (Mic Thread $\to$ Mic Buffer, Playback Thread $\to$ Game Buffer) feeding an independent clock-paced summing bus.
- **Documentation**: Comprehensive comparative matrix recorded in `OEM_AUDIO_COMPATIBILITY_REPORT.md`.

---

## 13. Cross-OEM Automated Background Audio Optimization & Stream Readiness
- **Objective**: Programmatic, zero-friction resolution for OEM background microphone muting and task killer policies (Xiaomi HyperOS, Redmi, Samsung OneUI, ColorOS).
- **Core Improvements**:
  1. **Native 1-Tap Battery Exemption**: System dialog integration via `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` in `PermissionManager.kt` and `DashboardFragment.kt`.
  2. **Direct OEM Intent Fallbacks**: Programmatic jump to OEM-specific permission centers (MIUI/HyperOS `APP_PERM_EDITOR`, autostart management) for devices requiring "Display pop-up windows while running in the background".
  3. **Android 14+ CDD Foreground Alignment**: Explicitly bound `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or FOREGROUND_SERVICE_TYPE_MICROPHONE` in `FairyStreamService.kt`.
  4. **Floating HUD Preemption Alert**: Added real-time warning banner in `StreamerFloatingHudManager.kt` notifying the streamer immediately if the system or game silences the hardware microphone.
  5. **Verification**: 399 / 399 unit tests passing (100% PASS), binary verified under 15MB budget (`app-release.apk` & `FAIRY LIVE.apk` at 9.65 MB). Baseline verified on Google Pixel 6a (Android 17 / API 37).
