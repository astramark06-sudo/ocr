# BatteryScope — device-wide telemetry architecture and capability audit

Audit date: 2026-11-23  
Target: Realme Narzo 20 Pro (RMX2161), Android 12, non-rooted  
Package identity: `com.batteryscope`  
Baseline source: branch `batteryscope`, commit `a5a6dea8453b0f0943520ff785b195dca17ad1a6`, version 1.14 / versionCode 15

## 1. Product objective

Evolve BatteryScope from a battery-focused recorder into a permanent, local-first device telemetry and history system. It should automatically collect every measurement and Android-maintained statistic that an ordinary application can legitimately access, preserve raw observations and provenance, calculate derived metrics transparently, and explain every unavailable data source.

The application must not require a Start or Stop action. After first-run initialization it should start its recorder automatically, resume after boot when Android permits, and maintain an ongoing foreground-service notification. It must not pretend that Android guarantees uninterrupted 24/7 execution. Force-stop, shutdown, OEM task management, permission revocation, Doze and thermal/power policy can interrupt collection. Every gap must be recorded or diagnosed when the process next runs.

Hard constraints:
- No root, Shizuku, ADB setup, privileged helper, companion app, or hidden API dependency.
- No arbitrary shell execution and no sysfs, PMIC, I2C, USB-protocol or charging-control writes.
- No fabricated readings or silent zero substitution.
- Preserve `com.batteryscope`, existing app data and sequential SQLite migrations.
- Heavy reads, scans, aggregation, imports and exports must execute off the main thread.
- Distinguish direct measurement, Android-maintained statistic, raw hardware attribute, estimate and derived calculation.
- Network connectivity must never be required for local recording or export.

## 2. Capability classification

Status vocabulary used by the implementation:
- **Public API** — documented Android SDK API; still subject to runtime permission or AppOps.
- **User-granted special access** — user must enable a Settings access such as Usage Access.
- **Best-effort interface** — ordinary file/API visibility varies by kernel, OEM build and SELinux.
- **Privileged** — signature/privileged/development permission or system UID access; unavailable to an ordinary Play-installed app.
- **Not exposed** — no supported ordinary-app API provides the requested underlying measurement.
- **Device verification pending** — cannot be confirmed without collecting evidence on this RMX2161 firmware.

### 2.1 Battery, charging and power path

| Data | Source / classification | Collection plan and boundary |
|---|---|---|
| SOC, charge state, plug type, voltage, temperature, health enum, technology, presence | BatteryManager sticky broadcast / Public API | Event-driven state transitions plus periodic snapshots; preserve missing extras as unavailable |
| Instantaneous/average battery current, charge counter, energy counter | BatteryManager properties / Public API with device-dependent fuel-gauge support | Preserve raw units, sentinel and exception status; verify whether RMX2161 exports each property |
| Maximum charging current/voltage extras | Battery broadcast / Public API | Advertised maximum only; never label as live USB input current/voltage |
| USB input V/A/W | Kernel power_supply/USB attributes / Best-effort interface | Only report direct values when same physical supply identity and semantics are validated; never substitute battery-side current |
| SuperDart/VOOC status | OEM kernel attributes / Best-effort interface | Preserve raw path/value; decoded labels require verified mapping; not protocol capture |
| Charge_full, charge_full_design, cycle_count | Fuel-gauge sysfs or optional framework metadata / Best-effort | Report gauge values separately from empirical capacity estimate |
| BatteryStats per-UID energy, system discharge history | BatteryStats service / Privileged | Do not assume ordinary app access. BATTERY_STATS is signature/privileged/development; do not try hidden API or shell workarounds |
| Charge/discharge rates, session durations, time-to-target | Derived from timestamped samples and valid counter deltas | Store formula inputs and quality flags; rate is n/a when counter direction/continuity is invalid |

### 2.2 Application identity, use and background activity

| Data | Source / classification | Collection plan and boundary |
|---|---|---|
| Foreground app/package transitions and duration | UsageStatsManager / User-granted Usage Access | Import UsageEvents frequently because detailed events are retained only for a few days; deduplicate by event timestamp/type/package |
| Daily/weekly/monthly/yearly usage totals | UsageStatsManager queryUsageStats/queryEventStats | Import Android aggregates into local history; preserve interval and source |
| App standby bucket / last-use metadata | UsageStatsManager / Usage Access | Snapshot periodically where available |
| Installed app inventory | PackageManager / Public API with Android package-visibility filtering | Enumerate launchable apps and visible packages; request broader visibility only if justified and policy-compliant. Record that the inventory may be incomplete |
| Per-app network bytes | NetworkStatsManager / User-granted Usage Access and device/API limitations | Query UID buckets on worker thread; keep Wi-Fi/mobile separate; do not call bytes battery consumption |
| Per-app storage/cache/data sizes | StorageStatsManager / Usage Access for other packages | Query off main thread; record permission and I/O errors |
| Per-app battery mAh / electrical power | BatteryStats privileged service | Not available to a normal third-party app on stock Android. Foreground-time/network correlation is not a substitute |
| Background service/job/alarm execution for every other app | No complete public third-party inventory | Record only events/statistics exposed by public APIs; do not claim complete background process visibility |
| App process memory/CPU for every other UID | Restricted/variable | Own-process diagnostics are available; system-wide per-process access is not guaranteed |

Usage Access is a special user-controlled AppOp, not a normal runtime permission. The app must provide a direct route to Settings and verify actual AppOps state before importing.

### 2.3 Device performance and resources

| Data | Source / classification | Collection plan and boundary |
|---|---|---|
| Total RAM, available RAM, low-memory state, thresholds | ActivityManager.MemoryInfo / Public API | Sample at a low cadence; label system-wide |
| Battery/system thermal status and thermal headroom | PowerManager / Public API on supported API levels | Register thermal status callback and snapshot sparingly; headroom is an estimate, not a sensor temperature |
| Thermal zone temperatures | `/sys/class/thermal`, hwmon / Best-effort interface | Record each readable sensor path and raw unit; do not infer CPU/GPU identity from unnamed zones |
| CPU load and per-core frequency | `/proc/stat`, `/proc/loadavg`, cpufreq sysfs / Best-effort interface | Validate access and units; calculate utilization from monotonic deltas; unavailable if procfs is restricted |
| GPU frequency/utilization | Devfreq/sysfs vendor nodes / Best-effort interface | Preserve raw attributes and path; many builds restrict GPU counters |
| Storage total/free and volume state | StatFs, StorageManager / Public API | Sample at a low cadence; distinguish internal and adopted/removable volumes |
| App storage use | StorageStatsManager / User-granted Usage Access | Worker thread only; may take seconds |
| Display state, resolution/refresh configuration | DisplayManager/Display APIs / Public API | Configuration is not proof of instantaneous panel power |
| Network connectivity and transport | ConnectivityManager callbacks / Public API | Record connected transport and validated state, not payload contents |
| Per-app Wi-Fi/mobile traffic | NetworkStatsManager / User-granted Usage Access | Historical bucket data; per-UID access must be tested on Android 12 firmware |
| Radio signal / cell information | TelephonyManager / Restricted and permission-sensitive | Only request if a clearly explained optional permission is accepted; avoid IMEI, IMSI, phone number and other persistent identifiers |
| Device uptime, boot count, time/timezone changes | SystemClock, Settings/receivers / Public API | Use wall-clock plus elapsedRealtime plus boot identity to detect reboot and clock discontinuity |

### 2.4 Sensors and physical hardware

Use SensorManager.getSensorList(TYPE_ALL) to enumerate sensors actually reported by the handset. Persist name, vendor, version, type, resolution, range, power, min delay and wake-up flag. The Realme product specification lists magnetic induction, light, proximity, gyroscope and accelerometer sensors, but the runtime list is authoritative for the installed firmware.

Do not continuously poll every sensor merely because it exists. Sensor streams can increase power use and some sensors have privacy/permission restrictions. Start with inventory plus low-frequency, explicitly useful sensors. Avoid microphone/camera capture. Do not infer a sensor measurement from a sensor's mere presence.

### 2.5 Android-maintained history that must be imported

1. UsageStatsManager: app usage aggregates and event history. Events are only retained by Android for a few days; import on a frequent schedule and persist locally.
2. NetworkStatsManager: system-maintained mobile/Wi-Fi byte counters and time buckets, including per-UID history when permitted.
3. StorageStatsManager: package/UID storage statistics; queries may take several seconds and belong on a worker.
4. BatteryStats: system battery history and per-UID power estimates exist, but the service's full data is privileged. Ordinary app access is not assumed.
5. Public broadcasts/callbacks: battery, screen, power, package add/remove/replace, connectivity, time/timezone, boot and thermal state. Capture events when the app is alive and use gap detection where broadcasts cannot be guaranteed.

Android's history is not the permanent database. The app must import accessible history before Android rotates or expires it, deduplicate imports, and preserve the original source interval.

## 3. Target-device facts versus unknowns

Official Realme specifications list Helio G95, Mali-G76 MC4, 4500 mAh typical / 4400 mAh minimum battery, 65 W SuperDart charging, Wi-Fi 2.4/5 GHz 802.11ac, Bluetooth 5.0, GPS/AGPS/BeiDou/GLONASS and the listed motion/light/proximity/magnetic sensors.

These marketing specifications do not prove that the Android 12 kernel exposes charge counter, energy counter, cycle count, full-charge capacity, USB input current/voltage, SuperDart protocol state, CPU/GPU frequencies, thermal zones or per-UID power statistics to this app.

Required on-device capability census:
- Enumerate all readable `/sys/class/power_supply`, `/sys/class/thermal`, `/sys/class/hwmon`, `/sys/class/devfreq`, `/sys/devices/system/cpu`, Type-C, USB-PD and dual-role roots.
- Probe known candidate attributes without writing to any path; preserve raw path, value, errno/exception, root-list result and scan truncation.
- Enumerate SensorManager sensor list.
- Record Build fields and SDK level, kernel release, ABI list, memory/storage totals and display configuration. Avoid serial/IMEI/IMSI and other unnecessary identifiers.
- Probe BatteryManager properties independently and preserve unavailable sentinel versus exception.
- Probe Usage Access/AppOps and test UsageStatsManager query windows.
- Probe NetworkStatsManager and StorageStatsManager queries with per-source exception reporting.
- Probe PackageManager visibility and report the inventory scope.
- Verify Android/Realme battery optimization state and observed service restarts/gaps.
- Export a device capability report for offline review.

No source-level research can replace this actual-device census. The RMX2161 runtime report is a release gate for claims about hardware access.

## 4. Always-on lifecycle design

Desired behavior:
1. On first launch, initialize database, run permission/capability census, and start the foreground recorder automatically.
2. Keep an ongoing notification that clearly identifies active monitoring and current sampling mode.
3. On BOOT_COMPLETED, resume automatically without requiring a Start button; record boot identity and restart cause.
4. Also handle MY_PACKAGE_REPLACED and process/service recreation where supported.
5. Use START_STICKY as a recovery hint, not a guarantee.
6. Keep a bounded partial CPU wake lock only while continuous sampling is active. Report that this increases BatteryScope's own drain. If Android releases it or service dies, record the gap after recovery.
7. Do not expose Start/Stop controls. A permanent monitor must not depend on user remembering to start it. Provide diagnostics and export controls instead.
8. If the user force-stops the app, Android prevents automatic restart until the user launches it again. Report this as a platform limitation.
9. Respect notification permission behavior on Android 13+ for future compatibility; target device Android 12 does not have POST_NOTIFICATIONS runtime prompt.
10. Use WorkManager/JobScheduler for deferred historical imports and maintenance, not as a substitute for precise 30-second real-time sampling.

The Android 12 boot-completed exception allows a foreground service start from the boot receiver, but this is not a promise that an OEM will never kill or restrict the service. Realme auto-launch/background policy must be validated on the device. A user may still need to exempt the app from battery optimization through normal Settings if the OEM requires it; this must be diagnosed, not silently assumed.

## 5. Collection and storage architecture

- **Event collector:** receivers/callbacks for battery, power, screen, boot, package, connectivity, time and thermal changes.
- **Periodic collectors:** independent cadences for battery, CPU/memory, thermal, storage, network history, UsageStats imports and hardware inventory. Avoid one monolithic scan every 30 seconds.
- **Capability registry:** one record per source/property with supported API, permission/AppOp, last attempt, last success, status, failure class, raw path/API and next retry policy.
- **Raw observation store:** timestamp, elapsedRealtime, boot ID/count, source ID, property, raw value, unit, status, accuracy/quality and collection latency.
- **Derived metrics store:** formula/version, input observation IDs or interval, result, unit and confidence/quality. Never overwrite raw observations with calculated values.
- **System-history import ledger:** provider, queried interval, last imported watermark and deduplication key.
- **SQLite:** WAL mode, transactional batch writes, indexes for time/source/app, bounded query windows, non-destructive schema migrations, integrity checks and no silent retention cleanup.
- **Exports:** CSV for observations/events, JSON for structured capability and metadata, and an optional consistent SQLite backup. Export on worker thread; include units, source, quality, unavailable reasons and schema version.
- **Privacy:** all collection local by default; no analytics upload; no serial numbers, IMEI/IMSI, contact, microphone or camera collection. Usage Access is disclosed and user-controlled.

## 6. Data-quality contract

Every field must carry one of:
- `MEASURED` — directly returned by a documented API or verified hardware attribute.
- `ANDROID_AGGREGATE` — Android-maintained historical statistic.
- `RAW_HARDWARE` — un-decoded kernel/vendor attribute.
- `CALCULATED` — deterministic result from recorded inputs.
- `ESTIMATED` — modelled result with method and confidence.
- `UNAVAILABLE_PERMISSION`, `UNAVAILABLE_API`, `UNAVAILABLE_HARDWARE`, `UNAVAILABLE_NOT_EXPORTED`, `UNAVAILABLE_SELINUX_OR_ABSENT`, `ERROR`, `NOT_YET_PROBED`, `STALE`.

Null is not zero. A failed read must retain the failure class and time. A stale value must never be presented as current. Every derived value must link to its inputs and calculation version. For an ambiguous sysfs failure, report the ambiguity (for example absent-or-SELinux-hidden) rather than claiming a definitive cause.

## 7. Implementation phases and acceptance gates

### Phase A — product/lifecycle correction
- Remove Start/Stop UI and user-enabled gate.
- Start monitoring automatically on first launch.
- Resume on boot and package replacement.
- Keep persistent notification and explicit monitoring health state.
- Detect and record sampling gaps, reboot and wall-clock discontinuities.
- Test force-stop, reboot, Doze, screen-off and Realme background policy.

### Phase B — capability registry and device census
- Build the on-device diagnostic inventory described in section 3.
- Add a complete capability report and export.
- Add tests for status transitions, scan truncation, permission revocation and null-versus-zero behavior.

### Phase C — Android system history import
- UsageStatsManager events and aggregates.
- NetworkStatsManager mobile/Wi-Fi UID buckets.
- StorageStatsManager per-package storage.
- Import watermarks, deduplication and retention-window diagnostics.

### Phase D — device performance telemetry
- RAM, CPU load/frequency, thermal status/zones, storage, display, connectivity and sensor inventory.
- Only collect sources validated by runtime census.
- Separate fast, medium and slow collection cadences.

### Phase E — unified history and analytics
- Device-wide daily/weekly/monthly/yearly/all-time dashboards.
- Per-app use/network/storage and coincident battery movement, with no claim of per-app electrical energy.
- Query performance, DB maintenance and export verification.

### Phase F — release validation
- Android CI tests/build and inspect exact APK artifact.
- Verify package/version/signing certificate and update compatibility.
- Install only after signing continuity and data-preservation checks.
- Validate all reported RMX2161 hardware sources with a real diagnostic export.
- Do not mark 24/7 continuity or hardware coverage as verified without multi-day physical testing.

## 8. Known hard limits

- Ordinary apps cannot obtain unrestricted BatteryStats/per-UID battery energy using the privileged BATTERY_STATS permission.
- Usage events are retained by Android for only a few days; the app must import them before expiry.
- Per-app network bytes and storage size are not per-app battery energy.
- A foreground service and wake lock improve continuity but consume power and remain subject to force-stop, shutdown and OEM/system policy.
- Proprietary SuperDart electrical negotiation and active adapter output may not be exposed through public interfaces.
- Public API access and sysfs visibility vary by exact firmware. Only the handset census can settle device-specific availability.

## 9. Primary references

- Android 12 behavior changes: https://developer.android.com/about/versions/12/behavior-changes-12
- Background foreground-service start restrictions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Background execution limits: https://developer.android.com/about/versions/oreo/background
- UsageStatsManager: https://developer.android.com/reference/android/app/usage/UsageStatsManager
- NetworkStatsManager: https://developer.android.com/reference/android/app/usage/NetworkStatsManager
- StorageStatsManager: https://developer.android.com/reference/android/app/usage/StorageStatsManager
- PowerManager thermal APIs: https://developer.android.com/reference/android/os/PowerManager
- BATTERY_STATS permission: https://developer.android.com/reference/android/Manifest.permission#BATTERY_STATS
- BatteryStats / dumpsys reference: https://developer.android.com/tools/dumpsys
- Realme Narzo 20 Pro official specifications: https://www.realme.com/in/realme-narzo-20-pro/specs
