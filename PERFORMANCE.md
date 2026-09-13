# FAIRY LIVE — PERFORMANCE AUDIT & TELEMETRY

## 1. Benchmarking Philosophy
Every performance claim must be backed by real measurements captured from target devices or emulators. Profiling data is recorded per milestone to prevent performance regressions.

---

## 2. Milestone 1 Performance Telemetry

### Test Device Profile
- **Device**: Google Pixel 8 AVD (`sdk_gphone64_x86_64`)
- **Android Version**: Android 15 (API level 35)
- **Architecture**: x86_64
- **Screen Resolution**: 1080 x 2400 (420 dpi)
- **Allocated Total RAM**: 2,532,432 kB (~2.41 GB RAM baseline)

---

### Startup & Latency Metrics
- **Launch Mode**: Cold Start (`am start -S -W`)
- **Total Startup Time**: **1,148 ms** (52% improvement vs M0 baseline of 2,419 ms)
- **Wait Time**: **1,150 ms**
- **Status**: OK (`LaunchState: COLD`)

---

### Memory Consumption (`dumpsys meminfo com.fairylive.app`)
| Metric | M0 Baseline (kB) | M1 Current (kB) | M1 Equivalent (MB) | Delta |
|---|---|---|---|---|
| **Total PSS** | 47,972 kB | 61,564 kB | 61.5 MB | +13.5 MB |
| **Private Dirty** | 34,888 kB | 39,576 kB | 39.5 MB | +4.6 MB |
| **Dalvik Heap (Allocated)** | 3,323 kB | 4,030 kB | 4.0 MB | +0.7 MB |
| **Dalvik Heap (Size)** | 6,645 kB | 8,059 kB | 8.0 MB | +1.4 MB |
| **Native Heap (Allocated)** | 17,069 kB | 21,574 kB | 21.5 MB | +4.5 MB |
| **Native Heap (Size)** | 23,564 kB | 27,800 kB | 27.8 MB | +4.2 MB |
| **Graphics PSS** | 0 kB | 0 kB | 0.0 MB | 0 kB |
| **Code PSS** | 5,076 kB | 3,076 kB | 3.0 MB | -2.0 MB |

*Analysis*: Memory growth of ~13.5 MB PSS is exceptionally modest for adding a complete 3-screen navigation architecture, full capability detection, SharedPreferences subsystem, and active foreground service foundation.

---

### Artifact Footprint
- **Debug APK Size**: **5.7 MB** (`app-debug.apk`)
- **Release Budget**: < 15 MB
- **APK Growth vs M0**: +0.2 MB

---

## 3. Milestone 0 Baseline Telemetry
- Cold start time: 2,419 ms
- Total PSS: 47,972 kB
- Dalvik Heap (Allocated): 3,323 kB
- Native Heap (Allocated): 17,069 kB
- APK size: 5.5 MB
