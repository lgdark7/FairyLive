# FAIRY LIVE — DSP Voice Quality & Auto-Ducking Architecture: Design & Risks

**Date:** 2026-10-22  
**Author:** Principal Real-Time DSP & Live-Streaming Systems Engineer  
**Target Repository:** `C:\Users\benny\Desktop\FAIRY LIVE`  
**Current Git Worktree:** `priceless-cannon-c49847`  

---

## 1. Executive Summary

A comprehensive forensic audit of the digital signal processing (DSP) chain in `AudioMixerProcessor.kt`, `VoiceActivityDetector.kt`, and `SpectralNoiseSuppressor.kt` was conducted.

The current DSP chain suffers from three critical speech-quality flaws:
1. **Aggressive Low-Frequency Voice Attenuation (`voicePurity`):** Even during active speech with the gate fully open, `voicePurity` scaling artificially attenuates speech body (< 3 kHz) by up to 35% (-3.7 dB) under moderate noise, thinning male vocal fundamentals and causing unnatural hollow timbre.
2. **Unvoiced Consonant Destruction (`airBand` Over-attenuation):** High-frequency unvoiced fricatives (/s/, /sh/, /f/, /th/) and plosives (/p/, /t/, /k/) are frequently clamped by up to 70% (-10.5 dB) because their energy fails to exceed `ambientAirFloor * 1.5f`.
3. **Persistent False Game Auto-Ducking:** The sidechain auto-ducking logic relies on `micEnvelope > 0.005f` (-46 dBFS) combined with binary `vad.isVoiceActive`. Fan turbulence, room resonance, and finger taps easily trip this threshold, while a 20,000-sample hold (~453 ms) and a sluggish release ramp (`0.0003f`, ~250 ms) keep game audio suppressed at 30% volume indefinitely.

This document details the signal chain, identifies failure points, and specifies surgical DSP corrections that preserve 100% natural speech and consonant articulation while providing robust fan-noise immunity.

---

## 2. Complete DSP Signal Processing Map

```
[Raw 16-bit PCM Mic Input]
           │
           ▼
[Float Normalization: rawMic in -1.0 .. 1.0]
           │
           ├──────────────────────────────────────────────┐
           │                                              │
           ▼                                              ▼
[Voice Activity Detector (VAD)]                [Spectral Noise Suppressor (SNS)]
 • Path A: 2-pole 220 Hz HPF (Formant)          • 2nd-order 55 Hz Rumble HPF
 • Path B: 2-pole 80 Hz HPF (AMDF)              • 256-pt STFT (50% Overlap-Add)
 • 4x Decimation & AMDF Autocorrelation         • Wiener Power-Domain Subtraction
 • Drone Discriminator (Fan rejection)          • In-place OLA Synthesis
 • Outputs: isVoiceActive, voiceProbability     • (Default: Bypassed unless enabled)
           │                                              │
           └──────────────────────┬───────────────────────┘
                                  │
                                  ▼
                    [Rumble High-Pass Filter]
                     • 1st-order Butterworth (78 Hz cutoff)
                     • Strips sub-audible table thuds & mechanical vibrations
                                  │
                                  ▼
                    [1-Pole Crossover @ 3.0 kHz]
                     • Vocal Core Body (< 3.0 kHz)
                     • Air & Consonant Band (> 3.0 kHz)
                                  │
                                  ▼
                    [Ambient Noise Floor Estimator]
                     • Tracks stationary room noise strictly during non-speech
                     • Dual tracking: Wideband Floor & Air Band Floor
                                  │
                                  ▼
                    [Dual-Threshold Downward Expander]
                     • Hysteresis: Open @ -49 dBFS, Close @ -55 dBFS
                     • Dynamic threshold adapts with noise floor
                     • Hold: 450 ms (non-VAD) or VAD hangover
                     • Smooth attack (0.025) & release (0.008)
                                  │
                                  ▼
                    [Vocal Presence Articulation]
                     • Gentle +1.5 dB presence lift around 3 kHz
                     • Boosts consonant clarity and intelligibility
                                  │
                                  ▼
                    [Speech-Aware Vocal AGC]
                     • Target: 0.22f RMS (-13 dBFS)
                     • Slow adaptation: active strictly during verified speech
                                  │
                                  ▼
                    [Broadcast Vocal Compressor]
                     • Envelope follower: 4 ms attack, 200 ms release
                     • Threshold: -12 dBFS, Ratio: 2.5:1
                     • Tames shout/laugh peaks without clipping
                                  │
                                  ▼
                    [Sidechain Game Auto-Ducking] ◄────── [Raw Game Audio]
                     • Triggered by voice presence
                     • Ducks game volume to target (30%)
                     • Smooth release back to 100%
                                  │
                                  ▼
                    [4-Channel Master Summing Bus]
                     • Mic + Ducked Game + Media
                                  │
                                  ▼
                    [Master Soft-Knee Brickwall Limiter]
                     • Transparent ceiling: 0.92f (-0.72 dBFS)
                     • Hyperbolic curve prevents digital clipping
                                  │
                                  ▼
                    [Output: 16-bit PCM Little-Endian]
```

---

## 3. Analysis of Specific DSP Stages & Root-Cause Defects

### 3.1 Rumble High-Pass Filter
- **Current Behavior:** 1st-order IIR filter with `HP_ALPHA = 0.989f` at 44.1 kHz, yielding a -3 dB cutoff at ~78 Hz.
- **Evaluation:** Acoustically sound. 78 Hz strips sub-audible DC offsets, phone handling rumble, and 50/60 Hz electrical hum while preserving male fundamental vocal frequencies (lowest typical male speech is 85–100 Hz).
- **Action:** Retain 78 Hz cutoff. Ensure it is not cascaded if SNS is active.

### 3.2 Voice Activity Detection (VAD) & Drone Discriminator
- **Current Behavior:** Decimates audio by 4, runs AMDF autocorrelation across pitch lags 22..126 (~87 Hz to ~500 Hz), evaluates formant energy ratio [250 Hz, 3200 Hz] against rumble [< 180 Hz], and identifies fan drones.
- **Evaluation:** The VAD algorithm is mathematically sound, but its integration with auto-ducking is brittle:
  - When fan noise is high, `isVoiceActive` can occasionally toggle during transient noises (clicks, taps).
  - The mixer previously used `micEnvelope > 0.005f` as the sole secondary condition. At -46 dBFS, fan rumble easily satisfies this condition.
- **Action:** In auto-ducking, require both `vad.isVoiceActive` and a voice confidence check (`vad.voiceProbability > 0.25f` or `isGateOpen`) plus dynamic noise floor margin (`micEnvelope > max(0.008f, ambientNoiseFloor * 2.0f)`).

### 3.3 Voice Purity Attenuation (`voicePurity`)
- **Current Code:**
  ```kotlin
  val overSub = 0.4f + 0.4f * levelNorm
  val voiceMargin = max(0.0f, micEnvelope - (ambientNoiseFloor * overSub))
  val voicePurity = if (micEnvelope > 0.0001f) (voiceMargin / micEnvelope).coerceIn(0.65f, 1.0f) else 1.0f
  cleanBody = speechBody * voicePurity
  ```
- **Flaw:** This logic is a pseudo-spectral subtraction in the time domain. Subtracting noise envelope from wideband envelope and multiplying the time-domain waveform results in severe amplitude distortion, speech thinning, and muffled voice.
- **Solution:** When the downward expander gate is open (`isGateOpen && currentGateGain > 0.5f`), human speech body must pass **unattenuated (`cleanBody = speechBody`)**. Noise reduction should be handled by the downward expander during pauses and by the spectral noise suppressor (STFT Wiener filter) when active.

### 3.4 Air Band (>3 kHz) Over-Attenuation
- **Current Code:**
  ```kotlin
  val hasHighFrequencySpeech = airEnvelope > (ambientAirFloor * 1.5f)
  val targetAirGain = if (hasHighFrequencySpeech) 1.0f else (1.0f - levelNorm * 0.70f).coerceAtLeast(0.30f)
  ```
- **Flaw:** Unvoiced consonants (/s/, /f/, /th/) contain little total energy and often fail to exceed `ambientAirFloor * 1.5f`. When this happens, high frequencies are slashed by up to 70% (-10.5 dB), turning crisp speech into a muddy, lisping mess.
- **Solution:** During active speech (`isGateOpen`), high frequencies must be preserved. If de-hiss is desired, the minimum gain should be clamped to `0.70f` (-3 dB) rather than `0.30f` (-10.5 dB), and speech envelope presence must keep air gain at `1.0f`.

### 3.5 Auto-Ducking Hold & Release Pacing
- **Current Code:**
  - `DUCK_HOLD_SAMPLES = 20000` (~453 ms)
  - Release ramp: `rampSpeed = 0.0003f` (takes ~250 ms to recover)
  - Total ducking duration for a 50 ms cough or false trigger: > 700 ms.
- **Solution:**
  - Reduce hold to `DUCK_HOLD_SAMPLES = 10000` (~226 ms at 44.1 kHz).
  - Increase release ramp rate from `0.0003f` to `0.0020f` (~35 ms recovery).
  - Result: Game audio ducks smoothly when speech begins, stays ducked through conversational syllables, and snaps back naturally within ~260 ms after speech finishes, eliminating the sluggish "pumping" effect.

---

## 4. Proposed DSP Parameter Revisions

| Parameter | Current Value | Proposed Value | Rationale |
|---|:---:|:---:|---|
| `DUCK_HOLD_SAMPLES` | 20,000 (~453 ms) | **10,000 (~226 ms)** | Prevents game audio from being held down excessively after short words. |
| Ducking Release Ramp | `0.0003f` (~250 ms) | **`0.0020f` (~35 ms)** | Brisk, natural game audio recovery without slow, audible pumping. |
| Ducking Trigger Margin | `micEnvelope > 0.005f` | **`micEnvelope > max(0.008f, ambientNoiseFloor * 2.0f)`** | Immune to stationary fan noise floor; triggers only on genuine speech. |
| Vocal Body Scaling | `voicePurity` (down to 0.65) | **`1.0f` (Full Transparency)** | Eliminates hollow/thin vocal distortion; preserves natural voice fundamentals. |
| Air Band Floor Gain | Down to 0.30 (-10.5 dB) | **0.75 (-2.5 dB max cut)** | Preserves unvoiced fricatives and plosives (/s/, /f/, /t/, /k/). |
| Presence Alpha | `0.22f` (+2.2 dB) | **`0.18f` (+1.5 dB)** | Sweet, smooth broadcast presence lift without harsh high-mid sibilance. |

---

## 5. Verification & Rollback Plan

- **Automated Validation:**
  - Re-run `AudioMixerProcessorTest` to verify that DSP presets, mute controls, and VU meters remain 100% compliant.
  - Re-run `ProductionDspAuditTest` to confirm noise attenuation is achieved without speech loss.
- **Rollback:**
  - All adjustments are confined to `AudioMixerProcessor.kt`. If any unexpected coloration occurs, the parameters can be reverted to their baseline constants without touching any other subsystem.
