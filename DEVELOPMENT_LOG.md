# FAIRY LIVE — DEVELOPMENT LOG

## 2026-09-13 — Milestone 3: Hardware Video Encoding (Verification Pass)
- **Milestone**: M3 — Hardware Video Encoding (Verification & Codec Classification)
- **Work Performed**:
  - Executed on-device instrumentation test (`CodecInspectionTest.kt`) to audit every video encoder exposed on the Pixel 8 Android 15 emulator.
  - Determined that the emulator BSP exposes zero hardware-accelerated video encoders (`isHardwareAccelerated = false`, `isSoftwareOnly = true`). Available platform codecs: `c2.android.avc.encoder` (Google Codec2) and `OMX.google.h264.encoder`.
  - Introduced explicit `EncoderType` enum (`HARDWARE`, `SOFTWARE`, `UNKNOWN`) to prevent false claims of hardware acceleration.
  - Upgraded `VideoEncoderCapabilityManager` with strict priority selection (Hardware H.264 surface -> Compatible hardware -> Software H.264 fallback).
  - Investigated portrait resolution handling: source display is 1080x2400 (aspect ratio 9:20 = 0.45). Confirmed that 1080x2400 exceeds the 2048 height limit of `c2.android.avc.encoder` (`isSizeSupported(1080, 2400) == false`).
  - Implemented `AdaptiveResolutionResolver`: dynamically computes aspect-ratio-preserving dimensions scaled proportionally to target profiles (e.g. 720x1600 for Balanced, 480x1056 for Low), strictly aligned to 16-pixel macroblocks, completely eliminating distortion and accidental square cropping.
  - Updated UI: badge explicitly displays `SOFTWARE ENCODER (FALLBACK)` in amber when software codec is active, and telemetry prints `[SW] c2.android.avc.encoder`.
  - Verified live on Pixel 8 emulator:
    * Adaptive resolution configured to 480x1056 @ 24fps (1200 kbps).
    * Streamed over 120+ frames via CPU-copy-free Surface pipeline.
    * Real-time on-demand IDR keyframe generation verified (Frame #114 size 20,244B).
    * Total PSS 63.5 MB during active encoding, returning cleanly to 61.6 MB after teardown.
- **Files Created / Modified**:
  - `app/src/androidTest/java/com/fairylive/app/CodecInspectionTest.kt`
  - `app/src/main/java/com/fairylive/app/core/capability/model/EncoderCapability.kt`
  - `app/src/main/java/com/fairylive/app/core/capability/DeviceCapabilityDetector.kt`
  - `app/src/main/java/com/fairylive/app/core/video/VideoEncoderCapabilityManager.kt`
  - `app/src/main/java/com/fairylive/app/core/video/model/VideoEncoderConfig.kt`
  - `app/src/main/java/com/fairylive/app/core/video/model/EncoderState.kt`
  - `app/src/main/java/com/fairylive/app/core/video/HardwareVideoEncoder.kt`
  - `app/src/main/java/com/fairylive/app/service/FairyStreamService.kt`
  - `app/src/main/java/com/fairylive/app/ui/dashboard/DashboardFragment.kt`
  - `app/src/test/java/com/fairylive/app/HardwareVideoEncoderTest.kt`
- **Tests**:
  - Unit Tests: 22/22 passing
  - On-Device Instrumentation Test: PASS (`inspectAllDeviceVideoEncoders`)
  - Codec Classification: PASS (`c2.android.avc.encoder` correctly tagged as `SOFTWARE`)
  - Adaptive 9:20 Resolution: PASS (`480x1056`, 0.45 ratio preserved)
  - Live Encoding: PASS (120+ frames encoded with Annex-B NAL output)
  - Keyframe Insertion: PASS (On-demand IDR keyframe verified)
  - Memory & Cleanup: PASS (Returned to 61.6 MB, zero leaks)
- **ChatGPT Feedback**:
  - Verification pass complete, awaiting final M3 approval.

---

## 2026-09-13 — Milestone 2: Screen Capture
- **Milestone**: M2 — Screen Capture
- **Work Performed**:
  - Implemented Native Screen Capture Engine (`core/capture`), MediaProjection and VirtualDisplay zero-copy pipeline.
  - Approved by ChatGPT on 2026-09-13.

---

## 2026-09-13 — Milestone 1: Application Foundation
- **Milestone**: M1 — Application Foundation
- **Work Performed**:
  - Implemented Device Capability Foundation, Lifecycle Foundation, Permission Foundation, Settings Foundation, Service Foundation, and Application Shell UI.
  - Approved by ChatGPT on 2026-09-13.

---

## 2026-09-13 — Milestone 0: Environment Audit & Project Foundation
- **Milestone**: M0 — Environment Audit & Project Foundation
- **Work Performed**:
  - Initialized native Android project with Kotlin 1.9.22, AGP 8.2.2, JDK 17, and established baseline.
  - Approved by ChatGPT on 2026-09-13.
