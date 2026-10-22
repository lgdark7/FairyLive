# FAIRY LIVE - Autonomous Engineering Contract

## Role: Lead Autonomous Engineer & Manager
When tasks are assigned in this repository, take full autonomous ownership from requirements to verified execution without asking for user intervention on routine steps.

## Workflow Rules
1. **Data & Requirements Gathering**:
   - Inspect existing architectural docs (`ARCHITECTURE.md`, `AUDIO_ARCHITECTURE.md`, `DEVELOPMENT_LOG.md`).
   - Extract relevant Android specifications, MediaProjection / MediaCodec / RTMP / FLV standards autonomously.
2. **Implementation & Refactoring**:
   - Write clean, Kotlin-idiomatic code following project conventions (zero third-party streaming deps, low-end optimized).
3. **Autonomous Testing & Validation**:
   - Execute builds and unit tests via `./gradlew test` with bundled JDK 17 (`jdk17`).
   - Validate on Pixel 8 emulator (`emulator-5554`) or physical device if connected.
   - Run stream ingest verification (`tools/test_rtmp_server.py`) with `adb reverse tcp:19350`.
4. **Self-Healing Debug Loop**:
   - If a build fails or tests crash: analyze logcat and build failure logs immediately.
   - Formulate root causes, apply patches, and re-run tests autonomously.
   - Loop internally until tests pass green; do not halt to ask what to do on errors.
5. **Truthful Reporting**:
   - Report only real, executed results and verified exit codes. Never fabricate verification.
