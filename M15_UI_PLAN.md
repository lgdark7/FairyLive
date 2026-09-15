# FAIRY LIVE — MILESTONE 15 UI & CREATOR STUDIO ARCHITECTURAL PLAN (M15-A -> M15-C)

## 1. Executive Mission & Context
- **Milestone 14**: APPROVED by ChatGPT on 2026-09-15.
- **Milestone 15**: AUTHORIZED by ChatGPT on 2026-09-15 across three structured sub-gates:
  - **M15-A**: Complete FAIRY LIVE Creator Studio UI (Native Android Views, dark-first, clean, responsive, low-end friendly, unified navigation).
  - **M15-B**: Functional Live Preview + Overlay & Scene Editor (Direct interactive preview, touch hit-testing, drag/scale/resize with normalized coordinates, layer reordering/visibility, properties inspector, scene lifecycle, and the critical broadcast composition verification).
  - **M15-C**: Complete End-to-End Release Test (Full lifecycle from clean install to multi-scene broadcast, carried M13 HAL-gap recovery regression test, resource stability audit, zero leaks, and release build sign-off).
- **Core Product Invariant**: FAIRY LIVE is not simply a streaming backend engine; it must provide a seamless, native, touch-friendly Creator Studio on Android (API 24–35) where what the creator edits in the studio is accurately and faithfully composed onto the outgoing RTMP broadcast without burning studio controls into the stream.

---

## 2. Current Architecture vs Target UI Architecture

### 2.1 Existing Subsystems (Preserved Backend)
- **MediaProjection & Screen Capture**: `ScreenCaptureEngine` handles hardware display virtual displays (API 24–35) with API 34+ foreground service binding and `MediaProjectionConfig.createConfigForDefaultDisplay()`.
- **Hardware Video Encoding**: `HardwareVideoEncoder` drives `MediaCodec` with 720p/1080p AVC/H.264 surface input, bounded strictly to 2 active codecs.
- **AAC Audio Pipeline**: `AudioCaptureEngine` (Microphone/Internal Audio with HAL silence synthesis) + `AacMediaCodecEncoder` with 1024-sample AU packetization.
- **GPU Compositor**: `OverlayCompositor` + `GlesRenderer` rendering multi-layer composition (Screen texture + Camera OES texture + Overlays: Text, Image, GIF, Goal, Lower Third, QR, Widget, Alert) via OpenGL ES 2.0/3.0.
- **Scene Subsystem**: `BroadcastSceneManager`, `BroadcastScene`, `SceneRepository`, and `SceneStateNotifier` maintaining scene graphs and layer ordering.
- **Streaming Pipeline**: Spec-compliant RTMP/AMF0 client with bounded queue, ABR, and automatic reconnect.
- **Security & Storage**: Android Keystore-protected AES-256-GCM credential storage, input sanitization, and signature-protected IPC.

### 2.2 Domain Separation Invariant: Creator UI vs Broadcast Composition
```
┌──────────────────────────────────────────────────────────┐
│                   CREATOR STUDIO UI                      │
│  (Native Android Views, Fragment, Touch Gestures, HUD)   │
└────────────────────────────┬─────────────────────────────┘
                             │ Dispatches modifications
                             ▼
┌──────────────────────────────────────────────────────────┐
│         SHARED SCENE MODEL & REPOSITORY                  │
│   (Normalized coords [0..1], Layer stack, Scene Graph)   │
└──────────────┬────────────────────────────┬──────────────┘
               │ Local WYSIWYG              │ Broadcast
               ▼                            ▼
┌──────────────────────────────┐ ┌─────────────────────────┐
│     STUDIO PREVIEW VIEW      │ │   GPU BROADCAST ENGINE  │
│ (Custom View / TextureView   │ │ (GlesRenderer / FBO     │
│  with touch-handle overlays  │ │  Screen + Cam + Layers  │
│  & bounding boxes)           │ │  Target: Codec Surface) │
└──────────────────────────────┘ └───────────┬─────────────┘
                                             │ Encoded bitstream
                                             ▼
                                 ┌─────────────────────────┐
                                 │   MediaCodec -> RTMP    │
                                 │ (Pure broadcast output: │
                                 │  NO studio controls!)   │
                                 └─────────────────────────┘
```

---

## 3. Detailed Sub-Gate Specifications

### 3.1 M15-A: Creator Studio UI
1. **Primary Navigation Structure**:
   - Bottom navigation bar:
     - **Dashboard**: High-level stream telemetry (Status, Destination, FPS, Bitrate, Network, Audio/Camera indicators, Quick Actions: "Open Creator Studio", "Go Live").
     - **Creator Studio**: Primary workspace with Live Preview, Scene Selector, Layer Inspector, Overlay Add Bar, and Quick Stream Controls.
     - **Settings**: Stream endpoints, Video resolutions/FPS, Audio sources, Camera options, Security, and Advanced tuning.
     - **Diagnostics**: Detailed real-time HUD (FPS, bitrates, audio rates, drop counters, memory PSS, thermal state, codec status).
2. **Creator Studio Screen Layout**:
   - **Header Bar**: Stream status indicator (`READY`, `LIVE`, `RECONNECTING`, `STOPPING`), Scene name, and Live duration.
   - **Visual Center (Live Preview)**: Main preview container maintaining correct broadcast aspect ratio (16:9) with pillarboxing/letterboxing as needed.
   - **Interactive Layer / Scene Bar**: Quick scene switcher pills (`Main`, `Starting`, `Gameplay`, `BRB`, `Ending`), Add Overlay button, and Layer Stack toggle.
   - **Bottom Sheet / Collapsible Control Panels**:
     - *Layer Inspector Panel*: List of active layers in rendering order, visibility eye toggle, reorder handles, edit properties, delete button.
     - *Quick Action Bar*: Camera Toggle / Flip, Mic Mute / Unmute, Go Live / Stop Broadcast.
   - **Performance HUD Overlay** (Toggleable): Stream FPS, Bitrate, Audio AU rate, Network health, and Active Codec count.

### 3.2 M15-B: Live Preview + Overlay & Scene Editor
1. **Touch Gesture & Hit-Testing Model**:
   - All spatial transformations (X, Y, Width, Height) remain normalized `[0.0, 1.0]`.
   - On-screen touch points convert from view pixel coordinates to normalized scene space.
   - Hit testing determines top-most selected layer (respecting z-order).
   - Touch gestures:
     - **Tap**: Select overlay, display selection bounding box with 4 corner resize handles.
     - **Tap Outside**: Deselect active overlay.
     - **Single-finger Drag**: Translate overlay in normalized coordinates.
     - **Pinch-to-scale / Corner Drag**: Scale overlay proportionally with clamp limits (`minSize = 0.05`, `maxSize = 1.0`).
2. **Overlay Catalog & Property Editors**:
   - **Text Overlay**: Text content, font size, text color, background box, opacity.
   - **Image Overlay**: Image selection, scale, opacity, aspect ratio lock.
   - **GIF Overlay**: Animated GIF playback, frame rate throttle, loop, scale.
   - **Facecam / Camera**: Front/Back camera, circular or rectangular mask, PIP scale, flip/mirror.
   - **Stream Goal**: Goal title, target amount, current amount, progress bar color.
   - **Lower Third**: Headline, subtitle, brand color, theme preset.
   - **QR Code**: URL/payload encoding, sizing, contrast background.
   - **Widget**: System diagnostics widget, viewer count badge.
3. **Scene Management**:
   - Persistent scene storage in `SceneRepository`.
   - Scene actions: Create, Duplicate, Rename, Delete, Switch.
   - Zero pipeline restarts when switching scenes — dynamic composition update via `BroadcastSceneManager`.

### 3.3 M15-C: End-to-End Release QA & Verification
1. **Mandatory Critical Broadcast Invariant Test**:
   - Run live RTMP broadcast with active composition: Screen capture + Facecam + Text + Image + GIF + Goal + Lower Third + QR.
   - Analyze recorded FLV bitstream: verify all configured visual elements are rendered on the broadcast frame, while zero Android Studio buttons, bounding boxes, or touch handles appear in the stream.
2. **M13 Carried Failure Regression Test**:
   - Force an intentional audio HAL drop (audio underflow/buffer starvation).
   - Verify: `HAL gap -> silence synthesis injection -> AAC silence AU sequence -> normal audio recovery` with mathematical timeline continuity and 0 timestamp jumps.
3. **Release Hardening & Resource Soak**:
   - Memory leak test (no monotonic growth after rapid scene/overlay switching).
   - Codec bound enforcement ($\le 2$ active codecs).
   - R8 release compilation (`assembleRelease`), ProGuard verification, APK size verification ($< 15$ MB).
   - Full regression test run (`testDebugUnitTest` + `connectedDebugAndroidTest`).

---

## 4. Implementation Steps & Milestones
- **Step 1**: Author `M15_UI_PLAN.md` (Completed).
- **Step 2**: Implement M15-A Creator Studio UI components (`CreatorStudioFragment`, layout XMLs, bottom navigation, studio viewmodel/adapter).
- **Step 3**: Verify M15-A UI builds, runs, passes unit tests, and commit checkpoint `M15-A UI stable`.
- **Step 4**: Implement M15-B Preview & Overlay Editor (interactive preview canvas, gesture recognizer, layer manager, properties dialogs, scene transitions).
- **Step 5**: Execute critical live stream test validating that Creator Studio compositions render accurately to RTMP without UI bleed, and commit checkpoint `M15-B Editor stable`.
- **Step 6**: Implement M15-C Release QA suite (HAL gap recovery test, soak test, regression run, release APK build).
- **Step 7**: Update documentation and compile final M15 Gate Dossier for ChatGPT review.
