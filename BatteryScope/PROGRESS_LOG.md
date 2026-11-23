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
