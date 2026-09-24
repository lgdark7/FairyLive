# FAIRY LIVE — OEM Audio Compatibility & Diagnostics Report

## Executive Summary
Following the deployment of DSP hardening and Single-App capture fixes on Google Pixel 6a, tests on secondary OEM devices (specifically **Redmi 13 5G**, running Xiaomi HyperOS / Android 14/15/16) revealed that audio capture is not functioning properly.

This report documents an evidence-based compatibility investigation across the complete Android audio pipeline:
$$\text{Audio HAL} \longrightarrow \text{AudioRecord} \longrightarrow \text{AudioPlaybackCapture} \longrightarrow \text{PCM DSP} \longrightarrow \text{Mixer} \longrightarrow \text{AAC MediaCodec} \longrightarrow \text{RTMP} \longrightarrow \text{YouTube}$$

---

## Phase 1 — Device Inventory

| Parameter | Reference Device (Working) | Failing Test Device (Under Investigation) |
|---|---|---|
| **Device Model** | Google Pixel 6a (`bluejay`) | Xiaomi Redmi 13 5G |
| **Manufacturer** | Google | Xiaomi |
| **Android Version** | Android 17 (Canary / Preview) | Android 14 / 15 / 16 (HyperOS) |
| **API Level** | 37 | API 34 / 35 / 36 |
| **Build Fingerprint** | `google/bluejay/bluejay:17/CP2A.260705.006/15641320:user/release-keys` | *Pending ADB query from connected device* |
| **SoC / Platform** | Google Tensor G1 (`gs101`) | Qualcomm Snapdragon 4 Gen 2 AE / MediaTek Dimensity 6100+ |
| **Audio HAL Config** | `/vendor/etc/audio_policy_configuration.xml` | *Pending dumpsys* |
| **Primary Mic Port** | Built-In Mic (`AUDIO_DEVICE_IN_BUILTIN_MIC`, bottom) | *Pending dumpsys* |
| **Mic Sampling Rates** | 48000 Hz native (16-bit PCM) | *Pending dumpsys* |
| **Channel Masks** | MONO (`0x0010`), STEREO (`0x000c`) | *Pending dumpsys* |
| **Hardware Effects** | AEC (`offload_effect` + `pre_processing`), NS, AGC, LoudnessEnhancer | *Pending dumpsys* |
| **AudioPlaybackCapture** | Supported (API 29+) | Supported (API 29+) |
| **MediaProjection Mode** | Dual Mode (Single App `RECORD_CONTENT_TASK` + Default Display) | Dual Mode |

---

## Phase 2 — Structured Telemetry Protocol

`AudioCaptureEngine` is instrumented to emit structured diagnostics every 5 seconds under tag `[AUDIO-OEM-DIAGNOSTIC]`:

```text
[AUDIO-OEM-DIAGNOSTIC]
DEVICE:
  mfr=Google model=Pixel_6a api=37
MIC:
  init=true state=RECORDING session=145 sampleRate=44100 chan=16
  bufSize=4096 bytesRead=2048 returnCode=2048 rms=-24.2dB peak=18%
GAME:
  playbackInit=true state=RECORDING bytesRead=2048 returnCode=2048
  rms=-18.4dB peak=32%
RING_BUFFER:
  capacity=65536 availableBytes=4096 underruns=0 overruns=0
DSP:
  gateState=OPEN noiseFloor=-58.2dB micGain=1.0 gameGain=1.0 masterGain=1.0
AAC:
  state=CONFIGURED fps=43.1 inputTimeouts=0 outBytes=184920
RTMP:
  packetsGen=1420 packetsSent=1420 lastPtsUs=32941000
```

---

## Phase 3 — Critical Independence Inspection

### Audit Finding
Code inspection of `AudioCaptureEngine.kt` (lines 426–505) revealed a **critical architectural coupling**:
```kotlin
// In AudioCaptureEngine.kt masterCaptureLoop:
val bytesRead = if (micRec != null) {
    micRec.read(micBuffer, 0, micBuffer.size)
} else { ... }

if (bytesRead > 0) {
    if (micRec != null) {
        ringBuffer.read(gameBuffer, 0, bytesRead) // <-- FATAL COUPLING
    }
}
```
### Impact on OEM Devices
1. On OEM devices (such as Xiaomi/Redmi), if `micRec.read()` encounters an error (`ERROR_INVALID_OPERATION`, `ERROR_DEAD_OBJECT`) or delays due to Xiaomi HAL preemption:
   - `bytesRead` is $\le 0$.
   - The loop branches to error sleep (50ms).
   - **Internal game audio from `ringBuffer` is NEVER read or dispatched.**
2. When the user launches Free Fire on Redmi 13 5G, Xiaomi's HAL may temporarily preempt background `AudioRecord`, causing `micRec.read()` to fail or return 0. Because game audio dispatch is chained inside `bytesRead > 0`, **both microphone AND internal game sound die completely.**

### Required Decoupling Architecture
- **Thread 1 (`MicCaptureWorker`)**: Reads `micRecord` independently $\to$ writes to `micRingBuffer`.
- **Thread 2 (`PlaybackCaptureWorker`)**: Reads `playbackRecord` independently $\to$ writes to `playbackRingBuffer`.
- **Thread 3 (`AudioSummingBus`)**: Strict monotonic clock pacing (1024 samples @ 44.1kHz = ~23.2ms). Fetches whatever is available from both buffers, processes DSP, and emits to AAC encoder. Complete mutual isolation: failure in one source never halts the other.

---

## Phase 4 — Negative AudioRecord Return Code Classifier

| Return Code | Code Value | Probable Cause on OEM Device | Handling Action |
|---|---|---|---|
| `ERROR_INVALID_OPERATION` | `-3` | Microphone resource preempted by foreground game (e.g. Free Fire team voice chat) or MIUI background recording permission denied | Reinitialize with fallback source (`VOICE_COMMUNICATION` / `MIC`), maintain game pacing |
| `ERROR_BAD_VALUE` | `-2` | Sample rate or channel config mismatch on OEM HAL | Fall back to HAL native sample rate (48000 Hz) |
| `ERROR_DEAD_OBJECT` | `-6` | `AudioFlinger` / Audio server crashed or restarted | Release and rebuild `AudioRecord` instance |
| `0 bytes returned` | `0` | Non-blocking read returned empty or OEM mic privacy mute enabled | Inject silence frame to prevent AAC starvation |

---

## Phase 5 — Controlled Audio Effects (AEC / NS / AGC) Comparison Matrix

| Effect | Pixel 6a Behavior | Redmi 13 5G / OEM Expected Behavior | Test Status |
|---|---|---|---|
| **AcousticEchoCanceler (AEC)** | Gentle software/offload cancellation | Often treats speaker output during gaming as call echo and zeroes out mic input | Pending Controlled A/B Test |
| **NoiseSuppressor (NS)** | High-pass + multi-band suppression | Aggressive clipping of low-volume streamer speech | Pending Controlled A/B Test |
| **AutomaticGainControl (AGC)** | Smooth gain leveling | Pumping artifacts during loud game explosions | Pending Controlled A/B Test |

---

## Phase 6 — Buffer Sizing Experiments

- Baseline: $1 \times \text{minBufferSize}$
- Candidate A: $2 \times \text{minBufferSize}$
- Candidate B: $4 \times \text{minBufferSize}$
- Verification metric: Count of `pcmUnderruns`, latency delta, and read return code stability.

---

## Phase 7 — Automated In-App OEM Readiness & Battery Exemption Flow (Added 2026-09-23)
- **Problem**: Streamers unfamiliar with OEM security centers (Xiaomi HyperOS, MIUI, Vivo, Oppo) suffer background mic cutoff when FAIRY LIVE is minimized to share a game.
- **Automated Solution Implemented**:
  1. `PermissionManager.isIgnoringBatteryOptimizations(context)` checks battery restriction status prior to launch.
  2. If battery optimization is active on an OEM device, a native Material Dialog prompts the user with:
     - **"Enable (1-Tap)"**: Direct launch of `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, displaying the native system prompt to whitelist FAIRY LIVE.
     - **"OEM Settings"**: Resolves direct intents for manufacturer centers (`miui.intent.action.APP_PERM_EDITOR`, autostart).
     - **"Continue"**: Proceeds without blocking if the user declines.
  3. **Streamer Floating HUD Alert**: If the Android system or a foreground game preempts the microphone (`isMicSilencedBySystem == true`), the in-stream HUD immediately displays:
     > *"⚠️ Microphone Silenced: Game or OS silenced mic! Turn off in-game mic to restore."*

---

## Next Steps
1. Request connection of Redmi 13 5G to ADB (via USB cable or Wi-Fi debugging).
2. Install the updated `FAIRY LIVE.apk` (or `app-release.apk`) on Redmi 13 5G.
3. Run `tools/collect_device_diagnostics.sh` once device is visible in ADB.
4. Execute test stream with Free Fire to verify mic behavior with the automated battery exemption active.
