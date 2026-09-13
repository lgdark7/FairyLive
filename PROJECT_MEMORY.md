# FAIRY LIVE — PROJECT MEMORY

## 1. Project Objective
Rebuild **FAIRY LIVE** from scratch as a high-performance, professional native Android live-streaming application, with an absolute design focus on **low-end Android devices** (low RAM, constrained CPU/GPU, hardware video encoding, unstable networks).

---

## 2. Complete Requirements Summary
- **Native Android Only**: 100% Kotlin, Android Studio, Gradle, native Android APIs. Zero cross-platform frameworks (No Flutter, React Native, etc.).
- **Decoupled Stream Pipeline**: Outgoing video composited independently from streamer device control UI.
- **Surface-to-Surface Encoding**: Zero-copy pipeline into MediaCodec H.264/AVC hardware encoder.
- **Low-Latency Audio**: AudioRecord PCM capture, mixer/sync, AAC encoding.
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
- **Capture Engine**: MediaProjection + VirtualDisplay + Zero-Copy Surface / ImageReader (triple-buffered) pipeline

---

## 4. Current Milestone Status
- **Completed Milestones**: 
  - **M0: Environment Audit & Project Foundation** (Approved by ChatGPT on 2026-09-13)
  - **M1: Application Foundation & Shell** (Approved by ChatGPT on 2026-09-13)
- **Current Milestone**: **Milestone 2: SCREEN CAPTURE**
  - Scope: Native MediaProjection permission/authorization flow, session management, capture Surface, foreground-service integration (`FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`), start/stop/pause/resume, orientation handling, resource cleanup, zero-copy architecture.
  - Status: Complete & Verified locally — **WAITING_FOR_CHATGPT** review.
- **Pending Milestones**: M3 through M15 (M3 Hardware Video Encoding strictly gated on M2 ChatGPT approval).

---

## 5. Bugs & Fixes Encountered
- **M0 Bug**: Host default JDK 25 was incompatible with Gradle 8.10.
  - **Fix**: Pinned `org.gradle.java.home=C:/Users/benny/Desktop/FAIRY LIVE/jdk17` in `gradle.properties`.
- **M1 Bug**: Calling `startForeground` with `FOREGROUND_SERVICE_TYPE_MICROPHONE` on Android 14+ threw `ForegroundServiceStartNotAllowedException` when permissions were ungranted.
  - **Fix**: Added proactive permission validation in `DashboardFragment`, wrapped `startForeground` in try-catch with error fallback.
- **M2 Bug**: `BufferQueue has been abandoned` during initial screen capture on Android 15.
  - **Root Cause 1**: Odd height dimension (`2205`) passed from `resources.displayMetrics` caused SurfaceFlinger buffer rejection.
  - **Root Cause 2**: Redundant ImageReader recreation in `updateResolution` during dialog dismiss closed the active Surface.
  - **Fix**: Enforced strict even-dimension alignment (`(dim / 2) * 2`), queried full window metrics (`1080x2400`), guarded `updateResolution` against recreating surfaces when dimensions do not change, and upgraded ImageReader buffer pool to triple-buffering (`maxImages = 3`).

---

## 6. Devices Tested
- **Google Pixel 8 AVD**: Android 15 (API 35), x86_64, 1080x2400 (420dpi), 2.41GB RAM.

---

## 7. Performance Findings
- **Cold Start Duration**: 1,142 ms
- **Active Capture Total PSS**: 61.8 MB (virtually zero overhead over M1's 61.5 MB baseline!)
- **Dalvik Heap (Allocated)**: 4.8 MB
- **Native Heap (Allocated)**: 24.3 MB (freed back to 21.1 MB on capture stop)
- **CPU Utilization (Capture)**: ~15% on emulator
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
- **M2**: Implemented and verified, submitted for review.
