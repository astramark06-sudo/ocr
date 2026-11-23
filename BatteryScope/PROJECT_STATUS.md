# BatteryScope project status and engineering handoff

Last source update: 2026-11-23  
Active branch: batteryscope  
Current source version: 1.14  
Current version code: 15  
Application ID / namespace: com.batteryscope  
Target handset: Realme Narzo 20 Pro (RMX2161), Android 12, non-rooted

## Architecture

BatteryScope is a standalone Android application. It uses standard Android APIs and ordinary-app process access only. It has no Shizuku dependency, provider, AIDL service, ADB setup, root requirement or companion-app requirement. The privileged charging-control path has been removed; BatteryScope does not write sysfs.

## Source layout

- `app/src/main/java/com/batteryscope/MainActivity.kt` — UI, Android telemetry, ordinary-app read-only diagnosis, recorder controls, capacity panel and CSV picker.
- `app/src/main/java/com/batteryscope/BatteryRecorderService.kt` — foreground recorder, worker thread, event capture, ordinary-app sysfs inventory, SQLite schema/history analytics and CSV writer.
- `app/src/main/java/com/batteryscope/BatteryCapacityEstimator.kt` — pure estimator using charge-counter/SOC session deltas.
- `app/src/main/java/com/batteryscope/BatteryRateMath.kt` — tested mA rate and SOC forecast calculations.
- `app/src/test/java/com/batteryscope/BatteryRateMathTest.kt` — pure calculation tests.
- `app/src/main/java/com/batteryscope/BatteryRecorderBootReceiver.kt` — user-intent-based BOOT_COMPLETED resume attempt.
- `app/src/main/AndroidManifest.xml` — Android permissions, launcher, boot receiver and foreground-service declaration.
- `app/src/test/java/com/batteryscope/BatteryCapacityEstimatorTest.kt` — capacity-estimator unit tests.
- `app/build.gradle.kts` — application identity, version and Android dependencies.
- `README.md`, `AUDIT.md`, `CHANGELOG.md`, `RELEASE_CHECKLIST.md`, `AGENTS.md` — product and engineering documentation.

## Current capabilities

The app collects Android BatteryManager battery-side measurements and best-effort read-only class-tree attributes visible to the ordinary app process. The recorder stores 30-second framework samples and 120-second class-tree samples in a local SQLite database. SQLite schema v4 adds optional charger-advertised maximum current/voltage, battery presence, Android invalid-charger flag and cycle-count metadata with non-destructive sequential migrations from v1/v2/v3. Local SQLite migration simulations from all three prior schema versions preserved existing rows. Live dashboard reads now run on a dedicated HandlerThread rather than the UI thread. Diagnostics report per-root visibility, read/list errors, Android property unavailability and Usage Access state. Existing history summaries and CSV export remain local. History reports percentage/hour and charge-counter-derived mA rates, screen-on/off comparisons, plugged-in battery decline, app foreground-time correlation, recent sessions, SOC threshold times and historical time-to-full/time-to-empty projections.

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

- Source changes for version 1.14 / versionCode 15 have been committed to branch `batteryscope`.
- Capacity estimator unit tests cover nominal capacity, small-SOC-change rejection, counter-direction mismatch and missing boot-count rejection.
- Battery rate math unit tests cover battery-side mA calculations and SOC time forecasts.
- Source-level checks completed: no Shizuku/privileged service references in MainActivity, recorder service, manifest or Gradle dependencies; CSV header/sample/event row alignment verified at 28 fields; isolated Kotlin arithmetic checks passed for battery-side mA and SOC forecasts.
- Fixed a duplicate MainActivity lifecycle override that would have blocked Kotlin compilation. Recorder event writes and orderly database shutdown were moved off the Android main thread; the boot receiver no longer opens SQLite during BOOT_COMPLETED.
- Added a dedicated GitHub Actions workflow for Android Gradle tests, debug APK assembly, package/version verification and artifact upload. Its run result for the latest branch head is not yet confirmed.
- Full Android Gradle build and repository JVM test suite: pending workflow verification. The local execution environment has no Android SDK/Gradle installation.
- APK artifact, SHA-256 and signing identity for version 1.14: not yet verified.
- No install or runtime test has been performed on the physical RMX2161.
- Actual charge-counter availability and capacity-estimate accuracy on this handset remain unverified.

## Physical RMX2161 validation

1. Install the exact final APK and verify update compatibility and preserved local history.
2. Confirm BatteryManager charge-counter readings are present and change plausibly while charging and discharging.
3. Record multiple sessions with at least 12 percentage points of change; compare the resulting estimate across sessions.
4. Check confidence/spread behavior and ensure missing charge-counter data produces an unavailable result.
5. Verify recorder notification, screen-off sampling, wake-lock power impact, CSV export and app-private history.
6. Test boot-resume behavior and Realme background-management restrictions.
7. Confirm no Shizuku, root, ADB or companion-app setup is requested or required.
