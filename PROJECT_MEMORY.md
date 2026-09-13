# FAIRY LIVE — PROJECT MEMORY

## 1. Project Objective
Rebuild **FAIRY LIVE** from scratch as a high-performance, professional native Android live-streaming application, with an absolute design focus on **low-end Android devices** (low RAM, constrained CPU/GPU, hardware video encoding, unstable networks).

---

## 2. Complete Requirements Summary
- **Native Android Only**: 100% Kotlin, Android Studio, Gradle, native Android APIs. Zero cross-platform frameworks (No Flutter, React Native, etc.).
- **Decoupled Stream Pipeline**: Outgoing video composited independently from streamer device control UI.
- **CPU-Copy-Free Surface Encoding**: Direct Surface-to-Surface pipeline into MediaCodec H.264/AVC encoder.
- **Low-Latency Audio**: AudioRecord PCM capture, mixer/sync, AAC-LC encoding, monotonic drift-free timestamps.
- **Overlay & Scene System**: Multi-layered compositing (images, text, dynamic metrics) and switchable scenes without stream resets.
- **Network Resilience**: RTMP/FLV client with reconnect and backpressure logic.
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
- **Audio Engine**: AudioRecord (Microphone + Android 10+ AudioPlaybackCapture) + MediaCodec AAC-LC encoder (`audio/mp4a-latm`) with monotonic sample-count timestamping

---

## 4. Current Milestone Status
- **Completed Milestones**: 
  - **M0: Environment Audit & Project Foundation** (Approved by ChatGPT on 2026-09-13)
  - **M1: Application Foundation & Shell** (Approved by ChatGPT on 2026-09-13)
  - **M2: Screen Capture Engine** (Approved by ChatGPT on 2026-09-13)
  - **M3: Hardware Video Encoding** (Approved by ChatGPT on 2026-09-13)
- **Current Milestone**: **Milestone 4: AUDIO PIPELINE**
  - Scope: AudioRecord microphone capture, Android 10+ internal device audio investigation/helper (`AudioPlaybackCaptureConfiguration`), MediaCodec AAC-LC encoder (`c2.android.aac.encoder`), mute/unmute with timestamp continuity, sample-accurate monotonic PTS generation, foreground-service integration, standalone & concurrent video+audio execution.
  - Status: Complete & Verified locally — **WAITING_FOR_CHATGPT** review.
- **Pending Milestones**: M5 through M15 (M5 Streaming Pipeline strictly gated on M4 ChatGPT approval).

---

## 5. Bugs & Fixes Encountered
- **M0 Bug**: Host default JDK 25 was incompatible with Gradle 8.10.
  - **Fix**: Pinned `org.gradle.java.home=C:/Users/benny/Desktop/FAIRY LIVE/jdk17` in `gradle.properties`.
- **M1 Bug**: Calling `startForeground` with `FOREGROUND_SERVICE_TYPE_MICROPHONE` on Android 14+ threw `ForegroundServiceStartNotAllowedException` when permissions were ungranted.
  - **Fix**: Added proactive permission validation in `DashboardFragment`, wrapped `startForeground` in try-catch with error fallback.
- **M2 Bug**: `BufferQueue has been abandoned` during initial screen capture on Android 15.
  - **Fix**: Enforced strict even-dimension alignment (`(dim / 2) * 2`), queried full window metrics (`1080x2400`), guarded `updateResolution` against recreating surfaces when dimensions do not change, and upgraded ImageReader buffer pool to triple-buffering (`maxImages = 3`).
- **M3 Resolution Clamping Issue**: Initial test clamped portrait 1080x2400 to 1072x1080 due to a hardcoded 1080 ceiling.
  - **Fix**: Built `AdaptiveResolutionResolver` preserving exact 9:20 aspect ratio (720x1600 for Balanced, 480x1056 for Low), strictly aligned to 16-pixel macroblocks, and verified within encoder max height limits (2048).
- **M4 Timestamping Strategy**: Wall-clock timestamping is vulnerable to clock jumps and thread scheduling jitter.
  - **Fix**: Implemented sample-accurate monotonic timestamping ($t = t_0 + \text{samples} \times 10^6 / \text{sampleRate}$), guaranteeing perfect lip-sync with video and continuous silence generation during mute.

---

## 6. Devices Tested
- **Google Pixel 8 AVD**: Android 15 (API 35), x86_64, 1080x2400 (420dpi), 2.41GB RAM.

---

## 7. Performance Findings
- **Cold Start Duration**: 1,231 ms
- **Active Audio Only Total PSS**: 61.6 MB (negligible memory delta)
- **Concurrent Video + Audio Total PSS**: 65.1 MB (+1.6 MB over video-only encoding)
- **Post-Stop Reclaimed PSS**: 61.6 MB (zero leaks)
- **Audio Codec**: `c2.android.aac.encoder` (AAC-LC, 44,100 Hz, 1-channel, 96 kbps)
- **Audio Bitrate**: Steady 95-96 kbps
- **Combined Pipeline CPU**: ~23% on emulator
- **APK Footprint**: 6.7 MB (well within 15 MB release budget)

---

## 8. Dependencies
- `androidx.core:core-ktx:1.12.0`
- `androidx.appcompat:appcompat:1.6.1`
- `com.google.android.material:material:1.11.0`
- `androidx.constraintlayout:constraintlayout:2.1.4`
- `androidx.lifecycle:lifecycle-runtime-ktx:2.7.0`
- `androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0`
- `androidx.fragment:fragment-ktx:1.6.2`
- `junit:junit:4.13.2`
- `androidx.test.ext:junit:1.1.5`
- `androidx.test.espresso:espresso-core:3.5.1`

---

## 9. Important Paths & Commands
- **Project Root**: `C:\Users\benny\Desktop\FAIRY LIVE`
- **JDK 17 Path**: `C:\Users\benny\Desktop\FAIRY LIVE\jdk17`
- **Android SDK**: `C:\Users\benny\AppData\Local\Android\Sdk`
- **Build Command**: `./gradlew assembleDebug`
- **Unit Test Command**: `./gradlew testDebugUnitTest`
- **Install Command**: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- **Launch Command**: `adb shell am start -S -W -n com.fairylive.app/.MainActivity`
- **Memory Check**: `adb shell dumpsys meminfo com.fairylive.app`

---

## 10. ChatGPT Decisions & Approvals
- **M0**: Approved by ChatGPT on 2026-09-13.
- **M1**: Approved by ChatGPT on 2026-09-13.
- **M2**: Approved by ChatGPT on 2026-09-13.
- **M3**: Approved by ChatGPT on 2026-09-13.
- **M4**: Implemented and verified, submitted for review.
