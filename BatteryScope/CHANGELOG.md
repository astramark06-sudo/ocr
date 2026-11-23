# BatteryScope changelog

## 1.14 — expanded telemetry, diagnostics and non-blocking live dashboard
- Moved live BatteryManager and kernel class-tree reads to a dedicated HandlerThread; dashboard refresh no longer traverses sysfs on the UI thread.
- Added a persisted continuous/low-impact recorder mode: continuous holds a partial CPU wake lock for screen-off continuity; low-impact releases it and exposes resulting sample gaps.
- Added best-effort charger-advertised maximum charging current/voltage, battery-present state, invalid-charger flag and optional cycle-count metadata to live telemetry and SQLite schema v4, with non-destructive v2/v3 migrations.
- Added explicit diagnostics for unavailable BatteryManager properties, Usage Access, per-kernel-root visibility and read/list failures; root paths hidden by SELinux are not falsely reported as definitely absent.
- Added a separate kernel fuel-gauge report in Battery Health for readable charge_full, charge_full_design and cycle_count values, with a reason when the driver does not export or the app cannot read them.
- Kept empirical capacity and kernel-reported capacity-health estimates separate.
- Expanded history with charge-counter-derived mA charge/discharge rates, screen-on/off rate comparisons and historical time-to-full/time-to-empty projections.
- Extended CSV export with the additional battery/charger fields.
- Preserved the 30-second recorder cadence and 120-second hardware-tree inventory cadence.
- Version code: 15.


## 1.13 — standalone battery capacity estimation
- Added a Battery Health panel estimating full-charge capacity from Android charge-counter movement relative to state-of-charge changes across continuous sessions.
- Uses up to 90 days of history, a 12-percentage-point minimum session change, same-boot continuity, direction checks, median aggregation, session spread and qualitative confidence.
- Uses 4,500 mAh as the RMX2161 design-capacity baseline; reports unavailable rather than fabricating capacity when the charge counter is missing or no valid sessions exist.
- Removed Shizuku dependencies, provider, UserService/AIDL integration and setup requirements. BatteryScope now uses standard Android APIs and ordinary-app access only.
- Removed privileged Oplus charging-control writes; no sysfs write path remains.
- Added estimator unit tests for nominal capacity and invalid data rejection.
- Version code: 14.


## 1.12 — CSV export integrity and session timing correction
- Fixed CSV record terminators: exported headers, samples and events now end with actual newline characters rather than literal backslash-n text.
- Centralized CSV row quoting/escaping and added JVM unit tests for commas, quotes, embedded line breaks and empty columns.
- Session screen-on duration and full-charge session boundaries now use the same monotonic-aware interval calculation as total session duration.
- Persisted Android BOOT_COUNT per sample, added a non-destructive SQLite v1-to-v2 migration, and use wall-clock duration across reboot even when post-reboot uptime is larger than the previous uptime.
- Moved CSV generation and copying to the selected document destination off the UI thread to avoid ANRs on large history exports.
- CI now runs testDebugUnitTest before assembling the APK.
- Version code: 13.

All versions are debug APK development builds unless a signed release is explicitly documented. Source changes and CI builds do not imply physical-handset testing.

## 1.11 — continuous recorder, session analytics and privileged history
- Added a user-started foreground recorder on a dedicated HandlerThread, independent of MainActivity visibility and Activity pause/destroy.
- Added a local SQLite sample/event store with 30-second Android battery sampling and 120-second bounded power_supply, Type-C, USB-PD, USB-role, thermal, hwmon and non-serial USB descriptor inventories.
- Added a recorder-owned optional read-only Shizuku UserService binding; saves power-path snapshots every 120 seconds and focused dumpsys batterystats --charged snapshots every 15 minutes. The complete audit remains on-demand.
- Added 24-hour, 7-day, 30-day, 90-day, 365-day and all-time streaming summaries; recent charge/discharge session ledgers; charge-counter changes; screen-on/off rates; plugged-in battery decline; app foreground correlation; threshold timing; and sampling-gap diagnostics.
- Uses monotonic elapsedRealtime for sample interval calculations within a boot, with wall-clock fallback across reboot.
- Prevents threshold projections when battery movement contradicts the CHARGING/DISCHARGING state.
- Added partial CPU wake lock during user-requested recording to reduce deep-sleep gaps; notification discloses that this increases the app's own power use.
- Added user-intent-based attempt to resume after BOOT_COMPLETED; powered-off time remains unrecorded and OS/OEM restrictions can prevent resume.
- Added Usage Access and battery-optimization settings shortcuts.
- Added durable app-private CSV export with chronological event/sample merge and wall-clock plus monotonic timestamps.
- USB input candidates now require USB-named or USB-typed supply identity; unrelated external supply V/I pairs are not labelled VBUS/IBUS.
- App-level drain remains correlation, not direct per-app electrical measurement. Hardware semantics and background continuity remain subject to RMX2161 runtime testing.
- Version code: 12.

## 1.10 — initial persistent telemetry recorder milestone
- Added a user-started foreground service, SQLite raw history, Usage Access correlation, event capture and CSV export.
- Version code: 11. Superseded by 1.11.

## 1.9 — expanded measurement diagnosis
- Expanded ordinary-app read-only discovery across power-supply, Type-C, USB-PD, dual-role USB, USB-role, thermal and hwmon class attributes.
- Added automatic diagnosis distinguishing live candidates, configured limits and denied/missing data.
- Version code: 10.

## 1.8 — expanded power-path discovery
- Expanded Shizuku and ordinary-app sysfs inventory.
- Added thermalservice and power-service dumpsys output.
- Version code: 9.

## 1.7 — launcher identity
- Added adaptive launcher icon resources and preserved application ID com.batteryscope.
- Version code: 8.

## 1.6 — telemetry labelling and CSV export
- Marked implausible Android battery voltages invalid in the UI and excluded them from battery-side power estimation.
- Clarified charge counter and Android health limitations.
- Added Storage Access Framework export for CSV.
- Version code: 7.

## 1.5 — Shizuku diagnostics baseline
- Added Android battery telemetry, sysfs audit, Shizuku diagnostic service, live power-path sampling, conditional Oplus cool_down control, CSV logging and diagnostic text display.
- Version code: 6.


## 1.14 — Reliability hardening (2026-11-23)

- Removed a duplicate Activity lifecycle override that would prevent Kotlin compilation.
- Moved recorder event database writes from main-thread service/broadcast callbacks to the recorder worker thread.
- Queued recorder database shutdown after pending worker operations.
- Removed SQLite access from the boot-completed broadcast receiver; boot-resume failures are retained in preferences and surfaced in diagnostics.
- Added a dedicated Android GitHub Actions build/test/APK-artifact workflow.
- No application ID, versionCode or SQLite schema change in this hardening pass.
