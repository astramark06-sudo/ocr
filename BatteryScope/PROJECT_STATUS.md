# BatteryScope project status and engineering handoff

Last source update: 2026-11-23  
Active branch: batteryscope  
Current source version: 1.15  
Current version code: 16  
Application ID / namespace: com.batteryscope  
Target handset: Realme Narzo 20 Pro (RMX2161), Android 12, non-rooted

## Architecture

BatteryScope is a standalone Android application. It uses standard Android APIs and ordinary-app process access only. It has no Shizuku dependency, provider, AIDL service, ADB setup, root requirement or companion-app requirement. The privileged charging-control path has been removed; BatteryScope does not write sysfs.

## Source layout

- `app/src/main/java/com/batteryscope/MainActivity.kt` — UI, Android telemetry, ordinary-app read-only diagnosis, automatic recorder recovery, capacity panel and CSV picker.
- `app/src/main/java/com/batteryscope/BatteryRecorderService.kt` — foreground recorder, worker thread, event capture, ordinary-app sysfs inventory, SQLite schema/history analytics and CSV writer.
- `app/src/main/java/com/batteryscope/BatteryCapacityEstimator.kt` — pure estimator using charge-counter/SOC session deltas.
- `app/src/main/java/com/batteryscope/BatteryRateMath.kt` — tested mA rate and SOC forecast calculations.
- `app/src/main/java/com/batteryscope/BatteryPercentMath.kt` — validated battery level/scale conversion.
- `app/src/test/java/com/batteryscope/BatteryRateMathTest.kt` — pure calculation tests.
- `app/src/test/java/com/batteryscope/BatteryPercentMathTest.kt` — missing/invalid percentage metadata tests.
- `app/src/main/java/com/batteryscope/BatteryRecorderBootReceiver.kt` — automatic BOOT_COMPLETED and MY_PACKAGE_REPLACED resume attempt.
- `app/src/main/AndroidManifest.xml` — Android permissions, launcher, boot receiver and foreground-service declaration.
- `app/src/test/java/com/batteryscope/BatteryCapacityEstimatorTest.kt` — capacity-estimator unit tests.
- `app/build.gradle.kts` — application identity, version and Android dependencies.
- `README.md`, `AUDIT.md`, `CHANGELOG.md`, `RELEASE_CHECKLIST.md`, `AGENTS.md` — product and engineering documentation.

## Current capabilities

The app collects Android BatteryManager battery-side measurements and best-effort read-only class-tree attributes visible to the ordinary app process. The recorder stores 30-second framework samples and 120-second class-tree samples in a local SQLite database. SQLite schema v4 adds optional charger-advertised maximum current/voltage, battery presence, Android invalid-charger flag and cycle-count metadata with non-destructive sequential migrations from v1/v2/v3. Local SQLite migration simulations from all three prior schema versions preserved existing rows. Live dashboard reads now run on a dedicated HandlerThread rather than the UI thread. Diagnostics report per-root visibility, read/list errors, Android property unavailability and Usage Access state. Existing history summaries and CSV export remain local. History reports percentage/hour and charge-counter-derived mA rates, screen-on/off comparisons, plugged-in battery decline, app foreground-time and charge-counter correlation, recent sessions, SOC threshold times and historical time-to-full/time-to-empty projections. Recent charging/discharging session reports also calculate display-on/display-off rates and separate time-to-threshold forecasts; display state is a proxy, not exact touch activity.

The new Battery Health panel estimates full-charge capacity from charge-counter movement relative to percentage movement across continuous charge/discharge sessions. It separately reports readable kernel fuel-gauge charge_full/design-capacity ratio and cycle count, with explicit missing/denied/hidden diagnostics. It examines up to 90 days, requires at least 12 percentage points of movement and consistent counter direction, uses the median of valid session estimates, and reports session count, spread and confidence. Design capacity is set to 4,500 mAh for the RMX2161. If Android does not expose a usable charge counter, no capacity value is fabricated.

## Measurement boundaries

- BatteryManager current/voltage and derived watts are battery-side, not USB input power.
- The charge counter is remaining-charge telemetry, not FCC by itself.
- Capacity health is an estimate from recorded charge-counter/SOC changes, not a laboratory test.
- Android health GOOD is a framework status, not capacity health.
- USB V/I candidates require an external supply identity and still need on-device driver-semantic validation.
- PDO records are advertised capabilities, not proof of the selected active contract.
- VOOC/SuperDart flags are driver state, not protocol packet capture.
- Foreground-app battery decline is correlation, not direct per-app electrical measurement.
- Missing or denied values remain unavailable, not zero.

## Verification state

- Source changes for version 1.15 / versionCode 16 have been committed to branch `batteryscope`.
- Capacity estimator unit tests cover nominal capacity, small-SOC-change rejection, counter-direction mismatch and missing boot-count rejection.
- Battery rate math unit tests cover battery-side mA calculations and SOC time forecasts; battery percentage math tests reject missing or invalid Android level/scale metadata.
- Source-level checks completed: no Shizuku/privileged service references in MainActivity, recorder service, manifest or Gradle dependencies; CSV header/sample/event row alignment verified at 28 fields; isolated Kotlin arithmetic checks passed for battery-side mA and SOC forecasts.
- Fixed a duplicate MainActivity lifecycle override that would have blocked Kotlin compilation. Recorder event writes and orderly database shutdown were moved off the Android main thread; the boot receiver no longer opens SQLite during BOOT_COMPLETED.
- A dedicated GitHub Actions workflow builds/tests the app, verifies package/version metadata and APK signature, and uploads artifacts. The v1.14 run 45211411009 succeeded for commit 1e1660e; the v1.15 corrective commits require a new run.
- BatteryManager property diagnostics distinguish unavailable sentinels, SecurityException and other exception classes. Version 1.15 additionally validates current polarity and voltage range, records quality transitions and excludes invalid values from battery-side wattage.
- v1.14 Android Gradle build and 14 JVM tests passed in workflow run 45211411009. v1.15 adds eight measurement-quality tests; its full build/test result is pending the new workflow run.
- v1.14 APK artifact verified: 913,390 bytes; SHA-256 ea9a9a1dac3c8dbe291534cda11f2e5173d7ae64073bb8d2140e786a957b036b; debug signer certificate SHA-256 3c75edb5262b17164453949bb90113e133edc2daeceb10846cd233d2be8194b5. v1.15 APK is pending.
- No install or runtime test has been performed on the physical RMX2161. Compare signing-certificate digests before any update install.
- Actual charge-counter availability and capacity-estimate accuracy on this handset remain unverified.

## Physical RMX2161 validation

1. Install the exact final APK and verify update compatibility and preserved local history.
2. Confirm BatteryManager charge-counter readings are present and change plausibly while charging and discharging.
3. Record multiple sessions with at least 12 percentage points of change; compare the resulting estimate across sessions.
4. Check confidence/spread behavior and ensure missing charge-counter data produces an unavailable result.
5. Verify recorder notification, screen-off sampling, wake-lock power impact, CSV export and app-private history.
6. Test boot-resume behavior and Realme background-management restrictions.
7. Confirm no Shizuku, root, ADB or companion-app setup is requested or required.


## Device-wide monitoring expansion — in progress (2026-11-23)

The product objective has expanded from battery-only monitoring to a permanent, local-first device telemetry platform. The full capability matrix, Android API/permission classification, history-import plan, RMX2161 device census, lifecycle design and phased acceptance gates are in [DEVICE_TELEMETRY_ARCHITECTURE.md](DEVICE_TELEMETRY_ARCHITECTURE.md).

Source changes now request recorder startup automatically when the app resumes, resume on BOOT_COMPLETED and MY_PACKAGE_REPLACED, and remove the in-app Start/Stop controls and service stop action. The boot receiver no longer gates restart on the former user-enabled preference. The ongoing foreground notification remains. This lifecycle source change is not yet build-verified or tested on the RMX2161.

Newly identified Android data sources for later implementation:
- UsageStatsManager events and daily/weekly/monthly/yearly aggregates (requires user-granted Usage Access; detailed events expire after a few days).
- NetworkStatsManager mobile/Wi-Fi and per-UID byte buckets (requires access and device verification).
- StorageStatsManager per-package storage usage (queries can take seconds and must run off-main-thread).
- PowerManager thermal status/headroom, ActivityManager memory information, SensorManager sensor inventory, and best-effort CPU/GPU/thermal sysfs sources.
- BatteryStats per-UID energy and full system battery history remain privileged; no ordinary-app workaround is assumed.

The RMX2161's exact kernel exports, UsageStats/NetworkStats query behavior, SuperDart input telemetry, sensor inventory and Realme background policy remain unverified until an on-device capability report is collected. No new device-wide collector is marked implemented merely because its API has been identified.


## Latest Android CI checkpoint — 2026-11-23

The dedicated Android build on commit `81286c706390d6c60d94a60428c8b298fa0651d2` failed during Kotlin compilation, not SDK setup. The exact compiler diagnostic was an unterminated string / incorrect template entry at `MainActivity.kt:407:204`. The offending bounded-kernel-scan warning string is fixed in commit `8463bb34afa81b2ba26e5598342ad8c5f798ce78`. A new APK workflow run (`45211353283`) was started for that commit and must be inspected before claiming build success. The failed run created no APK and uploaded no build artifact. General repository CI passed on the previous checkpoint; that does not validate the Android app.


## Latest handset-export validation — 2026-11-23

Reviewed user-provided `BatteryScope_History_20261123_214733.csv` from the RMX2161. The export contains 22 SAMPLE rows and 51 EVENT rows over 13 min 35.654 s (UTC 16:03:51.492–16:17:27.146; 21:33:51–21:47:27 India time). Battery SOC fell from 83% to 80%; Android charge counter fell from 3,735,000 to 3,600,000 µAh; temperature ranged 33.8–35.4 °C. All samples report NOT_CHARGING / plug NONE, so this export contains no charging session and cannot validate charging watts.

Critical measurement finding: `battery_voltage_mv` contains only raw values 3 or 4, although the source labels the field mV and the live dashboard accepts only 2,500–5,000 mV. Consequently, the current dashboard will reject these voltage values and cannot calculate battery-side watts. `battery_current_ua` contains 14–674 (mean 201.9; median 132) and is treated as µA by the app, yielding only 0.014–0.674 mA. These magnitudes do not reconcile cleanly with the 135 mAh charge-counter decline over the capture interval (coarse endpoint-derived average about 595.8 mA). This is evidence of a device/API scaling or counter-quantization issue, not proof of a particular conversion factor. Do not multiply or relabel these values automatically until verified against a known-good RMX2161 reference or independent measurement.

Average-current property, energy counter, cycle count, charger-advertised maxima and USB voltage/current candidates were absent in all samples. Usage Access was off in 20/22 samples and on only in the final two. Twenty-one sample intervals averaged 38.84 s; one interval was 154.741 s (2 min 34.7 s), exceeding the 90 s analytics continuity threshold. Screen state changed during the capture. Kernel root diagnostics in the export report READ_DENIED or ABSENT_OR_HIDDEN_BY_SELINUX, with zero captured fields.

Disposition: recorder and CSV persistence are demonstrated for this short session; measurement-unit correctness, battery-side watt calculation, USB-input watts, long-duration continuity and device-wide collectors are not validated by this file. Preserve raw values. Next engineering action is to add explicit raw-value/unit diagnostics and handset-specific validation, then test charging and discharging captures separately. Do not claim 24/7 continuity or charger-input wattage from this export.
\n\n## Forensic audit and v1.15 correction — 2026-11-23\n\nSee [FORENSIC_AUDIT_2026-11-23.md](FORENSIC_AUDIT_2026-11-23.md) for the source-tree/APK integrity review and extended CSV findings. Version 1.15 now validates BatteryManager current direction against Android's documented convention, validates battery voltage in mV, prevents wattage calculation from invalid inputs, logs quality transitions, simplifies the main telemetry panel and retains UsageStats source event timestamps. Eight tests were added. No database schema migration or package identity change was made.\n\n**Still incomplete:** NetworkStatsManager, StorageStatsManager, SensorManager, ActivityManager memory, installed-app inventory, CPU/GPU collectors and other device-wide telemetry remain unimplemented. No physical-device or 24-hour continuity validation has been completed.\n