# FAIRY LIVE V1.1 — CREATOR CONTROL & LIVE WIDGETS
## Architectural Specification & Engineering Audit

### 1. Executive Summary & Design Invariants
FAIRY LIVE V1.1 evolves the application from an accepted streaming core (Milestone 15-J) to an advanced mobile creator studio with:
- **Render-Domain Isolation**: Explicit boundary enforcement between broadcast-composited content and streamer-only UI.
- **Browser/Web Overlays**: Live HTML/web widget rendering directly into the broadcast GLES compositor and RTMP stream.
- **YouTube Live Integration**: Real subscriber counter widgets composited into the broadcast and creator-only YouTube Live Chat.
- **Streamer Floating HUD**: Minimal, creator-only control dock with microphone mute/unmute, stream pause/freeze controls, and quick action sheet.
- **Protected Core Architecture**: Zero regression of `BroadcastSceneManager` single source of truth, <=2 codec invariant, Keystore AES-GCM encryption, and 216/216 test suite.

---

### 2. Render-Domain Architecture
```text
┌─────────────────────────────────────────────────────────────┐
│                 DOMAIN A: CAPTURE (Android)                 │
│         MediaProjection Display / Selected Game Task        │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│             DOMAIN B: BROADCAST COMPOSITOR (GLES)           │
│   • Base Game Capture Stream                                │
│   • Text, Image, GIF Overlays                               │
│   • Web / Browser Overlays (Offscreen Render -> Texture)    │
│   • YouTube Subscriber Widget                               │
│   • Facecam PIP (Camera2 / SurfaceTexture)                  │
│   • Stream Goals, Lower Thirds, Alerts                      │
│                                                             │
│   [RULE: RenderDomain.BROADCAST ONLY — No Creator UI]       │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│             VIDEO ENCODER & RTMP TRANSMISSION               │
│        MediaCodec AVC -> RTMP Multiplexer -> YouTube        │
└─────────────────────────────────────────────────────────────┘

===============================================================

┌─────────────────────────────────────────────────────────────┐
│               DOMAIN C: CREATOR-ONLY UI                     │
│   • Floating Streamer HUD (💬 Chat, 🎙 Mic, ⏸ Pause, ⋯ More)│
│   • YouTube Live Chat Panel                                 │
│   • Technical Telemetry & Diagnostics                       │
│   • Scene Editors & Settings                                │
│                                                             │
│   [RULE: RenderDomain.CREATOR_ONLY — NEVER SENT TO GLES]    │
└─────────────────────────────────────────────────────────────┘
```

---

### 3. Web / Browser Overlay Implementation
- **Model**: `WebOverlay` extending base `BroadcastOverlay` (`type = OverlayType.WEB`, URL, dimensions, opacity, refreshInterval, transparentBg).
- **Offscreen Web Renderer**:
  - Headless Android `WebView` configured with hardware acceleration enabled and transparent background support (`Color.TRANSPARENT`).
  - Offscreen rendering rendered to an internal `Bitmap` or `Surface` via `WebView.draw(Canvas)` on update ticks or layout invalidations.
  - Bitmaps upload to the GLES overlay compositor texture pool.
  - Fallback: Graceful placeholder ("Web overlay unavailable") upon network timeout or load errors with zero compositor stalls.

---

### 4. YouTube Integration & Creator Chat
- **Secure Credentials**: Uses `KeystoreHelper` and `EncryptedPreferences` for OAuth2 tokens; zero plaintext storage in git, SharedPreferences, or logs.
- **Subscriber Widget**: Broadcast-composited widget pulling live or cached subscriber count, formatted cleanly with warm editorial styling.
- **Creator-Only Live Chat**:
  - Live chat recycler panel bound exclusively to the Activity/Window overlay.
  - Not placed in `BroadcastSceneManager` or `InteractiveCanvasView`.
  - Supports incoming messages, SuperChat badges, and creator reply composer.

---

### 5. Floating Creator HUD & Mic/Stream Controls
- **Floating HUD**: Compact draggable pill window rendered in the creator's view hierarchy.
- **Hardware Mic Control**: Directly controls `AudioRecordPipeline.setMuted(boolean)` to guarantee zero microphone audio enters AAC packets when muted.
- **Truthful Pause Semantics**: Pauses video capture by repeating the last composited frame with a "Stream Paused / Standby" banner, maintaining RTMP socket continuity and A/V timestamp monotonicity without disconnecting the ingestion endpoint.
