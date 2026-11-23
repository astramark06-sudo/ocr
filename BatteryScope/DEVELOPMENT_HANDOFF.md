# BatteryScope — persistent development handoff

Last updated: 2026-11-23  
Repository: https://github.com/astramark06-sudo/test  
Active branch: `batteryscope`  
Target device: Realme Narzo 20 Pro (RMX2161), Android 12, non-rooted  
Current source version: 1.15 / versionCode 16  
Package: `com.batteryscope`

## Non-negotiable architecture

- Standalone Android app only.
- No Shizuku, root, mandatory ADB, companion app or second service application.
- Standard public Android APIs and best-effort read-only ordinary-app filesystem access.
- Never write sysfs, PMIC, I2C or proprietary charging controls.
- Never invent a measurement. Persist raw evidence, source, timestamp and unavailable/error status.
- Keep the UI responsive: filesystem traversal, telemetry collection, database summaries and exports run off the main thread.
- Preserve local history with non-destructive SQLite migrations.
- Save every coherent milestone and update this handoff/progress log in this GitHub branch so a later chat can resume.
- Monitoring is automatic after first app launch; no Start/Stop UI or service stop action. Resume is attempted on boot and app replacement.

## Current implementation

### Live telemetry
- Android battery percentage, status, plugged source, voltage, temperature, health enum, technology. Percentage is computed only from a valid Android EXTRA_LEVEL/EXTRA_SCALE pair; no default scale is invented.
- BatteryManager instantaneous and average battery-side current, charge counter and energy counter.
- Optional kernel fuel-gauge charge_full/design-capacity ratio and cycle_count when readable, kept separate from empirical capacity estimate.
- Charger-advertised maximum current/voltage, battery-present state, invalid-charger flag and optional cycle-count metadata when Android/OEM extras expose them.
- Battery-side power estimate only when voltage/current are plausible; never label this as USB input power.
- Best-effort charger-advertised maximum current/voltage, battery-present, invalid-charger and optional cycle-count extras.
- Read-only inventory of power_supply, Type-C, USB-PD, USB role, thermal, hwmon and selected non-serial USB descriptor attributes.
- Vendor/Oplus/VOOC fields are raw state indicators only; no proprietary handshake or protocol claims.
- Per-root and per-file visibility/read/list diagnostics; distinguishes definitely unavailable from permission denied and absent-or-SELinux-hidden.
- BatteryManager diagnostics distinguish unavailable sentinels, SecurityException and other exception classes for current, average current, charge counter and energy counter; missing battery broadcast/level/voltage/temperature extras are reported separately. Version 1.15 also validates current polarity and voltage range, records quality transitions, and excludes invalid readings from battery-side wattage.
- Usage Access status is displayed for foreground-app correlation.
- Live dashboard file traversal and BatteryManager reads run on a dedicated HandlerThread.
- Recorder event writes triggered by service/broadcast callbacks are queued on the recorder HandlerThread; database close is queued after pending work to avoid main-thread SQLite stalls and dropped events.
- BOOT_COMPLETED no longer opens SQLite on the broadcast main thread; automatic-resume failures are saved in preferences and surfaced in diagnostics.
- Duplicate MainActivity lifecycle override found during review was removed.

### Recording and analytics
- Automatically requested foreground service on Activity resume; boot and package-replacement receivers request recovery; 30-second BatteryManager sample target; 120-second kernel-tree inventory. The latest lifecycle edits passed the v1.14 CI build; v1.15 telemetry corrections require their own CI and device validation.
- Selectable continuous mode (partial CPU wake lock for screen-off continuity) or low-impact mode (no permanent wake lock; screen-off gaps are expected and measured).
- Local SQLite sample/event history, monotonic elapsed time and boot count.
- Screen-on/off and power-connected/disconnected/battery-state events.
- Optional UsageStats foreground-package correlation when user grants Usage Access.
- 24-hour, 7-day, 30-day, 90-day, 365-day and all-time summaries.
- Battery-side charge-counter mA rates and screen-state-specific historical charge/discharge projections.
- Percentage-point/hour and charge-counter-derived mA charge/discharge rates.
- Screen-on versus screen-off charge/discharge rates.
- Battery decline while charger remains connected.
- Recent charge/discharge session durations, SOC thresholds and historical time-to-full/time-to-empty estimates.
- Each recent session also separates display-on and display-off SOC rates, signed battery-side mA rates when charge-counter deltas are valid, and separate time-to-100% / time-to-0% forecasts. Display state is only a proxy for active use; foreground-app correlation is separately labelled approximate.
- App foreground time with coincident SOC movement and, when valid, charge-counter mAh movement. Attribution requires the same foreground package at both sample endpoints with the display on. This is correlation, not per-app electrical energy/current or causal attribution.
- CSV export includes chronological samples/events, boot metadata and raw hardware tree snapshots.
- SQLite schema v4 adds charger maximum current/voltage, battery-present, invalid-charger and optional cycle count without deleting v1/v2/v3 history.

### Capacity estimate
- Estimates full-charge capacity from charge-counter movement versus SOC movement across continuous sessions.
- Requires known boot count, same-boot continuity, valid counter and SOC values, at least 12 percentage points, direction agreement and plausibility checks.
- Median of sessions, spread and qualitative confidence.
- 4,500 mAh original design-capacity baseline for RMX2161.
- No forced 0% cycle. If the fuel gauge does not expose a usable charge counter, capacity remains unavailable.

## Device-wide monitoring expansion

Read `DEVICE_TELEMETRY_ARCHITECTURE.md` before implementing new collectors. It defines the target product, capability matrix, public API versus privileged boundary, device-specific unknowns, data-quality contract and phased acceptance gates.

Important newly identified sources:
- UsageStatsManager: foreground app transitions, usage aggregates and screen-interactive event aggregates. Detailed events are only retained for a few days, so regular import and deduplication are required.
- NetworkStatsManager: Android-maintained mobile/Wi-Fi byte buckets and per-UID traffic where access permits. Network bytes are not battery energy.
- StorageStatsManager: package/UID storage totals, queried on a worker because operations may take seconds.
- PowerManager: thermal status and thermal headroom; headroom is an estimate.
- ActivityManager.MemoryInfo: system RAM pressure.
- SensorManager: actual runtime sensor inventory; product-sheet sensor claims are not a substitute for enumeration.
- CPU/GPU/thermal kernel paths: best-effort read-only discovery only.

BatteryStats per-app power and detailed system battery history remain privileged. Ordinary-app code must not pretend UsageStats or network traffic provides per-app mAh. The exact RMX2161 firmware capability census remains pending.

## Important measurement limitations

- Battery-side current is not USB adapter current.
- Android max-charging-current/voltage extras are advertised maximums, not live input measurements.
- Live USB V/A/W requires a same-supply, semantically verified kernel voltage/current pair. A candidate is not confirmed until validated on the actual handset.
- PD PDO values indicate advertised capabilities, not necessarily the selected active contract.
- Android 12 may not expose battery cycle count or health capacity.
- Per-app battery energy is not publicly exposed to an ordinary third-party app on this device; UsageStats foreground correlation must remain labelled as such.
- Continuous CPU wake lock improves screen-off sampling but adds BatteryScope's own drain. Low-impact mode avoids the permanent wake lock but may miss screen-off samples. The notification and diagnostics disclose the selected mode. Measure comparisons with consistent recorder settings.
- Force-stop, shutdown, OEM process restrictions and Android power management can create sampling gaps. Report observed gaps; never fabricate continuity.

## Next engineering priorities

1. Verify the dedicated Android GitHub Actions workflow on the latest batteryscope commit; repair every compile/test failure before APK delivery.
2. Inspect the exact workflow artifact, APK metadata, checksum and signing identity; do not assume update compatibility with older debug builds.
3. Continue auditing MainActivity and recorder for lifecycle races, main-thread I/O, long-running history queries and large-database performance.
4. Recheck schema-v4 migrations from v1/v2/v3 and verify all CSV header/sample/event rows align at 28 fields.
5. Add focused JVM tests for plugged-in drain, screen-state projections, usage correlation boundaries and database/export invariants where feasible.
6. Improve diagnostics where exact unavailable reasons can be distinguished from absent-or-SELinux-hidden paths.
7. Inspect the RMX2161's actual exposed attributes after installation; validate USB input V/I semantics, VOOC state, thermal zones and charge-counter availability from a diagnostic export.
8. Preserve signing-key continuity before releasing an APK intended to update an installed version. Do not advise uninstalling without protecting local history.
9. Only mark hardware validation complete after real handset evidence exists.

## Resume instructions for a future ChatGPT session

Read this file first, then `PROGRESS_LOG.md`, `README.md`, `AUDIT.md`, `PROJECT_STATUS.md`, `AGENTS.md`, the Gradle workflow and current branch source. Continue from the latest branch commit. Do not ask the user to restate the phone model, app name, package or no-Shizuku constraint. Do not claim a build or APK is verified until the actual workflow result/artifact has been inspected.


## Latest user-export findings (2026-11-23)

Review of `BatteryScope_History_20261123_214733.csv`: 22 samples / 51 events across 13m35.654s. SOC 83→80%; charge counter 3,735,000→3,600,000 µAh; temperature 33.8–35.4 °C. Every sample says NOT_CHARGING / NONE; therefore no charging-watt validation occurred.

Critical: voltage export is 3–4 in a column named `battery_voltage_mv`; source expects 2,500–5,000 mV and currently rejects these values for live power calculation. Current export is 14–674 in `battery_current_ua` (mean 201.9), but the charge-counter endpoint delta implies roughly 595.8 mA over the whole session; counter values move in coarse 45,000 µAh steps. Do not assume whether the OEM API returns volts/millivolts or mA/µA from this file alone. Do not apply a heuristic multiplier to historical data. Preserve raw readings and add explicit raw-unit/device validation before trusting battery-side watts.

Other gaps: average current, energy counter, cycle count, charger max values and USB V/I candidates were null; Usage Access was off in 20/22 samples; one sample interval was 154.741s; kernel roots were denied or absent/hidden and no fields were captured. This is a short recording, not proof of 24/7 continuity. Next: collect one unplugged discharge and one charging capture with Usage Access enabled, compare BatteryManager raw values to same-supply battery sysfs values if accessible, and independently verify voltage/current units before changing scaling. Never claim USB-input watts from battery-side values.


## 2026-11-23 forensic audit and v1.15 changes

Read [FORENSIC_AUDIT_2026-11-23.md](FORENSIC_AUDIT_2026-11-23.md) for the exact APK/artifact hashes, branch/build commit comparison, 55-sample RMX2161 export findings, source defects and verification limits. The new BatteryMeasurementQuality helper refuses to interpret current with a sign inconsistent with Android's documented convention, refuses voltage outside 2,500–5,000 mV, and returns no battery-side watts unless both inputs pass. Raw values remain unchanged. Quality transitions are written to the event history. The live battery summary is shorter and foreground UsageStats event timestamps now use the source event time.

Version is 1.15 / versionCode 16; applicationId remains com.batteryscope; database remains schema v4. Eight new JVM tests were added. Do not install a new CI debug APK over an existing installation until its signing-certificate digest is compared.

Outstanding: v1.15 CI build, physical charging/discharging tests on RMX2161, 24-hour screen-off continuity test, and implementation of the remaining device-wide collectors in DEVICE_TELEMETRY_ARCHITECTURE.md.
