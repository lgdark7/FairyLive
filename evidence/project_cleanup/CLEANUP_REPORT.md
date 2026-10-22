# FAIRY LIVE — Safe Project Cleanup Audit Report
Recorded: 2026-10-22

## 1. Audit Summary
A comprehensive audit of the FAIRY LIVE project tree was conducted prior to building, testing, and deployment. The objective was to eliminate disposable, obsolete, and transient files without risking project functionality, test suites, or user experimental data.

### Classification Breakdown
* **Required Source & Assets**: Android application code (`app/src/main/`), build scripts (`build.gradle.kts`, `settings.gradle.kts`), Gradle wrapper, manifests, Proguard configuration.
* **Required Tests & Test Vectors**: All unit and instrumented tests (`app/src/test/`, `app/src/androidTest/`), including required input test vectors (`physical_raw_mic.pcm`, `physical_hw_ns.pcm`, `app/evidence/milestone1/revalidation_post_fix/*.pcm`).
* **Deliberately Preserved Architectural & Audit Records**: All root documentation (`*.md`), milestone evidence directories (`evidence/*`), and audio continuity evidence (`evidence/audio_continuity_phase1/`, `evidence/audio_recovery_audit/`).
* **Preserved Automation & Tooling**: Python and PowerShell scripts under `tools/` and evaluation datasets under `tools/cmu_arctic_eval/` and `tools/output_*`. Because these scripts interface with external review and evaluation harnesses, they were conservatively preserved.
* **Disposable Artifacts Removed**: Stale JVM crash dumps, stale turn watcher logs, and Python compilation caches.

---

## 2. Removed Paths & Justifications

| Path | Type | Size | Reason for Removal |
| :--- | :--- | :--- | :--- |
| `hs_err_pid10528.log` | File | 7,345 B | Transient HotSpot JVM crash log from previous build process |
| `hs_err_pid16640.log` | File | 7,520 B | Transient HotSpot JVM crash log from previous build process |
| `tools/hermes_turn_watcher.log` | File | 18,058 B | Stale turn watcher log file from earlier automation session |
| `tools/__pycache__/` | Directory | 66 files | Ephemeral Python bytecode cache (.pyc files) |

---

## 3. Deliberately Preserved Files & Folders

1. **Pre-Existing Modified Tracked Files (19 files)**:
   * `DEVELOPMENT_LOG.md`
   * `app/src/androidTest/java/com/fairylive/app/SceneInstrumentedTest.kt`
   * `app/src/androidTest/java/com/fairylive/app/StreamPipelineInstrumentedTest.kt`
   * `app/src/main/java/com/fairylive/app/core/audio/AudioCaptureEngine.kt` (Original restored implementation)
   * `app/src/main/java/com/fairylive/app/core/audio/AudioPipeline.kt`
   * `app/src/main/java/com/fairylive/app/core/audio/AudioStudioRepository.kt`
   * `app/src/main/java/com/fairylive/app/core/audio/dsp/AudioMixerProcessor.kt` (Original restored implementation)
   * `app/src/main/java/com/fairylive/app/core/audio/dsp/VoiceActivityDetector.kt`
   * `app/src/main/java/com/fairylive/app/core/audio/model/AudioCaptureState.kt` (Original restored implementation)
   * `app/src/main/java/com/fairylive/app/core/stream/StreamPipeline.kt`
   * `app/src/main/java/com/fairylive/app/core/stream/flv/H264FlvPacketizer.kt`
   * `app/src/main/java/com/fairylive/app/core/video/HardwareVideoEncoder.kt`
   * `app/src/service/FairyStreamService.kt`
   * `app/src/main/java/com/fairylive/app/ui/audio/AudioStudioBottomSheetDialog.kt`
   * `app/src/main/java/com/fairylive/app/ui/settings/SettingsFragment.kt`
   * `app/src/test/java/com/fairylive/app/AudioMixerProcessorTest.kt`
   * `app/src/test/java/com/fairylive/app/AudioPipelineTest.kt`
   * `app/src/test/java/com/fairylive/app/StreamPipelineTest.kt`
   * `app/src/test/java/com/fairylive/app/VoiceActivityDetectorTest.kt`
2. **Untracked Core Production Source**:
   * `app/src/main/java/com/fairylive/app/core/audio/dsp/SpectralNoiseSuppressor.kt` (Created Oct 15, active dependency of `AudioMixerProcessor`)
3. **Untracked Unit Tests**:
   * `app/src/test/java/com/fairylive/app/H264FlvPacketizerTest.kt`
   * `app/src/test/java/com/fairylive/app/Milestone1RevalidationTest.kt`
   * `app/src/test/java/com/fairylive/app/ProductionDspAuditTest.kt`
   * `app/src/test/java/com/fairylive/app/SpectralNoiseSuppressorTest.kt`
   * `app/src/test/java/com/fairylive/app/SpectralSuppressorPassThroughTest.kt`
   * `app/src/test/java/com/fairylive/app/VadExperimentAuditTest.kt`
   * `app/src/test/java/com/fairylive/app/VoiceActivityDetectorEvaluationTest.kt`
4. **Test Vector PCM Files**:
   * `physical_raw_mic.pcm` (258,048 B) — required by `ProductionDspAuditTest` and `VadExperimentAuditTest`.
   * `physical_hw_ns.pcm` (258,048 B) — required by `ProductionDspAuditTest` and `VadExperimentAuditTest`.
   * `app/evidence/milestone1/revalidation_post_fix/*.pcm` (35 files) — required by `Milestone1RevalidationTest`.
5. **Release Distribution Artifact**:
   * `app-release.apk` (10,123,135 B) — preserved per project policy keeping a single release APK in the project root. Not staged for Git.
6. **All Empirical Evidence & Audit Trails**:
   * `evidence/audio_continuity_phase1/` — full empirical capture logs, WAV, FLV, and packet metrics from Phase 1.
   * `evidence/audio_recovery_audit/` — pipeline traces and disassembly checks.
   * All earlier milestone directories under `evidence/`.
7. **Tooling & Orchestration Scripts**:
   * All Python/PowerShell scripts under `tools/` and evaluation suites (`cmu_arctic_eval/`, `output_*`). Preserved to avoid breaking external user automation.
