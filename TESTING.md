# FAIRY LIVE — TESTING SUITE & VERIFICATION MATRIX

## 1. Test Verification Strategy
Testing covers unit tests, instrumentation, system-level execution, memory stability, thermal/battery endurance, and live streaming transport on target Android devices.

---

## 2. Milestone 9 — Adaptive Bitrate & Network Resilience Verification Matrix

| Category | Test Case | Target / Command | Result | Evidence |
|---|---|---|---|---|
| **Build** | Clean & Assemble | `./gradlew clean assembleDebug` | **PASS** | `BUILD SUCCESSFUL`, 7.00 MB APK (< 15 MB budget) |
| **Unit Tests** | Complete Suite | `./gradlew testDebugUnitTest` | **PASS** | **108/108 unit tests pass** (16 classes, incl. 16 M9 resilience tests) |
| **Device Tests** | On-Device Instrumentation | `connectedDebugAndroidTest` | **PASS** | **5/5 pass** on Pixel 8 Android 15 AVD |
| **Network Monitor** | Circular buffer latency & zero alloc | `NetworkQualityMonitorTest` | **PASS** | `testSlidingWindowLatencyAveraging`, `testZeroAllocationHotPath` |
| **Network Monitor** | Throughput & NetworkTransport | `NetworkQualityMonitorTest` | **PASS** | `testThroughputEstimation`, `testNetworkAvailabilityTransition` |
| **ABR Ladder** | Rapid downscale on congestion | `AdaptiveBitrateControllerTest` | **PASS** | `testImmediateClampingToFloorOnCritical`, `testDownscaleOnPoorNetwork` |
| **ABR Hysteresis** | Anti-oscillation cooldown & step-up | `AdaptiveBitrateControllerTest` | **PASS** | `testConservativeRecoveryRequiresSustainedHealthyWindows`, `testCooldownPreventsOscillation` |
| **ABR Keyframe** | Request sync IDR on downscale | `AdaptiveBitrateControllerTest` | **PASS** | `testKeyFrameRequestedOnSignificantDownscale` |
| **Reconnection** | Exponential backoff sequence | `StreamReconnectionManagerTest`| **PASS** | 1s, 2s, 4s, 8s backoff timings verified |
| **Reconnection** | Capped attempts & exhaustion | `StreamReconnectionManagerTest`| **PASS** | Max 4 attempts enforced, exhausts to null and `EXHAUSTED` state |
| **State Machine** | Strict prevention of false LIVE | `NetworkResilienceTest` | **PASS** | `testStrictConnectionStateTransitions`, `DISCONNECTED` & `RECONNECTING` never `isStreaming` |
| **Drop Accounting**| Decoupled drops vs delta evictions | `BoundedStreamQueue.kt` | **PASS** | `captureDrops=0`, `encoderDrops=0`, `muxDrops=0`, `rtmpDrops=0`, `queueEvictions`, `deltaFramesEvicted`, `keyframesEvicted=0` |
| **Reconnect Audit**| Precise timeline ledger | `tools/audit_reconnect_timeline.json` | **PASS** | RTMP publishing recovered after forced disconnect; outage 1.03s, reconnect 1.027s; viewer-side seamlessness explicitly not validated |
| **ABR Ledger** | Controlled bandwidth throttling | `tools/audit_bandwidth_ledger.json` | **PASS** | Controlled step-down (1200->500->300 kbps) and step-up recovery (300->500->800->1200 kbps) verified |
| **Failure Mode** | Reconnect exhaustion timeline | `tools/audit_reconnect_failure.json` | **PASS** | Exact delays 1006ms, 2010ms, 4004ms, 8004ms -> halts cleanly in `FAILED` state, no crash, 0 leaks |
| **10-Cycle Audit** | 10 repeated reconnect cycles | `tools/audit_10_cycle_resources.json` | **PASS** | No sustained resource growth observed across 10 cycles; Native Heap 14.0-14.9 MB, Threads 34-35 |
| **A/B Overhead** | Controlled Condition A vs B | `tools/audit_ab_benchmark.json` | **PASS** | Identical 5-overlay pipeline: +0.4% CPU, ~0 MB net memory delta |
| **Transport Note** | Interface transition detection | `NetworkQualityMonitor.kt` | **DOCUMENTED**| Transition detection implemented; physical Wi-Fi/cellular streaming migration not validated in M9 |

---

## 3. Milestone 8 — Advanced Multi-Overlay System Verification Matrix

| Category | Test Case | Target / Command | Result | Evidence |
|---|---|---|---|---|
| **Build** | Clean & Assemble | `./gradlew clean assembleDebug` | **PASS** | `BUILD SUCCESSFUL`, 6.22 MB APK (< 15 MB budget) |
| **Unit Tests** | Complete Suite | `./gradlew testDebugUnitTest` | **PASS** | **81/81 unit tests pass** (11 classes, incl. `OverlayCompositorTest` with M8 suite) |
| **Device Tests** | On-Device Instrumentation | `connectedDebugAndroidTest` | **PASS** | **5/5 pass** on Pixel 8 Android 15 AVD (incl. `testMultiOverlaySceneRendering`) |
| **Text Overlay** | Typography, Padding & Background | `OverlayCompositorTest` | **PASS** | `testTextOverlayConfigAndFactory`: fontSize, textColor, background card |
| **Goal Overlay** | Value & Progress Fraction Math | `OverlayCompositorTest` | **PASS** | `testGoalOverlayProgressFractionCalculation`: 0.0 to 1.0 clamped progress |
| **Lower Third** | Broadcast Banner Structure | `OverlayCompositorTest` | **PASS** | `testLowerThirdOverlayConfig`: title, subtitle, accent stripe, zIndex=20 |
| **QR Generation** | Pure-Kotlin QR Matrix 21x21 | `OverlayCompositorTest` | **PASS** | `testQrMatrixGenerationHasValidFinders`: finder patterns, timing strips, zero ZXing dep |
| **Resource Limits** | Low-End Memory Constraint | `OverlayCompositorTest` | **PASS** | `testCompositorLimitsEnforcedOnScene`: max 10 active overlays strictly enforced |
| **Multi-Overlay** | Multi-Layer Scene Ordering | `OverlayCompositorTest` | **PASS** | `testMultiOverlaySceneOrdering`: ascending z-index ordering across all types |
| **GL Multi-Layer** | 4 Simultaneous Overlays to Surface | `CompositorInstrumentedTest` | **PASS** | `testMultiOverlaySceneRendering`: real AVC encoder surface, all 4 texture handles valid |
| **Live Multi-Stream**| RTMP Broadcast with 5 Overlays | Real Ingest Server | **PASS** | 94.6s run: 346 V, 2,968 A, 0 drops, qDepth=1, clean socket teardown |
| **Alert Trigger** | On-Screen Alert & Sound | UI & Service Action | **PASS** | Animated alert with headline, subtext, and auto-dismissal after 4000ms |
| **A/B Comparison** | M7 Single vs M8 Multi-Overlay | `tools/m8_multi_overlay.log` | **PASS** | Only +3.8% CPU, +0.7 MB PSS overhead for 5 simultaneous active overlays |

---

## 3. Milestone 7 — Broadcast Overlay Compositor Verification & Revalidation Matrix

| Category | Test Case | Target / Command | Result | Evidence |
|---|---|---|---|---|
| **Build** | Clean & Assemble | `./gradlew clean assembleDebug` | **PASS** | `BUILD SUCCESSFUL`, 6.15 MB APK (< 15 MB budget) |
| **Unit Tests** | Complete Suite | `./gradlew testDebugUnitTest` | **PASS** | **81/81 unit tests pass** (11 classes, incl. `OverlayCompositorTest` 10 tests) |
| **Device Tests** | On-Device Instrumentation | `connectedDebugAndroidTest` | **PASS** | **4/4 pass** on Pixel 8 Android 15 AVD (incl. `CompositorInstrumentedTest`) |
| **Overlay Model** | Normalized Coords & Sanitization | `OverlayCompositorTest` | **PASS** | $x, y, w, h \in [0.0, 1.0]$, opacity clamp, visibility state |
| **Scene Management** | Z-Order & Ordering | `OverlayCompositorTest` | **PASS** | Sorted layer retrieval, layer dedup-by-id, dynamic toggle |
| **Transform Math** | Resolution Invariance | `OverlayCompositorTest` | **PASS** | Aspect-ratio preserving height/width calculations across profile changes |
| **GLES Renderer** | Real Encoder Input Surface Init | `CompositorInstrumentedTest` | **PASS** | EGL14 display, context, window surface bound to MediaCodec `video/avc` input Surface |
| **Visual Validation** | Pixel-Level Readback Assertion | `CompositorInstrumentedTest` | **PASS** | `glReadPixels` confirms non-clear color (`rgba=(36, 208, 180, 207)`) matching teal circle logo |
| **Resource Cache** | PNG Decode & LRU Texture Cache | `OverlayResourceManager` | **PASS** | Background bitmap decode from assets, bounded cache, clean release |
| **Duplicate Prevention** | Timestamp Guard & Buffer Tracking | `GlesRenderer.consumeNewFrame` | **PASS** | **4,217 duplicate renders skipped** in 78.1s audit; renders occur if and only if new capture buffer latched |
| **A/B Baseline (A)** | Compositor OFF Passthrough | On-device RTMP Stream | **PASS** | 116s run: 1,285 V (11.04 FPS), 5,011 A (43.08 AU/s), 0 drops, 20.6% CPU, 67.7 MB PSS |
| **A/B Composite (B)** | Compositor ON with Watermark | On-device RTMP Stream | **PASS** | 78s run: 810 V (10.41 FPS), 3,351 A (43.09 AU/s), 0 drops, 34.4% CPU, 75.2 MB PSS, steady 10.4 FPS matched to source |
| **Clean Teardown** | Stop & Resource Cleanup | Notification "Stop Service" | **PASS** | GLES renderer released, EGL terminated, HandlerThread quitSafely, zero leaked surfaces |
| **Creator Control UI** | Dashboard Toggle Switch | On-device UI | **PASS** | MaterialCheckBox toggle, live status readout ("Compositor: ON \| 10.4 fps (src: 10.4) \| ...") |

---

## 3. Milestone 6 — Creator Experience, Error UX & Secure Config Verification & Revalidation Matrix

| Category | Test Case | Target / Command | Result | Evidence |
|---|---|---|---|---|
| **Build** | Clean & Assemble | `./gradlew clean assembleDebug` | **PASS** | `BUILD SUCCESSFUL`, 5.8 MB APK |
| **Unit Tests** | Complete Suite | `./gradlew testDebugUnitTest` | **PASS** | **71/71 unit tests pass** (10 classes, incl. `CreatorExperienceTest` 17 + `CaptureFailureRegressionTest` 10) |
| **Device Tests** | On-Device Instrumentation | `connectedDebugAndroidTest` | **PASS** | **3/3 pass** on Pixel 8 Android 15 AVD (`CodecInspectionTest`) |
| **Failure Path** | Null VirtualDisplay → ERROR, no false LIVE | `CaptureFailureRegressionTest` | **PASS** | Engine never reports CAPTURING on null VD; `CaptureState.ERROR` reachable; notifiers reset to IDLE; error bus fires; rapid errors don't crash |
| **Test A** | Current M6 flow (full-display) | On-device GO LIVE | **PASS** | "Entire screen" dialog → LIVE → video + audio flowing (263s success run) |
| **Test B** | Full-screen capture | On-device | **PASS** | **14,679,930 bytes, 3,129 video + 11,326 audio AUs**, clean stop |
| **Test C** | Single-app selector (Android 15) | Documented | **KNOWN LIMITATION** | User selected single-app → `RECORD_CONTENT_TASK - id=-1` bind failure → null VD (root defect; full-display path works reliably) |
| **Test D** | Fresh app/task state | force-stop + relaunch | **PASS** | Clean baseline, no stale state |
| **Test E** | Repeated authorization | GO LIVE → Cancel → GO LIVE → Start | **PASS** | Cancel → OFFLINE/CAPTURE IDLE (no false LIVE); accept → streams |
| **Test F** | Clean stop → restart | Stop via notification → GO LIVE | **PASS** | Stop → OFFLINE + Frames:0; restart → streams again (128s run, 1,317 video + 5,512 audio) |
| **Success Path** | V+A both present at server | Verified server | **PASS** | Audio 43.07 AU/s (= theory 43.066); video ~11.9 fps (full-display swiftshader throttle); A/V first/last diff ≤ 82ms |
| **Per-Stage FPS Trace** | Encoder → muxer → RTMP | `STAGE-AUDIT` log | **PASS** | 492.56s run: muxedV≈sentV at every 5s sample, drops=0, qDepth=1 — zero pipeline loss; fps ceiling is upstream (VirtualDisplay), evidence in `tools/m6_final_audit.log` |
| **Denial-Path Error UX** | GO LIVE → Cancel projection | On-device + unit test | **PASS** | Error card "Screen Recording Permission Denied" + OPEN SETTINGS; OFFLINE/CAPTURE IDLE; server receives 0 bytes; `testProjectionDeniedCreatorErrorCard` |
| **Credential Masking** | Plaintext key concealment | UI + Logcat Audit | **PASS** | Dashboard `Key: li•••••••••••••••••••••yz`; Settings EditText `password=true`; **zero** plaintext in logcat |

---

## 3. Milestone 5 — Streaming Pipeline Verification & Revalidation Matrix

| Category | Test Case | Target / Command | Result | Evidence |
|---|---|---|---|---|
| **Build** | Clean & Assemble | `./gradlew clean assembleDebug` | **PASS** | `BUILD SUCCESSFUL in 22s`, 6.7 MB APK |
| **Unit Tests** | Complete Suite | `./gradlew testDebugUnitTest` | **PASS** | **44/44 unit tests pass** across 8 test classes |
| **AMF0 Codec** | Number, String, Object, ECMA Array | `StreamPipelineTest` | **PASS** | Full round-trip serialization and deserialization verified |
| **FLV H.264 Muxing** | Annex-B to AVC NALU & Sequence Header | `StreamPipelineTest` | **PASS** | Generated `AVCDecoderConfigurationRecord` (CSD) and 4-byte length-prefixed NALUs |
| **FLV AAC Muxing** | Raw AAC to FLV Audio Tag & Header | `StreamPipelineTest` | **PASS** | Generated `AudioSpecificConfig` (`0x12, 0x08` for 44.1kHz mono) and `0xAE` tag headers |
| **FLV Metadata** | onMetaData Script Tag Generation | `StreamPipelineTest` | **PASS** | Correct AMF0 script tag with dimensions, framerate, bitrates, and codec IDs |
| **Backpressure** | Bounded Queue Eviction | `testBoundedQueueCapacityAndEviction` | **PASS** | Dropped delta video frames during congestion; strictly preserved keyframes & sequence headers |
| **RTMP Loopback** | In-Process Mock RTMP Ingest | `testRtmpClientConnectAndPublishLoopback` | **PASS** | Completed C0-C2 handshake, Connect, CreateStream, Publish, and chunk transmission |
| **Device Instrumentation** | On-Device RTMP Socket Broadcast | `connectedDebugAndroidTest` | **PASS** | Verified live socket transmission and chunk multiplexing on Pixel 8 Android 15 AVD |
| **Live UI Broadcast** | End-to-End Screen + Audio RTMP Stream | Physical Pixel 8 AVD | **PASS** | Encoded & streamed live over RTMP; 860 AAC units, 459 video frames |
| **AAC Frame Accounting** | Access Unit vs Duration Verification | `tools/test_rtmp_server.py` | **PASS** | **860 AAC units in 19.946s** ($43.11\text{ fps}$), exactly matching $1024 / 44100 = 23.2199\text{ ms}$ interval |
| **A/V Synchronization** | Monotonic Timeline Duration Parity | `verified_stream_report.json` | **PASS** | Audio duration: 19.946s, Video duration: 19.901s, Delta: **45 ms** (drift-free) |
| **Bitrate Accounting** | Payload & Protocol Overhead Audit | `verified_stream_report.json` | **PASS** | Video: 1115.8 kbps, Audio: 96.8 kbps, Combined: 1212.6 kbps, Overhead: <0.05% |
| **Stream Key Masking** | Plaintext Key Concealment | UI & Logcat Audit | **PASS** | Masked format `li•••••••••••••••••••••yz` in UI; zero plaintext leaks in logs |
| **Active Stream Memory** | Memory Consumption during Broadcast | `dumpsys meminfo` | **PASS** | Total PSS: **68.8 MB** (well below low-end threshold) |
| **Pipeline CPU** | CPU Load during Broadcast | `top` | **PASS** | App CPU: **25.9%** (concurrent capture, video encode, audio encode, mux, and RTMP send) |
| **Reconnection Logic** | Socket Drop Handling | `StreamPipeline` | **PASS** | Exponential backoff (1s, 2s, 4s), capped at 3 attempts |
| **Post-Stop Teardown** | Resource Release | `dumpsys meminfo` | **PASS** | Clean socket and surface release; zero native leaks |

---

## 3. Historical Milestone Results
- **M0**: Cold launch (2,419 ms), PSS (48 MB), 2 baseline tests.
- **M1**: Service lifecycle, navigation, capability detection, 17 tests.
- **M2**: Zero-copy VirtualDisplay capture at 30 fps, even-dimension clamping, 20 tests.
- **M3**: Hardware H.264 MediaCodec video encoding, adaptive 9:20 scaling ($480 \times 1056$), 27 tests.
- **M4**: Native AudioRecord PCM capture, MediaCodec AAC-LC encoding, canonical `MediaClock` A/V synchronization, 32 unit tests + 2 device instrumentation tests.
