# FAIRY LIVE — ARCHITECTURE SPECIFICATION

## 1. Core Engineering Philosophy: LOW-END PERFORMANCE FIRST
FAIRY LIVE is engineered from the ground up for stability and high frame rates across low-end and constrained Android devices. 
Key architectural principles:
- **Zero In-Stream UI Compositing**: The streamer's control UI and the broadcast stream composition are completely decoupled. Android View hierarchy elements are never rendered into or captured by the stream compositor.
- **Surface-to-Surface Zero-Copy Pipelines**: Video capture and overlay rendering feed directly into MediaCodec input Surfaces via OpenGL ES 2.0 / 3.0 or HardwareBuffers, eliminating CPU memory copies and Bitmap allocations.
- **Bounded Asynchronous Execution**: Audio and network buffers use fixed-size ring buffers / object pools to prevent garbage collection pauses and out-of-memory crashes on low-RAM devices (2GB-3GB RAM classes).
- **Graceful Capability Degradation**: Built-in dynamic telemetry adjusts encoding profile, bitrate, and overlay update frequency when device thermals or memory thresholds are reached.

---

## 2. Package Architecture
```text
com.fairylive.app
 ├── core/
 │    ├── capability/         # Hardware encoder detection, RAM class, thermal & OS level profiling
 │    ├── lifecycle/          # Lifecycle-aware controller bindings & foreground service coordinators
 │    └── utils/              # Memory-safe utilities, bounded pools, formatters
 ├── engine/
 │    ├── capture/            # MediaProjection screen capture & virtual display management
 │    ├── audio/              # AudioRecord capture, permission validation, mixing & audio encoding
 │    ├── video/              # MediaCodec video encoding (H.264/AVC baseline/main profiles)
 │    ├── overlay/            # Surface compositor, OpenGL ES overlay rendering pipeline
 │    ├── scene/              # Scene manager, overlay layer state transitions
 │    └── network/            # RTMP/FLV muxing, adaptive bitrate, socket management & exponential backoff
 ├── service/                 # Foreground streaming service with notification & wake-lock management
 └── ui/                      # Streamer dashboard, permission wizard, scene switcher, live telemetry preview
```

---

## 3. Streaming Engine & Capture Pipeline
```text
Android Display / Game
        ↓
MediaProjection VirtualDisplay
        ↓
Capture Surface (Raw Video Frames)
        ↓
Stream Compositor (OpenGL ES 2.0 / SurfaceTexture)
   ├── Layer 0: Screen / Game Surface
   ├── Layer 1: Camera Surface (if active)
   └── Layer 2..N: Overlays (PNG / Text / Animated Layers)
        ↓
MediaCodec Hardware Video Encoder Surface (H.264 / AVC)
        ↓
MediaCodec Output Buffers (Annex-B / NAL units)
        ↓
Multiplexer / Streaming Transport (RTMP / Protocol Engine)
        ↓
Network Socket (Buffered / Non-blocking with Backpressure)
```

---

## 4. Parallel Audio Pipeline
```text
Microphone (AudioRecord)   [+ Device Audio via AudioPlaybackCapture if permitted]
        ↓
Raw PCM 16-bit 44.1kHz / 48kHz Ring Buffer (Zero-allocation)
        ↓
Audio Mixer & Synchronizer (Timestamp alignment with video PTS)
        ↓
MediaCodec AAC Hardware/Software Encoder
        ↓
Multiplexer / Streaming Transport
```

---

## 5. Overlay Composition System
- **Decoupled Architecture**: Outgoing stream overlays live in an OpenGL texture-based render graph, completely independent of the Android Window/Activity layout.
- **Layer System**: Supports Z-ordering, opacity, positioning, and matrix transformations for:
  - Static images (PNG / WebP)
  - Live text (chat, subscriber alerts, stats)
  - Animated sequences (GIF / sprite sheets with low-footprint frame cache)
  - Face cam / Camera preview texture
- **Scene System**: Scenes group overlay configurations. Switching scenes transitions texture visibility/matrices without destroying or reinitializing the underlying MediaProjection or MediaCodec instances.

---

## 6. Device Capability Engine
Centralized probe executed at startup:
- Android API Level & OS capabilities
- Device RAM class (e.g. low-ram flag via `ActivityManager.isLowRamDevice()`)
- Available hardware video encoders (`MediaCodecList` query for `video/avc`, profile levels, color formats, resolution limits)
- Audio capture capabilities (`AudioRecord` min buffer size, playback capture support)
- Baseline quality selection: Low (480p 24/30fps 1.5Mbps), Medium (720p 30fps 2.5Mbps), High (1080p 30/60fps 4.5Mbps).

---

## 7. Lifecycle & Foreground Service Management
- Streaming runs inside a dedicated `ForegroundService` with `foregroundServiceType="mediaProjection|microphone"`.
- Survives Activity backgrounding, screen orientation switches, and configuration changes.
- Safe teardown: stops VirtualDisplay, stops MediaCodec, flushes muxer, releases surfaces, and stops foreground notification gracefully.
