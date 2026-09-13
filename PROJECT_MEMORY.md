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
- **JVM Target**: Java 17 (`jdk17` Temurin distribution configured in `gradle.properties`)
- **UI Architecture**: ViewBinding, Material Components, Lifecycle KTX

---

## 4. Current Milestone Status
- **Current Milestone**: **Milestone 0: ENVIRONMENT AUDIT + PROJECT FOUNDATION**
- **Status**: Complete & Verified locally — **WAITING_FOR_CHATGPT** review.
- **Completed Milestones**: None approved yet (Milestone 0 review pending).
- **Pending Milestones**: M1 through M15.

---

## 5. Bugs & Fixes Encountered
- **Bug**: Default Android Studio JBR was JDK 25.0.2 which Gradle 8.10 rejected as unsupported.
- **Fix**: Pinned `org.gradle.java.home=C:/Users/benny/Desktop/FAIRY LIVE/jdk17` (Temurin JDK 17.0.12) in `gradle.properties` and exported `JAVA_HOME` pointing to `jdk17`. Build succeeded cleanly.

---

## 6. Devices Tested
- **Google Pixel 8 AVD**: Android 15 (API 35), x86_64, 1080x2400 (420dpi), 2.41GB RAM.

---

## 7. Performance Findings
- Cold start launch time: 2,419 ms
- Total PSS memory footprint: 47.9 MB (Dalvik heap: 2.8 MB, Native heap: 8.9 MB)
- APK size: 5.5 MB (debug)

---

## 8. Dependencies
- `androidx.core:core-ktx:1.12.0`
- `androidx.appcompat:appcompat:1.6.1`
- `com.google.android.material:material:1.11.0`
- `androidx.constraintlayout:constraintlayout:2.1.4`
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
- **Launch Command**: `adb shell am start -n com.fairylive.app/.MainActivity -W`
- **Memory Check**: `adb shell dumpsys meminfo com.fairylive.app`

---

## 10. ChatGPT Decisions & Approvals
- Milestone 0: Ready for submission.
