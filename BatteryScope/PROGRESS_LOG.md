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
- Added SQLite schema version 4 and non-destructive v2-to-v3 migration for these four fields; v1-to-v2 boot-count migration remains.
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
- Confirmed SQLite schema v4 migration and CSV field alignment: 27 header fields, 22 sample-specific columns plus record type/timestamp/elapsed and event columns; sample and event row widths both align to 27.
- Corrected history charge-counter reporting so missing or direction-inconsistent counter values remain `n/a`, not false 0 mAh or 0 mA.
- Local isolated Kotlin checks passed for capacity estimation (3 scenarios) and rate/forecast arithmetic (4 checks). These are not a substitute for the full Android Gradle build.
- Android Gradle build, repository JVM suite, APK artifact/signature and RMX2161 runtime validation remain unverified. Do not install over an existing signed build or uninstall an older app without protecting its local database.
- Latest inspected source milestone before this checkpoint: `344c04242220d17dd2bb6be5c9fbeae5bbd97d12`.

## Capacity panel follow-up

- Extended the Battery Health panel to show both the empirical charge-counter/SOC capacity estimate and the latest kernel fuel-gauge `charge_full`, `charge_full_design` and `cycle_count` report.
- The kernel report identifies a denied attribute, a denied/hidden power_supply root, a non-numeric value, or an attribute not exported/reached by the bounded scan. It does not conflate this with the empirical estimate.
- Corrected history rate analytics so missing or sign-inconsistent charge-counter data is shown as unavailable, not a fabricated zero.

## Schema v4 charger-diagnostic checkpoint

- Added the Android `invalid_charger` battery-broadcast extra to live telemetry, diagnosis and recorder samples.
- SQLite schema is now v4; v3-to-v4 migration adds `invalid_charger_state` without deleting existing history. v1/v2 migrations remain sequential.
- CSV includes invalid-charger status. Header, sample row and event row widths were rechecked: 28 columns each (23 sample-specific columns plus the 3 leading and 2 trailing record/event fields).
- Full Android Gradle build and physical RMX2161 validation remain pending; no APK is claimed as verified.

## Schema v4 validation result

- SQLite migration simulations from schema v1, v2 and v3 to v4 passed; an existing sample row was preserved and newly added columns remained null as expected.
- CSV header, sample row and event row widths align at 28 fields after adding `invalid_charger_state`.
- Android invalid-charger flag is now included in the live panel, diagnostics, SQLite and CSV.
- Pure Kotlin rate/capacity arithmetic checks passed earlier; full Android build, GitHub Actions result and physical handset validation remain pending.


## 2026-11-23 — Responsiveness, boot-resume and Android CI hardening

- Removed a duplicate `MainActivity.onDestroy()` override found during source review; this was a Kotlin compile blocker.
- Added `.github/workflows/batteryscope-android.yml`: Java 17, Android SDK 35, Gradle 8.9, JVM unit tests, debug APK assembly, package/version metadata check, SHA-256 report and a retained GitHub Actions artifact.
- Moved recorder event SQLite writes from Android service/broadcast main-thread callbacks onto the recorder HandlerThread. Service shutdown now queues database closure after pending worker tasks so queued events are not discarded.
- Removed synchronous SQLite opening/writes from the BOOT_COMPLETED receiver. A boot-resume startup exception is retained in preferences and shown in the app's automatic diagnostics; successful recorder startup clears the stale failure.
- Existing focused JVM tests include battery rate/forecast math, capacity estimation, reboot-aware time intervals and CSV row escaping.
- Source version remains 1.14 / versionCode 15. No application ID or database schema change was made in this hardening pass.
- Verification still required: inspect the Android Actions run for the exact branch head, repair any compiler/test failures, inspect the produced artifact and signature, and test on the physical RMX2161. No APK is declared verified by these source commits.


## 2026-11-23 — Per-session screen-state charging/discharging analysis

- Recent charging and discharging session reports now calculate separate display-on and display-off SOC rates from valid intervals within that session.
- Where the charge counter is available and direction-consistent, the session report also shows separate signed battery-side mA estimates for display-on and display-off intervals.
- Each session now estimates remaining time to 100% or 0% using its own display-on and display-off rates, while suppressing forecasts when the overall session direction is inconsistent or the threshold is already reached.
- Display state is explicitly a usage proxy, not proof of active touch interaction. Foreground-app UsageStats correlation remains a separate approximate metric.
- Source version remains 1.14 / versionCode 15; no database schema change. Full Android compile/test and device verification are still pending.


## 2026-11-23 — Foreground-app charge-counter correlation

- Per-app history now reports foreground duration, coincident SOC movement during charging, unplugged discharge and plugged-in decline.
- When both samples have valid charge-counter readings and the counter direction agrees, the app report also shows the associated remaining-charge-counter change in mAh for each condition.
- Attribution is intentionally limited to intervals where the same package is foreground at both sample endpoints and the display is on. These values are temporal correlation, not direct per-app electrical energy or proof of causation.
- Missing charge-counter intervals remain `n/a`; no per-app mAh is fabricated.
- No database schema change. Android build/test and physical handset verification remain pending.


## Android CI artifact verification scope

- The Android workflow's APK verification step now runs `apksigner verify --verbose --print-certs` and saves the signing-certificate details alongside the APK SHA-256, package metadata and test report.
- The workflow is configured but its result for the current branch head has not been confirmed through the available GitHub status interface. Do not claim CI passed or distribute an artifact until the exact run is inspected.


## 2026-11-23 — BatteryManager unavailable-reason diagnostics

- Live diagnostics now distinguish a BatteryManager unavailable sentinel from a SecurityException and other runtime exceptions for CURRENT_NOW, CURRENT_AVERAGE, CHARGE_COUNTER and ENERGY_COUNTER.
- The dashboard separately reports whether ACTION_BATTERY_CHANGED was available and whether battery level, voltage and temperature extras were missing or invalid.
- The recorder persists BatteryManager property-access status transitions as events (AVAILABLE, unavailable sentinel, security exception or exception class). It logs transitions only, not every 30-second sample, to avoid event spam.
- No schema change. Exact hardware-path visibility still depends on the Realme ROM and ordinary-app SELinux permissions.


## 2026-11-23 — Battery percentage input validation

- Removed the implicit 100-point default when Android does not provide EXTRA_SCALE. Both the live dashboard and recorder now require a valid EXTRA_LEVEL/EXTRA_SCALE pair.
- Added a shared pure `BatteryPercentMath` helper that rejects missing scale, non-positive scale, negative level and level greater than scale instead of inventing a percentage.
- Added JVM tests for normal, full, empty, missing and out-of-range values.
- No schema change; invalid/missing percentage remains null in SQLite and CSV.


## 2026-11-23 — Exact battery percentage metadata status

- The dashboard now identifies the exact percentage-input failure: ACTION_BATTERY_CHANGED unavailable, EXTRA_LEVEL missing, EXTRA_SCALE missing, invalid level, non-positive scale or level greater than scale.
- The recorder writes an event only when this metadata status changes, preserving the reason in exported event history without producing a row every sample.
- A null/invalid level-scale pair remains null; no assumed 100-point scale is used.
