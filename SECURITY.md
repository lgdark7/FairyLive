# FAIRY LIVE — SECURITY POLICY & AUDIT COMPLIANCE

**Document Version**: 1.0.0  
**Milestone**: 14 (Security + Hardening)  
**Target Platform**: Android 7.0 (API 24) to Android 15 (API 35)

---

## 1. Security Core Principles

FAIRY LIVE is engineered with defense-in-depth security principles:
1. **Zero Secret Exposure**: Stream keys and authentication credentials must never be written to persistent plaintext storage, system logs (Logcat), bug reports, or unmasked UI elements.
2. **Minimal Attack Surface**: All components are non-exported by default. Any component exposed for system/external IPC must be protected by explicit signature permissions or rigorous input sanitization.
3. **Fail-Safe Defaults**: Permission denials, hardware failures, or network disconnections must fail cleanly into an inert `IDLE` or `FAILED` state without leaking resources or reporting false `● LIVE` status.
4. **Input Sanitization**: All external inputs (RTMP URLs, overlay texts, image dimensions, intent extras) are strictly validated, range-clamped, and sanitised before consumption by GLES or MediaCodec.

---

## 2. Secure Credential Storage Architecture

### Architecture:
* **Non-Sensitive Settings**: Stored in standard `SharedPreferences` (`fairy_live_settings`):
  * Video resolution (width, height)
  * Target framerate (fps)
  * Video bitrate (kbps)
  * Audio bitrate (kbps)
  * Microphone enabled (boolean)
  * Server URL (host endpoint, e.g. `rtmp://live.twitch.tv/app`)
* **Sensitive Credentials**:
  * Stream keys (`stream_key`) are cryptographically secured using Android Keystore-protected AES-256-GCM; hardware-backed key storage is used when supported by the device, via `SecureCredentialStorage`.
  * On devices where hardware KeyStore is unavailable (such as during certain emulation or JVM unit tests), an authenticated fallback encryption layer protects the key.
  * Backup Exclusion: `android:allowBackup="false"` in `AndroidManifest.xml` prevents credentials from being extracted via `adb backup` or Android Cloud Backup.

---

## 3. Logging & Telemetry Redaction Rules

1. **Stream Key Redaction**:
   - Stream keys must be masked using standard partial masking (`li•••••••••••••56` or `[REDACTED]`).
   - Logging RTMP URLs must invoke `UrlSanitizer.sanitize(url)` which strips any path or query parameters containing the stream key:
     ```
     Input:  rtmp://live.twitch.tv/app/live_user_xyz123
     Output: rtmp://live.twitch.tv/app/[REDACTED_STREAM_KEY]
     ```
2. **Exception Sanitization**:
   - `SocketException`, `IOException`, and `CodecException` messages must never interpolate raw connection URLs containing stream keys.

---

## 4. IPC & Component Security

1. **Broadcast Receivers**:
   - `StreamControlReceiver` is protected by `android:permission="com.fairylive.app.permission.STREAM_CONTROL"` with `android:protectionLevel="signature"`.
   - Only apps signed with the same release certificate (or the system shell with signature privileges) can invoke stream controls via broadcast.
2. **Intent Extra Validation**:
   - String inputs (`MUTATE_OVERLAY`, `SWITCH_SCENE`) are length-bounded (max 256 characters) and checked for null/malformed values.
   - Integer inputs (`status`, `index`) are range-bounded.

---

## 5. Input Validation Rules

| Input Domain | Constraint / Validation Rule | Violation Action |
|---|---|---|
| **RTMP URL** | Must begin with `rtmp://` or `rtmps://`. Hostname must be valid IPv4/IPv6 or FQDN. Port must be in range 1–65535. Max length 2048 chars. | Rejected with typed `CreatorError(INVALID_DESTINATION)` |
| **Stream Key** | Printable ASCII only. Length: 1 to 512 chars. Must not contain whitespace or control chars. | Rejected with typed `CreatorError(INVALID_STREAM_KEY)` |
| **Overlay Text** | Max length: 256 characters. Control characters stripped. | Clamped / Truncated safely |
| **Overlay Image** | Max dimensions: 4096 x 4096 pixels. Safe decode bounds check before allocation. | Rejected if exceeds memory ceiling |
| **Bitrate** | Range: 100 kbps to 10,000 kbps. | Clamped to nearest valid tier |
| **Framerate** | Range: 15 fps to 60 fps. | Clamped to supported camera/compositor rates |

---

## 6. Vulnerability Disclosure & Audit History

* **M14 Audit (2026-09-14)**:
  * Secret scan completed across all files. Zero hardcoded credentials.
  * Encrypted storage established for stream credentials.
  * Manifest hardened with `android:allowBackup="false"` and signature-permission on `StreamControlReceiver`.
  * Service lifecycle hardened against rapid re-entrant START/STOP races.
