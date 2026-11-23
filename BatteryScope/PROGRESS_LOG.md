# BatteryScope persistent progress log

This file is the durable checkpoint for resuming work in a new chat.

## 2026-11-23 — Comprehensive telemetry milestone (source version 1.14)

Repository: `astramark06-sudo/test`  
Branch: `batteryscope`  
Target: Realme Narzo 20 Pro RMX2161 / Android 12  
App ID: `com.batteryscope`

Committed source changes:
- Moved live dashboard BatteryManager and kernel-tree reads onto a dedicated HandlerThread so directory traversal no longer blocks the UI thread.
- Reduced dashboard refresh polling to 2 seconds while keeping recorder collection separate.
- Added optional Android broadcast metadata: charger maximum charging current/voltage, battery-present and cycle-count extra.
- Added SQLite schema version 3 and non-destructive v2-to-v3 migration for these four fields; v1-to-v2 boot-count migration remains.
- Added the new fields to CSV export.
- Expanded diagnostics for BatteryManager unavailable sentinels, Usage Access, individual kernel class-root status, read/list errors, thermal status and captured field count.
- Avoids claiming a root path is definitely absent when it may be hidden by SELinux.
- Expanded history with battery-side charge-counter mA rates, screen-on/off charge/discharge comparisons, and historical time-to-100% / time-to-0% projections.
- Updated README, audit and changelog.
- Version bumped to 1.14 / versionCode 15.

Existing capabilities retained:
- 30-second local foreground recording; 120-second hardware-tree inventory.
- Battery current, voltage, temperature, SOC, status, charge counter, energy counter and Android health.
- Read-only power_supply, Type-C, USB-PD, USB-role, thermal, hwmon and selected USB descriptor inventory.
- Session duration, SOC threshold timing, daily/weekly/monthly/quarterly/yearly/all-time summaries.
- Screen-state and foreground-app correlation.
- Local SQLite retention and chronological CSV export.
- Capacity estimation from charge-counter/SOC session deltas against the 4,500 mAh design baseline.

Verification status at checkpoint:
- GitHub source edits are committed in the branch; source-level checks were inspected.
- Version 1.14 GitHub Actions build/test result has not yet been confirmed.
- No physical RMX2161 runtime validation has been performed.
- USB input power semantics and cycle-count availability remain handset-dependent.
- Do not claim the APK is ready until CI artifact and signature are inspected.

Next:
- Verify GitHub Actions for the latest batteryscope commit; repair failures.
- Audit background service, database migrations, CSV alignment, performance and app usage analytics.
- Add tests for statistics and migration/export invariants.
- Keep each logical milestone committed and update this log/handoff.


Checkpoint marker: workflow-triggering commit created after 1.14 source milestone.

## Additional 1.14 implementation checkpoint

- Dashboard telemetry and kernel sysfs enumeration were moved to a dedicated HandlerThread; the UI no longer performs the bounded kernel directory scan.
- Added optional charger maximum current/voltage, battery-present and cycle-count extras to live readings and recorder samples.
- Added four schema-v3 columns with non-destructive migration from existing schema v2.
- Corrected CSV sample/header fields to include those four values.
- History now shows battery-side mA rate from charge-counter deltas and historical time-to-full/time-to-empty forecasts split by screen-on/off charging/discharging averages.
- Added pure `BatteryRateMath` and unit tests for rate and forecast arithmetic.
- Improved kernel access diagnostics and explicit hardware scan-cap reporting.
- GitHub Actions build and device runtime verification remain pending.

- Added user-selectable continuous (wake lock enabled) and low-impact (wake lock released) recording modes. Mode is persisted, reflected in the foreground notification and diagnosis, and recorded as an event when changed. Low-impact mode allows Android deep sleep to create measurable gaps rather than forcing continuous CPU wakeups.
- Fixed Activity teardown to stop the live telemetry HandlerThread.

- Added live kernel fuel-gauge parsing for optional `cycle_count`, `charge_full` and `charge_full_design`; displays a separate gauge-reported capacity-health ratio and explicit missing-attribute diagnosis. This remains distinct from the empirical charge-counter/SOC capacity estimate.
- Added the optional hardware inventory field-cap diagnostic and Android thermal status to the live scan.

## Final checkpoint — source audit and remaining verification

- Confirmed no Shizuku, Shizuku provider/AIDL, privileged service, or Oplus `cool_down` control references remain in the app's MainActivity, recorder service, manifest or Gradle dependencies; deleted Shizuku service/AIDL paths return not found.
- Confirmed SQLite schema v3 migration and CSV field alignment: 27 header fields, 22 sample-specific columns plus record type/timestamp/elapsed and event columns; sample and event row widths both align to 27.
- Corrected history charge-counter reporting so missing or direction-inconsistent counter values remain `n/a`, not false 0 mAh or 0 mA.
- Local isolated Kotlin checks passed for capacity estimation (3 scenarios) and rate/forecast arithmetic (4 checks). These are not a substitute for the full Android Gradle build.
- Android Gradle build, repository JVM suite, APK artifact/signature and RMX2161 runtime validation remain unverified. Do not install over an existing signed build or uninstall an older app without protecting its local database.
- Latest inspected source milestone before this checkpoint: `344c04242220d17dd2bb6be5c9fbeae5bbd97d12`.

## Capacity panel follow-up

- Extended the Battery Health panel to show both the empirical charge-counter/SOC capacity estimate and the latest kernel fuel-gauge `charge_full`, `charge_full_design` and `cycle_count` report.
- The kernel report identifies a denied attribute, a denied/hidden power_supply root, a non-numeric value, or an attribute not exported/reached by the bounded scan. It does not conflate this with the empirical estimate.
- Corrected history rate analytics so missing or sign-inconsistent charge-counter data is shown as unavailable, not a fabricated zero.
