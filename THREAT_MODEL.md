# FAIRY LIVE — THREAT MODEL SPECIFICATION (STRIDE & ASSET ANALYSIS)

**Document Version**: 1.0.0  
**Milestone**: 14 (Security + Hardening)  
**Classification**: Engineering Security Architecture  
**Target Platform**: Android 7.0 (API 24) to Android 15 (API 35)

---

## 1. System Overview & Trust Boundaries

FAIRY LIVE is a pure-Kotlin native Android live-streaming application operating in high-privilege Android subsystems:
* **MediaProjection (Screen Capture)**: Possesses access to all screen content (notifications, passwords, personal data).
* **Camera2**: Possesses hardware access to front and back camera image sensors.
* **AudioRecord**: Possesses hardware access to device microphone and internal audio playback.
* **MediaCodec (Hardware Encoders)**: Encodes video/audio using bounded hardware and software codecs.
* **Network Transport**: Publishes live FLV bitstreams over RTMP/RTMPS sockets to remote servers.

### Trust Boundaries:
1. **User / Streamer UI Boundary**: Validates all user inputs, resolution choices, bitrate settings, and destination credentials.
2. **IPC Boundary (Intents & Receivers)**: Boundary separating FAIRY LIVE from co-installed third-party Android applications and the Android OS shell (`am broadcast`).
3. **Storage Boundary (SharedPreferences & Filesystem)**: Boundary protecting persistence of stream credentials and configuration on disk.
4. **Network Transport Boundary**: Boundary between the local RTMP client and external media ingest endpoints.
5. **Hardware HAL / Subsystem Boundary**: Boundary interacting with Android OS media services (CameraManager, MediaProjectionManager, AudioRecord, MediaCodec).

---

## 2. Protected Assets & Impact Assessment

| Asset | Description | Confidentiality | Integrity | Availability | Risk Level |
|---|---|:---:|:---:|:---:|:---:|
| **Stream Key / Token** | Secret credential authenticating the publisher to Twitch, YouTube, Kick, etc. | **CRITICAL** | **HIGH** | LOW | **CRITICAL** |
| **Screen Capture Stream** | Live video buffer containing whatever is displayed on device screen. | **HIGH** | **HIGH** | **HIGH** | **HIGH** |
| **Camera Video Stream** | Live video frames from front/back camera sensors. | **HIGH** | **HIGH** | **HIGH** | **HIGH** |
| **Microphone Audio Stream** | Live audio PCM buffers captured from hardware microphone. | **HIGH** | **HIGH** | **HIGH** | **HIGH** |
| **Codec Hardware Resources**| Bounded hardware MediaCodec instances (often limited to 2 on low-end SoCs). | LOW | **HIGH** | **CRITICAL** | **HIGH** |
| **Stream Telemetry & State** | Stream status, bitrate, dropped frame counters, health classification. | LOW | **HIGH** | MEDIUM | MEDIUM |

---

## 3. Threat Analysis using STRIDE Matrix

### S — Spoofing (Identity & Authenticity)
* **Threat S1: Unauthorized Broadcast Control Injection**
  * *Vector*: Malicious co-installed application sends unauthorized broadcast intents to `StreamControlReceiver` (`com.fairylive.app.action.STOP_CAPTURE`, `TOGGLE_FACECAM`, `SWITCH_SCENE`).
  * *Impact*: Hijacking or terminating a live broadcast without streamer consent.
  * *Mitigation*: Secure `StreamControlReceiver` with custom signature-level permission (`com.fairylive.app.permission.STREAM_CONTROL`, `protectionLevel="signature"`) and restrict intent resolution.

### T — Tampering (Data & Integrity)
* **Threat T1: SharedPreferences Manipulation or Backup Extraction**
  * *Vector*: Plaintext `fairy_live_settings.xml` read or restored via `android:allowBackup="true"`.
  * *Impact*: Tampering with stream URLs, redirecting broadcasts to attacker-controlled RTMP proxies, or stealing stream keys.
  * *Mitigation*: Disable unencrypted backups (`android:allowBackup="false"`), separate sensitive stream keys from non-sensitive settings, and cryptographically protect stream keys using Android KeyStore AES-256-GCM.
* **Threat T2: Malformed Intent Extra Injection**
  * *Vector*: Attacker broadcasts crafted intents with invalid types, null pointers, or extreme payload sizes to crash or corrupt overlay rendering.
  * *Impact*: Memory corruption, UI freezes, or app crashes.
  * *Mitigation*: Strict type-checking, range verification, length clamping, and exception guarding on all intent extras.

### R — Repudiation
* **Threat R1: Unlogged Security Failures**
  * *Vector*: Silent failures or untracked permission revocations leave no audit trail.
  * *Mitigation*: Publish sanitized, typed `CreatorError` notifications and error metrics without leaking secrets.

### I — Information Disclosure (Confidentiality)
* **Threat I1: Stream Key Leakage in System Logcat**
  * *Vector*: Unsanitized `Log.d` / `Log.i` printing RTMP connection URLs (`rtmp://live.twitch.tv/app/{STREAM_KEY}`). Logcat is readable on older Android versions or via desktop debugging tools.
  * *Impact*: Permanent compromise of broadcaster's streaming account.
  * *Mitigation*: Strict credential masking (`li•••••••••••••56`) in all logging, UI displays, and error notifications. URL sanitizer utility to strip stream keys from connection logs.
* **Threat I2: Accidental Background Display Capture**
  * *Vector*: MediaProjection continuing to stream screen contents after the streamer thought they stopped broadcasting.
  * *Mitigation*: Explicit, visible Foreground Service notification with persistent "Stop" action; immediate destruction of `VirtualDisplay` and `MediaProjection` handles upon stop command.

### D — Denial of Service (Availability)
* **Threat D1: Codec / Surface Starvation & Resource Exhaustion**
  * *Vector*: Rapid START/STOP cycling creates concurrent MediaCodec or CameraDevice instances, exhausting low-end SoC hardware limits and throwing `CodecException`.
  * *Impact*: Device codec subsystem crash, black screen, or forced device reboot.
  * *Mitigation*: Re-entrant lifecycle locking in `FairyStreamService`, sequential teardown queues, atomic state checking (`isStarting`, `isStopping`), and bounded resource pooling.
* **Threat D2: Malformed Input Buffer Flooding**
  * *Vector*: Streamer or attacker injects multi-megabyte overlay text strings or extreme resolution configurations.
  * *Impact*: OOM crash on 2GB RAM devices during font rasterization or bitmap allocation.
  * *Mitigation*: Strict input validation: text length bounded (e.g. max 256 chars), font size clamped (8–96 sp), image dimensions clamped to maximum render surface size.

### E — Elevation of Privilege
* **Threat E1: Unauthorized Foreground Service Invocation**
  * *Vector*: Third-party app starts `FairyStreamService` directly with forged projection intents.
  * *Impact*: Unauthorized media capture.
  * *Mitigation*: `FairyStreamService` is non-exported (`android:exported="false"`) and requires MediaProjection tokens originating strictly from the system consent dialog.

---

## 4. Security Verification Gates
1. Zero secrets in source code, logs, or reports.
2. Encrypted stream key storage with fallback.
3. Signature-protected or non-exported broadcast receivers.
4. `android:allowBackup="false"` to prevent backup data extraction.
5. Strict URL and input sanitization.
6. Graceful permission denial/revocation handling.
7. Concurrency-hardened service lifecycle.
