# FAIRY LIVE — PERFORMANCE AUDIT & TELEMETRY

## 1. Benchmarking Philosophy
Every performance claim must be backed by real measurements captured from target devices or emulators. Profiling data is recorded per milestone to prevent performance regressions.

---

## 2. Milestone 0 Baseline Telemetry

### Test Device Profile
- **Device**: Google Pixel 8 AVD (`sdk_gphone64_x86_64`)
- **Android Version**: Android 15 (API level 35)
- **Architecture**: x86_64
- **Screen Resolution**: 1080 x 2400 (420 dpi)
- **Allocated Total RAM**: 2,532,432 kB (~2.41 GB, ideal mid/low memory baseline)

---

### Startup & Latency Metrics
- **Launch Mode**: Cold Start
- **Total Startup Time (`am start -W`)**: **2,419 ms**
- **Wait Time**: **2,442 ms**
- **Status**: OK (`LaunchState: COLD`)

---

### Memory Consumption (`dumpsys meminfo com.fairylive.app`)
| Metric | Measurement (kB) | Equivalent (MB) |
|---|---|---|
| **Total PSS** | 47,972 kB | 47.9 MB |
| **Private Dirty** | 34,888 kB | 34.8 MB |
| **Dalvik Heap (Allocated)** | 3,323 kB | 3.2 MB |
| **Dalvik Heap (Size)** | 6,645 kB | 6.5 MB |
| **Native Heap (Allocated)** | 17,069 kB | 16.7 MB |
| **Native Heap (Size)** | 23,564 kB | 23.0 MB |
| **Graphics PSS** | 0 kB | 0.0 MB |
| **Code PSS** | 5,076 kB | 5.0 MB |
| **Stack PSS** | 504 kB | 0.5 MB |

---

### Artifact Footprint
- **Debug APK Size**: **5.5 MB** (`app-debug.apk`)
- **Target Release Footprint Budget**: < 15 MB
