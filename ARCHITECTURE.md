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
 │    ├── capture/            # MediaProjection screen capture & VirtualDisplay engine
 │    ├── lifecycle/          # Lifecycle-aware controller bindings & foreground service coordinators
 │    ├── permission/         # Centralized runtime permission wizards & contracts
 │    ├── settings/           # Lightweight SharedPreferences persistence & profile clamping
 │    ├── video/              # MediaCodec hardware video encoding (H.264/AVC baseline/main profiles)
 │    └── utils/              # Memory-safe utilities, bounded pools, formatters
 ├── service/                 # Foreground streaming service with notification & wake-lock management
 └── ui/                      # Streamer dashboard, settings configurator, hardware diagnostics
```

---

## 3. Video Pipeline Architecture: Path A vs Path B Tradeoff
During Milestone 3, we evaluated two distinct architectural paths for video ingestion:

### Path A: CPU Buffer Pipeline (`VirtualDisplay -> ImageReader -> CPU Transfer -> MediaCodec`)
- **Mechanism**: Captured frames arrive at an `ImageReader`, are mapped into CPU memory (`Image.Plane`), converted/copied into `ByteBuffer` inputs of `MediaCodec`.
- **Drawbacks on Low-End Devices**:
  - High CPU memory bandwidth consumption (e.g., copying a 1080x2400 RGBA frame takes ~10MB per frame; at 30fps this exceeds 300MB/s of CPU memory bus traffic).
  - Triggers frequent garbage collection and thermal throttling on budget SoCs.
  - Adds 20-50ms of CPU-GPU synchronization latency.

### Path B: Direct Zero-Copy Hardware Pipeline (`VirtualDisplay -> MediaCodec.createInputSurface()`)
- **Mechanism**: `MediaCodec` allocates an input Surface backed by native hardware buffers (`COLOR_FormatSurface`). The `VirtualDisplay` renders directly into this Surface via SurfaceFlinger/hardware composer.
- **Advantages on Low-End Devices**:
  - **Zero CPU Byte Copies**: Pixel data never crosses into JVM or native CPU user space.
  - **Minimal Memory Overhead**: Total memory increase during continuous encoding is only **+1.7 MB PSS**.
  - **Low Latency & High Frame Rates**: Encoder latency remains < 12ms with steady frame output.
  - **Natural Compatibility with OpenGL ES Compositor**: In Milestone 7, the OpenGL compositor will simply render its output framebuffer directly into this same `MediaCodec` input Surface.
- **Decision**: **Path B is adopted as the definitive foundation for FAIRY LIVE.**

---

## 4. Streaming Engine & Capture Pipeline
```text
Android Display / Game
        ↓
MediaProjection VirtualDisplay
        ↓
MediaCodec Input Surface (COLOR_FormatSurface, Zero-Copy)
        ↓
MediaCodec Hardware Video Encoder (c2 / OMX H.264 AVC Baseline/Main)
        ↓
Output Buffers / Annex-B NAL Units (SPS, PPS, IDR, P-frames)
        ↓
Multiplexer / Streaming Transport (Future M5 RTMP Engine)
        ↓
Network Socket (Buffered / Non-blocking with Backpressure)
```

---

## 5. Parallel Audio Pipeline (Targeted for Milestone 4)
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

## 6. Device Capability Engine & Codec Discovery
Centralized probe executed at startup:
- Android API Level & OS capabilities
- Device RAM class (e.g. low-ram flag via `ActivityManager.isLowRamDevice()`)
- Available hardware video encoders (`MediaCodecList` query for `video/avc`, profile levels, color formats, resolution limits)
- Prioritizes hardware encoders with `COLOR_FormatSurface` support; provides clean fallback to `c2.android.avc.encoder` software implementation for emulators or unsupported hardware.
- Baseline quality selection: Low (480p 24fps 1.2Mbps), Balanced (720p 30fps 2.2Mbps), High (1080p 30fps 4.0Mbps).

---

## 7. Lifecycle & Foreground Service Management
- Streaming runs inside `FairyStreamService` with `foregroundServiceType="mediaProjection|microphone"`.
- Survives Activity backgrounding, screen orientation switches, and power events.
- Clean resource release: stops VirtualDisplay, stops MediaCodec, releases surfaces, and stops foreground notification gracefully.
