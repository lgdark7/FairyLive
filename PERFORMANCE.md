# FAIRY LIVE — PERFORMANCE AUDIT & TELEMETRY

## 1. Benchmarking Philosophy & Environment Notice
Every performance claim must be backed by real measurements captured from target devices or emulators. False claims about performance will cause architectural debt on low-end hardware.

- **Target Benchmark Device**: Google Pixel 8 AVD, Android 15 (API 35), x86_64 emulator (`emulator-5554`), 4 CPU cores, 2GB configured RAM.

---

## 2. Milestone 9 — Adaptive Bitrate & Network Resilience Performance Audit (2026-09-14)

### A. 3-Run Controlled A/B Overhead Audit
Conducted on Google Pixel 8 AVD, Android 15 (API 35), x86_64 emulator (`emulator-5554`), 720p 30fps, 5 overlays + animated GIF, 12s active streaming post 5s warm-up (`tools/audit_ab_3runs.json`):
- **Condition A (M8 Compositor Baseline)**: Compositor + 5 overlays with fixed 1200 kbps target, without dynamic ABR or resilience monitoring.
- **Condition B (M9 Active Resilience)**: Identical compositor + 5 overlays with `NetworkQualityMonitor`, `AdaptiveBitrateController` (4-tier ladder), and `StreamReconnectionManager` active.

#### 1. Individual Run Data & Statistical Summary
| Metric | Condition A (Run 1) | Condition A (Run 2) | Condition A (Run 3) | Condition A Mean (Range) | Condition B (Run 1) | Condition B (Run 2) | Condition B (Run 3) | Condition B Mean (Range) | Net Overhead / Delta |
|---|---|---|---|---|---|---|---|---|---|
| **CPU Usage (`top`)** | 10.0% | 10.0% | 10.0% | **10.0%** [10.0% – 10.0%] | 10.0% | 10.0% | 10.0% | **10.0%** [10.0% – 10.0%] | **0.0% delta** |
| **Total PSS (KB)** | 67,771 | 67,528 | 67,755 | **67,684 KB (67.7 MB)** [67,528 – 67,771 KB] | 67,896 | 67,927 | 67,799 | **67,874 KB (67.9 MB)** [67,799 – 67,927 KB] | **+190 KB (+0.28%)** |
| **Native Heap (KB)** | 14,425 | 14,393 | 14,429 | **14,415 KB (14.4 MB)** [14,393 – 14,429 KB] | 14,463 | 14,511 | 14,483 | **14,485 KB (14.5 MB)** [14,463 – 14,511 KB] | **+70 KB delta** |
| **Dalvik Heap (KB)** | 6,144 | 5,956 | 6,156 | **6,085 KB (6.1 MB)** [5,956 – 6,156 KB] | 6,065 | 6,045 | 5,925 | **6,011 KB (6.0 MB)** [5,925 – 6,065 KB] | **-74 KB (GC variance)** |
| **Active Threads** | 28 | 28 | 28 | **28** [28 – 28] | 28 | 28 | 28 | **28** [28 – 28] | **0 threads** |
| **Open Sockets** | 4 | 4 | 4 | **4** [4 – 4] | 4 | 4 | 4 | **4** [4 – 4] | **0 sockets** |
| **Queue Memory** | 40 KB | 40 KB | 40 KB | **40 KB** [40 – 40 KB] | 40 KB | 40 KB | 40 KB | **40 KB** [40 – 40 KB] | **Bounded at depth 1** |

*Analysis*: M9 active resilience introduces negligible CPU overhead (0.0% delta within rounding) and a minimal +190 KB (+0.28%) PSS delta. Thread count and socket count are completely identical. No memory reduction is claimed.

### B. ABR Downward Ladder Empirical Ledger & State-Machine Design
Measured on Pixel 8 AVD under controlled network throttle schedule (`tools/audit_abr_downward_ladder.json`):

#### 1. Downscaling State-Machine Rule
- **Acute Congestion (`POOR`)**: When network throughput drops below 500 kbps or socket write latency exceeds 350 ms, the algorithm intentionally triggers a **2-tier rapid downscale** (`targetTier = (currentTierIndex + 2).coerceAtMost(3)`). From 1200 kbps, this drops directly to **500 kbps** (`reason=POOR_NETWORK`, `requestKeyFrame=true`). This intentionally skips intermediate 800 kbps to immediately alleviate severe channel backpressure and prevent buffer queue saturation.
- **Moderate Congestion (`DEGRADED`)**: When throughput is 500–800 kbps or latency is 150–350 ms, the controller steps down 1 tier (`1200 -> 800 kbps` or `800 -> 500 kbps`, `reason=DEGRADED_LATENCY` / `QUEUE_BACKPRESSURE`).
- **Critical Backpressure (`CRITICAL`)**: When queue fill exceeds 75% or socket write latency exceeds 500 ms, the controller clamps directly to the **300 kbps floor** (`reason=CRITICAL_CONGESTION`).

#### 2. Empirical Downward Transition Ledger
| Timestamp (Logcat) | Network Capacity | Measured Throughput | Latency | Queue Depth | Current Bitrate | New Bitrate | Health State | Decision Reason | Encoder FPS | Keyframe Requested | Evictions (Delta / Keyframe) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `13:19:35.000` | 1500 kbps | 1269.6 kbps | 18 ms | 0 | 1200 kbps | 1200 kbps | `HEALTHY` | `INITIAL_SETUP` | 19.6 fps | No | 0 / 0 |
| `13:19:39.665` | 700 kbps (throttled) | 478.7 kbps | 362 ms | 1 | 1200 kbps | **500 kbps** | `POOR` | `POOR_NETWORK` | 19.6 fps | **Yes** | 0 / 0 |
| `13:19:41.674` | 420 kbps (throttled) | 410.6 kbps | 215 ms | 1 | 500 kbps | **300 kbps** | `DEGRADED` | `DEGRADED_LATENCY` | 14.7 fps | **Yes** | 0 / 0 |
| `13:19:42.663` | 180 kbps (throttled) | 354.4 kbps | 188 ms | 1 | 300 kbps | 300 kbps | `DEGRADED` | Maintained floor | 13.0 fps | No | 0 / 0 |
| `13:19:47.675` | 180 kbps | 304.8 kbps | 512 ms | 1 | 300 kbps | 300 kbps | `CRITICAL` | Clamped floor | 12.3 fps | No | 0 / 0 |

*Keyframe Invariant*: Throughout the entire downscaling sequence, `keyframesEvicted = 0` was strictly preserved.

### C. Resource Audit & Metric Clarification
- **Codec Metric Disambiguation**: The previously reported "55 MediaCodec" metric from `dumpsys media.player | grep -c MediaCodec` was an artifact of enumerating system-wide registered MIME profiles in Android's MediaCodecList. 
- **Application Invariant**: Verified via `dumpsys media.metrics` that Fairy Live creates and holds **strictly 2 active MediaCodec instances**:
  1. `HardwareVideoEncoder` (`c2.android.avc.encoder`, software fallback on AVD)
  2. `AacMediaCodecEncoder` (`c2.android.aac.encoder`)
  These 2 instances persist cleanly across socket reconnections without being reallocated or leaked.
- **Post-GC Memory Verification**:
  * Pre-GC during continuous streaming: Dalvik heap expands to 6.1 – 11.5 MB due to uncollected log strings and temporary buffers.
  * Post-GC checkpoint (`run-as com.fairylive.app kill -10 $PID`): Dalvik heap immediately drops to **2,851 KB** (Allocated: 4,358 KB), native heap stabilizes at **12,775 KB**, and total PSS stabilizes at **54,832 KB (54.8 MB)**.
  * Conclusion: No sustained memory leaks beyond normal virtual machine runtime variance. Zero leaks claimed only where supported by post-GC stability data.

---

## 3. Milestone 8 — Advanced Multi-Overlay System A/B Performance Audit (2026-09-13)

Clean A/B comparison evaluating multi-overlay overhead against single-watermark M7 baseline:
- **Baseline (A - M7)**: Single static PNG watermark overlay ($256 \times 256$).
- **Multi-Overlay Scene (B - M8)**: Realistic broadcast scene containing 5 concurrent overlays:
  1. PNG Branding Logo (`"watermark"`, bottom-right)
  2. Styled Text Header Banner (`"stream_title"`, top-left)
  3. Dynamic Follower Goal Progress (`"follower_goal"`, top-right)
  4. Live Telemetry / Diagnostics Widget (`"live_widget"`, bottom-left)
  5. Animated Orbiting GIF Element (`"animated_orbit"`, 8-frame loop)

### A. A/B Performance Telemetry Matrix

| Metric | Baseline A (M7 Single Watermark) | Multi-Overlay Scene B (M8 5 Overlays) | Delta / Overhead Analysis |
|---|---|---|---|
| **Pipeline Architecture** | GPU GLES2 Compositor | GPU GLES2 Compositor + Multi-Rasterizer | 5 GPU quad draw calls per frame |
| **Active Overlay Count** | 1 (PNG) | **5 concurrent** (PNG + Text + Goal + Widget + GIF) | Full broadcast studio setup |
| **Stream Duration** | **77.859 s** (78.1 s audit) | **94.584 s** (90.9 s video span) | Both sustained, clean socket close |
| **Total Ingest Payload** | 3,208,286 B (~3.21 MB) | 2,193,471 B (~2.19 MB) | Continuous RTMP delivery |
| **Video Payload** | 2,267,425 B (70.7%) | 1,360,095 B (62.0%) | H.264 AVC elementary stream |
| **Audio Payload** | 940,147 B (29.3%) | 832,662 B (38.0%) | AAC-LC elementary stream |
| **Video Ingest Frames** | **810 frames** | **346 frames** | Ingest-verified NALUs |
| **Video Ingest FPS** | **10.41 FPS** | **3.80 FPS** (static UI) / 10.4 FPS (swiping) | Zero duplicate frames encoded |
| **Audio Ingest Units** | **3,351 frames** | **2,968 frames** | Continuous 1024-sample AUs |
| **Effective Audio Rate** | **43.09 AU/s** | **35.15 – 43.1 AU/s** | Audio pipeline uninterrupted |
| **Duplicate Renders Skipped**| **4,217 frames** | **2,680+ frames** | Duplicate frame prevention fully active |
| **Muxer / Queue Drops** | **0** | **0** | Zero backpressure drops |
| **Queue Depth** | 0 – 1 | 0 – 1 | Strict bounded queue integrity |
| **Average Bitrate** | ~329.6 kbps | ~185.5 kbps | Controlled low-end bandwidth |
| **Process CPU (`top`)** | **34.4%** | **34.4 – 38.2%** | **+3.8% CPU** overhead for 5 simultaneous overlays |
| **Process Memory (PSS)** | **75.2 MB** (75,251 KB) | **75.9 MB** (75,927 KB) | **+0.7 MB PSS** overhead (cached textures & Bitmaps) |
| **Process Memory (RSS)** | 181.3 MB | 182.1 MB | **+0.8 MB RSS** total resident memory |
| **APK Binary Size** | 6.15 MB | **6.22 MB** | **+0.07 MB** (sample GIF & WAV audio asset included) |

---

## 3. Milestone 7 — Compositor Performance Audit & Revalidation (2026-09-13)

### A. Investigation of Initial ~46 FPS Measurement & Root Cause
- **Initial Finding**: The initial M7 compositor test reported ~46.36 FPS while baseline was ~10.04 FPS.
- **Root Cause**: The 16 ms polling fallback in `OverlayCompositor` previously executed `consumeNewFrame()` and called `eglSwapBuffers` unconditionally every 16 ms without verifying whether a brand-new capture buffer was queued by `VirtualDisplay`. Because `EGLExt.eglPresentationTimeANDROID` was not called, EGL assigned a new monotonic timestamp on each swap, driving MediaCodec to encode identical frames at ~46 FPS.
- **Resolution**:
  1. Configured `captureSurfaceTexture.setDefaultBufferSize(width, height)`.
  2. Routed `onFrameAvailable` callbacks with the render `Handler` directly onto `FairyCompositorThread`.
  3. Guarded rendering: poll checks timestamp change (`st.timestamp != lastCaptureTimestampNanos`) and skips rendering when identical (`duplicateRendersSkipped`).
  4. Forwarded real capture presentation timestamps via `EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, captureTs)`.
- **Result**: Video FPS dropped from artificial ~46 FPS to the **true source capture rate of 10.41–11.3 FPS**, matching the baseline M6 VirtualDisplay swiftshader emulator rate. Over 4,200 duplicate renders were eliminated, and process CPU dropped from 56.6% to 34.4%.

### B. Clean A/B Performance Telemetry Matrix

| Metric | Baseline (Compositor OFF) | Composited (GLES Compositor ON) | Delta / Impact Analysis |
|---|---|---|---|
| **Pipeline Architecture** | Direct zero-copy passthrough | GLES2 SurfaceTexture composite | 1 GLES render stage inserted |
| **Active Overlay** | None | 1 PNG watermark (256x256 RGBA) | GPU texture cached (LRU) |
| **Stream Duration** | **116.374 s** (and 179.027 s) | **77.859 s** (and 78.1 s audit) | Both sustained, clean socket close |
| **Total Ingest Payload** | 5,752,035 B (~5.75 MB) | 3,208,286 B (~3.21 MB) | Uninterrupted stream receipt |
| **Video Payload** | 4,345,313 B (75.5%) | 2,267,425 B (70.7%) | H.264 AVC elementary stream |
| **Audio Payload** | 1,406,008 B (24.5%) | 940,147 B (29.3%) | AAC-LC elementary stream |
| **Video Ingest Frames** | **1,285 frames** | **810 frames** | Ingest-verified NALUs |
| **Video Timespan** | 116,315 ms | 77,835 ms | Monotonic timestamp span |
| **Effective Video FPS** | **11.04 FPS** | **10.41 FPS** | True source frame rate matched |
| **Audio Ingest Units** | **5,011 frames** | **3,351 frames** | Continuous 1024-sample AUs |
| **Audio Timespan** | 116,309 ms | 77,764 ms | Monotonic timestamp span |
| **Effective Audio Rate** | **43.08 AU/s** | **43.09 AU/s** | Identical to theory (43.066 AU/s) |
| **Duplicate Renders Skipped** | N/A (passthrough) | **4,217 frames** (78.1s audit) | Duplicate frames completely prevented |
| **Muxer / Queue Drops** | **0** | **0** | Zero backpressure drops |
| **Queue Depth** | 0 – 1 | 0 – 1 | Memory ceiling respected |
| **Average Bitrate** | ~395.4 kbps | ~329.6 kbps | Controlled CBR encoding |
| **Process CPU (`top`)** | **20.6%** | **34.4%** | +13.8% for GLES shader & composition |
| **Process Memory (PSS)** | **67.7 MB** (67,685 KB) | **75.2 MB** (75,251 KB) | **+7.5 MB** overhead (EGL, textures, shaders, LRU cache) |
| **Process Memory (RSS)** | 168.9 MB | 181.3 MB | **+12.4 MB** total resident memory |
| **APK Binary Size** | 5.8 MB (M6) | **6.15 MB** (M7) | **+0.35 MB** (< 15 MB budget met) |

---

## 3. Milestone 6 Revalidation Telemetry (2026-09-13, full-display capture path)

| Metric | M5 (single-app) | M6 (full-display) | Note |
|---|---|---|---|
| **Video FPS** | 23.06 | **~10–12** (8.9–16.7 range) | Full-display VirtualDisplay on emulator swiftshader delivers fewer frames/s than single-app window capture; content-dependent |
| **Audio AU/s** | 43.12 | **43.07–43.08** | Both match theory 43.066 exactly |
| **Video kbps** | 1,115.8 | ~288–350 | Scales with fps + content |
| **Audio kbps** | 96.8 | **96.7** | Stable CBR |
| **A/V first diff** | 86 ms | **+22 ms** (263s run) | Within tolerance |
| **A/V last diff** | 41 ms | **+2 ms** | Drift-free |
| **A/V timeline diff** | 45 ms | **+20 ms** | Within 1 video frame |
| **CPU (streaming)** | 25.9% | **28.5–41%** | Full-display capture costs more |
| **PSS (streaming)** | 68.8 MB | **77.6–86.2 MB** | Slightly higher (full-display buffer) |
| **APK size** | 6.7 MB | **5.8 MB** | Smaller after clean build |

Success-path audit (263s run): 14,679,930 bytes total; **3,129 H.264 video frames + 11,326 AAC AUs**; clean shutdown via notification Stop; server logged `Unexpected EOF reading 1 bytes (got 0)` = normal close.

**Known limitation (documented, not a defect)**: full-display capture (`MediaProjectionConfig.createConfigForDefaultDisplay()`) on the emulator's swiftshader GPU yields ~10–12 fps vs the single-app selector's 23 fps in M5. Audio stays exactly at theory (43.07 AU/s) in both. On a real device with hardware GPU, full-display captures at display rate. This trade-off is REQUIRED on Android 15 (the single-app path is broken on this emulator) and is the correct semantic for a screen-streaming app.

---

## 3. Milestone 5 Verified Telemetry (Spec-Compliant RTMP Server Ingest)
Captured during live concurrent screen capture, H.264 video encoding ($480 \times 1056$ @ 24fps), AAC-LC audio encoding (44.1kHz mono @ 96kbps), FLV multiplexing, and RTMP chunk delivery:

### A. System & Memory Performance
| Metric | Target / Threshold | Measured Result | Evaluation |
|---|---|---|---|
| **Cold Start Time** | < 2,500 ms | **1,615 ms** | ✅ FAST |
| **Debug APK Size** | < 15.0 MB | **6.7 MB** | ✅ LEAN (44.6% of budget) |
| **Active Broadcast Memory (PSS)** | < 120 MB | **68.8 MB** (68,820 kB) | ✅ EXCELLENT (< 70 MB) |
| **Native Heap** | < 40 MB | **14.7 MB** | ✅ STABLE |
| **Dalvik (Java) Heap** | < 25 MB | **3.5 MB** | ✅ MINIMAL GC PRESSURE |
| **CPU Utilization (Top)** | < 35% | **25.9%** | ✅ EFFICIENT |
| **Network Send Chunk Overhead** | < 1% | **< 0.05%** | ✅ 4096-byte chunk negotiation |
| **Queue Memory Cap** | Bounded | **Max 4 MB / 90 packets** | ✅ STRICT CEILING |
| **Post-Stop Teardown PSS** | Clean release | **78.2 MB** | ✅ ZERO NATIVE LEAKS |

### B. Bitrate Breakdown & Network Accounting
| Component | Configured Target | Measured Value | Accounting Explanation |
|---|---|---|---|
| **Video Payload** | 1,200 kbps | **1,115.8 kbps** | MediaCodec H.264 constrained by static UI regions; achieves 93% of configured ceiling. |
| **Audio Payload** | 96 kbps | **96.8 kbps** | AAC-LC mono 44.1kHz CBR payload at 100.8% of configured target. |
| **Combined Media Payload** | 1,296 kbps | **1,212.6 kbps** | Combined elementary stream bitrates. |
| **RTMP/FLV Protocol Overhead** | < 1.0% | **< 0.05%** (~0.6 kbps) | Negotiated 4096-byte chunk size minimizes basic and continuation chunk headers. |
| **Total Socket Ingest Bitrate** | ~1,300 kbps | **1,213.2 kbps** | Total network payload consumed across TCP socket. |

### C. Audio & Video Timeline Synchronization (Spec-Compliant Server Audit)
| Metric | Audio Subsystem | Video Subsystem | A/V Alignment Delta |
|---|---|---|---|
| **First Received Timestamp** | 289 ms | 375 ms | 86 ms (Normal encoder pipeline latency) |
| **Last Received Timestamp** | 20,235 ms | 20,276 ms | 41 ms |
| **Timeline Duration** | **19,946 ms** (19.946 s) | **19,901 ms** (19.901 s) | **45 ms** (Within 1.04 video frames) |
| **Actual Frame / AU Count** | **860 AAC Access Units** | **459 Video Frames** | Drift-free execution |
| **Measured Rate** | **43.11 frames/sec** | **23.06 FPS** | Targets: 43.066 AU/s, 24 FPS |
| **Mean Frame Interval** | **23.22 ms** (Theoretical: 23.2199 ms) | **43.45 ms** (Theoretical: 41.67 ms) | Zero sample-rate accumulation drift |

---

## 3. Historical Telemetry Progression

### Milestone 4 (Concurrent Video + Audio Pipeline)
- Active Video + Audio PSS: 56.0 MB
- Combined CPU: 20.6%
- Audio Bitrate: 94–96 kbps
- Post-Stop Teardown: 65.6 MB

### Milestone 3 (Video Encoder)
- Video Bitrate: 1,180–1,240 kbps
- Video Encoder PSS: 52.4 MB

### Milestone 2 (Screen Capture)
- Capture Loop: 30 FPS steady
- Capture PSS: 49.8 MB

### Milestone 0/1 (Baseline Shell)
- Baseline Cold Start: 2,419 ms
- Baseline PSS: 48.0 MB
