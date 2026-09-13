# FAIRY LIVE — ARCHITECTURE SPECIFICATION

## 1. Core Engineering Philosophy: LOW-END PERFORMANCE FIRST
FAIRY LIVE is engineered from the ground up for stability and high frame rates across low-end and constrained Android devices. 
Key architectural principles:
- **Zero In-Stream UI Compositing**: The streamer's control UI and the broadcast stream composition are completely decoupled. Android View hierarchy elements are never rendered into or captured by the stream compositor.
- **CPU-Copy-Free Surface Pipelines**: Video capture feeds directly into MediaCodec input Surfaces (`COLOR_FormatSurface`) via GPU/SurfaceFlinger, eliminating CPU memory copies and Bitmap allocations.
- **Bounded Asynchronous Audio Pipeline**: Audio capture uses pre-allocated reusable PCM buffers and sample-accurate monotonic timestamps, avoiding garbage collection pauses, buffer underruns, and PTS drift.
- **Graceful Capability Degradation**: Built-in dynamic telemetry adjusts encoding profile, bitrate, and overlay update frequency when device thermals or memory thresholds are reached.

---

## 2. Package Architecture
```text
com.fairylive.app
 ├── core/
 │    ├── audio/              # AudioRecord capture, playback capture helper, AAC encoder & pipeline
 │    ├── capability/         # Hardware/software encoder detection, RAM class, OS level profiling
 │    ├── capture/            # MediaProjection screen capture & VirtualDisplay engine
 │    ├── lifecycle/          # Lifecycle-aware controller bindings & foreground service coordinators
 │    ├── permission/         # Centralized runtime permission wizards & contracts
 │    ├── settings/           # Lightweight SharedPreferences persistence & profile clamping
 │    ├── video/              # MediaCodec video encoding (H.264/AVC baseline/main profiles)
 │    └── utils/              # Memory-safe utilities, bounded pools, formatters
 ├── service/                 # Foreground streaming service with notification & wake-lock management
 └── ui/                      # Streamer dashboard, settings configurator, hardware diagnostics
```

---

## 3. Video Pipeline Architecture (CPU-Copy-Free Surface Pipeline)
```text
Android Display / Game
        ↓
MediaProjection VirtualDisplay
        ↓
MediaCodec Input Surface (COLOR_FormatSurface, Zero CPU Copies)
        ↓
MediaCodec Video Encoder (c2.android.avc.encoder / Hardware AVC Baseline/Main)
        ↓
Output Buffers / Annex-B NAL Units (SPS, PPS, IDR, P-frames)
        ↓
Multiplexer / Streaming Transport (Future M5 RTMP Engine)
```

---

## 4. Audio Pipeline Architecture
```text
Audio Source (Microphone AudioRecord OR Android 10+ AudioPlaybackCapture)
        ↓
Raw 16-bit PCM Ingestion (44.1kHz / 48kHz, Zero-Allocation Reusable Buffers)
        ↓
Monotonic Timestamp Generator (Sample-Count Accurate: PTS = t0 + samples * 10^6 / rate)
        ↓
Mute Processor (Zeroes PCM samples on mute to preserve lip-sync & timestamp continuity)
        ↓
MediaCodec AAC-LC Encoder (audio/mp4a-latm, 96-128 kbps, Profile 2)
        ↓
Encoded Audio Frames (AAC NAL units with csd-0 AudioSpecificConfig header)
        ↓
Multiplexer / Streaming Transport (Future M5 RTMP Engine)
```

### Audio Design Decisions:
1. **Sample-Accurate Monotonic PTS**: Rather than relying on non-monotonic wall clocks (`System.currentTimeMillis()`) or jitter-prone thread scheduling, audio timestamps are derived strictly from accumulated sample counts. This guarantees 100% drift-free audio playback and perfect synchronization with video presentation timestamps over hours of live streaming.
2. **Silence-Preserving Mute**: Muting does not stop buffer dispatch. Instead, the audio engine zeroes out the PCM buffer, sending encoded silent AAC frames. This keeps the muxer and player audio pipelines alive without PTS discontinuities or desynchronization.
3. **Internal Device Audio (Android 10+)**: Supported via `AudioPlaybackCaptureConfiguration` requiring `MediaProjection`. Apps that opt-out or voice call streams are excluded by system security policy, with clean automatic fallback to microphone capture.

---

## 5. Device Capability Engine & Codec Discovery
Centralized probe executed at startup:
- Android API Level & OS capabilities
- Device RAM class (e.g. low-ram flag via `ActivityManager.isLowRamDevice()`)
- Video encoder classification: explicit `HARDWARE`, `SOFTWARE`, or `UNKNOWN` tags via `MediaCodecInfo.isHardwareAccelerated` and `isSoftwareOnly`.
- Prioritizes hardware encoders with `COLOR_FormatSurface` support; provides clean fallback to `c2.android.avc.encoder` software implementation for emulators or unsupported hardware.
- Baseline quality selection: Low (480p 24fps 1.2Mbps, 96kbps audio), Balanced (720p 30fps 2.2Mbps, 128kbps audio), High (1080p 30fps 4.0Mbps, 160kbps audio).

---

## 6. Lifecycle & Foreground Service Management
- Streaming runs inside `FairyStreamService` with `foregroundServiceType="mediaProjection|microphone"`.
- Survives Activity backgrounding, screen orientation switches, and power events.
- Clean resource release: stops VirtualDisplay, stops video encoder, stops AudioRecord, stops audio encoder, releases surfaces, and stops foreground notification gracefully.
