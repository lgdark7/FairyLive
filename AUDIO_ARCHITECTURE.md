# FAIRY LIVE — Audio Pipeline Architecture & DSP Hardening Spec

## 1. Overview
FAIRY LIVE utilizes a pure-Kotlin, zero-external-SDK native audio architecture optimized for Android 10+ (API 29–35). It delivers broadcast-quality live audio with strict separation between the streamer's voice processing and internal game/device audio playback capture.

```
                 ┌── GAME AUDIO ───────────────┐
                 │  AudioPlaybackCapture       │
Free Fire ───────┤  (USAGE_GAME/MEDIA/UNKNOWN) ├──► PcmRingBuffer ──► Game Fader ─┐
                 │  Downmix stereo->mono       │                                  │
                 └─────────────────────────────┘                                  │
                                                                                  │
Mic Input ──► VOICE_RECOGNITION ──► High-Pass Rumble Filter (80-140Hz)            ▼
                                  │                                      FAIRY AUDIO MIXER
                                  ▼                                               │
                            Multi-band Noise Suppressor                           │
                            (Spectral Subtraction + De-Hiss)                      ▼
                                  │                                        MASTER LIMITER
                                  ▼                                      (Soft-Knee Brickwall)
                            Dynamic Hysteresis Noise Gate                         │
                            (Speech-Immune Noise Floor)                           ▼
                                  │                                       AAC ENCODER
                                  ▼                                    (MediaCodec 44.1kHz)
                            Vocal AGC + Dynamic Compressor                        │
                                  │                                               ▼
                              Mic Fader ──────────────────────────────────────────┘
```

---

## 2. Dedicated Signal Paths

### A. Microphone Studio DSP Chain
1. **Source Selection & Priority Fallback**:
   - `MediaRecorder.AudioSource.VOICE_RECOGNITION` (highest Android CDD 5.4.5 concurrent capture priority, flat frequency response, exempt from background muting).
   - Graceful fallback: `VOICE_RECOGNITION` ➔ `MIC` ➔ `VOICE_COMMUNICATION`.
2. **1st-Order Butterworth Rumble High-Pass Filter**:
   - Cutoff dynamically tuned from 75 Hz (quiet room) to 140 Hz (heavy fan/AC motor vibration) to strip mechanical sub-bass rumble without vocal thinness.
3. **Multi-Band Noise Suppression (God-Mode Ultra)**:
   - Crossover at 3.0 kHz:
     - **Vocal Core (< 3 kHz)**: Stationary fan noise subtraction with speech energy preservation.
     - **Air Band (> 3 kHz)**: High-frequency de-hiss.
4. **Dynamic Downward Expander (Noise Gate)**:
   - **Speech-Immune Floor Tracking**: Floor adaptation strictly gated to low-energy frames (`micEnvelope < 0.015f`, ~490 PCM) with slow time constant (`0.00005f`) and hard cap at `0.008f` (~262 PCM). Speech can NEVER inflate the noise floor.
   - **Thresholds**: Baseline open threshold `0.006f + levelNorm * 0.012f`, close threshold `0.003f + levelNorm * 0.008f`.
   - **Timing**: 5 ms attack, 350 ms hold counter to eliminate word truncation and breath clipping.
5. **Vocal AGC & Speech Compressor**:
   - Smooth 4 ms attack, 150 ms release envelope follower.
   - Tames sudden yelling/shouting while gently boosting soft speech and whispers.

### B. Game Audio Playback Capture Path
1. **AudioPlaybackCaptureConfiguration**:
   - Captures `AudioAttributes.USAGE_GAME`, `AudioAttributes.USAGE_MEDIA`, and `AudioAttributes.USAGE_UNKNOWN`.
2. **Zero-Allocation Downmixing**:
   - If device outputs stereo (L, R), downmixed directly into 16-bit mono via `(L + R) >> 1`.
3. **Decoupled PcmRingBuffer**:
   - High-performance circular buffer using block `System.arraycopy`.
   - Isolates the internal game audio capture thread (`FairyGameAudioThread`) from the master mixing thread (`FairyAudioMixerThread`).
4. **DSP Isolation Guarantee**:
   - **Game audio bypasses the mic noise gate and noise suppressor completely.** Game audio passes straight to the summing bus with 100% dynamic range and zero coloration.

### C. Master Summing & Brickwall Limiter
1. **4-Channel Summing Bus**:
   - Combines `processedMic + rawGame + rawMedia`, scaled by `masterVolume`.
2. **Continuous Soft-Knee Brickwall Limiter**:
   - Active at `LIMITER_CEILING = 0.90f` (-0.9 dBFS).
   - Hyperbolic curve compression prevents digital clipping even when shouting and game explosions coincide.

---

## 3. Resilience & Failure Recovery

1. **Silence Pacing in Game-Only Mode**:
   - When the microphone is absent or disabled, if game audio underflows or is silent, the master thread injects precisely timed silence frames (`Thread.sleep` matched to buffer duration).
   - Guarantees continuous 44.1 kHz PCM flow, preventing AAC encoder starvation and RTMP audio drops.
2. **AudioPlaybackCapture Error Recovery**:
   - On negative read return codes (`ERROR_INVALID_OPERATION`), the thread performs a non-destructive restart check rather than terminating.
3. **Zero-Restart Live Control**:
   - Mic volume, game volume, master volume, and DSP filters adjust dynamically on active streams without restarting RTMP, MediaCodec, or MediaProjection.

---

## 4. Telemetry & Diagnostics

Developer diagnostics exposed via `AudioStateNotifier.getDiagnosticSummary()`:
- **Mic/Game/Master RMS & Peak**: Real-time dBFS and peak percentage.
- **Estimated Noise Floor**: Adaptive floor tracking telemetry.
- **Ring Buffer Depth**: Monitored in bytes to catch drift or underruns.
- **AAC Encoder Performance**: Encoded frame count, real-time FPS, bitrate, and drops (`inputTimeoutCount`).
- **RTMP Transport**: Audio packets sent count from `StreamTelemetryNotifier`.
- **System Concurrency State**: `isMicSilencedBySystem` flag monitoring Android AudioPolicy changes.
