# BatteryScope — persistent development handoff

Last updated: 2026-11-23  
Repository: https://github.com/astramark06-sudo/test  
Active branch: `batteryscope`  
Target device: Realme Narzo 20 Pro (RMX2161), Android 12, non-rooted  
Current source version: 1.14 / versionCode 15  
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

## Current implementation

### Live telemetry
- Android battery percentage, status, plugged source, voltage, temperature, health enum, technology.
- BatteryManager instantaneous and average battery-side current, charge counter and energy counter.
- Optional kernel fuel-gauge charge_full/design-capacity ratio and cycle_count when readable, kept separate from empirical capacity estimate.
- Charger-advertised maximum current/voltage, battery-present state, invalid-charger flag and optional cycle-count metadata when Android/OEM extras expose them.
- Battery-side power estimate only when voltage/current are plausible; never label this as USB input power.
- Best-effort charger-advertised maximum current/voltage, battery-present, invalid-charger and optional cycle-count extras.
- Read-only inventory of power_supply, Type-C, USB-PD, USB role, thermal, hwmon and selected non-serial USB descriptor attributes.
- Vendor/Oplus/VOOC fields are raw state indicators only; no proprietary handshake or protocol claims.
- Per-root and per-file visibility/read/list diagnostics; distinguishes definitely unavailable from permission denied and absent-or-SELinux-hidden.
- Usage Access status is displayed for foreground-app correlation.
- Live dashboard file traversal and BatteryManager reads run on a dedicated HandlerThread.
- Recorder event writes triggered by service/broadcast callbacks are queued on the recorder HandlerThread; database close is queued after pending work to avoid main-thread SQLite stalls and dropped events.
- BOOT_COMPLETED no longer opens SQLite on the broadcast main thread; automatic-resume failures are saved in preferences and surfaced in diagnostics.
- Duplicate MainActivity lifecycle override found during review was removed.

### Recording and analytics
- User-started foreground service; 30-second BatteryManager sample target; 120-second kernel-tree inventory.
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
- App foreground time with coincident SOC movement. This is correlation, not per-app electrical energy/current.
- CSV export includes chronological samples/events, boot metadata and raw hardware tree snapshots.
- SQLite schema v4 adds charger maximum current/voltage, battery-present, invalid-charger and optional cycle count without deleting v1/v2/v3 history.

### Capacity estimate
- Estimates full-charge capacity from charge-counter movement versus SOC movement across continuous sessions.
- Requires known boot count, same-boot continuity, valid counter and SOC values, at least 12 percentage points, direction agreement and plausibility checks.
- Median of sessions, spread and qualitative confidence.
- 4,500 mAh original design-capacity baseline for RMX2161.
- No forced 0% cycle. If the fuel gauge does not expose a usable charge counter, capacity remains unavailable.

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
