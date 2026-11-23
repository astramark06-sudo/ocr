# BatteryScope changelog

## 1.9 — expanded measurement diagnosis
- Ordinary-app read-only discovery now enumerates power-supply, Type-C, USB-PD, dual-role USB, USB-role, thermal and hwmon class attributes, preserving raw values and explicit read errors.
- Added automatic measurement-access diagnosis that separates live same-supply voltage/current candidates from configured limits and reports missing/denied USB interfaces.
- No sysfs writes are performed by the expanded automatic scan; Shizuku remains optional for shell-UID diagnostics.
- Version code: 10.

All versions are debug APK development builds unless a signed release is explicitly documented.

## 1.8 — expanded power-path discovery
- Shizuku full audit now inventories readable attributes under power-supply, Type-C, USB-PD, USB-role, thermal-zone and hardware-monitor class trees rather than relying only on a fixed property-name whitelist.
- Live Shizuku snapshots include discovered attributes from these class trees, subject to explicit depth and output caps.
- Added Android thermal-service and power-service dumpsys sections to the full audit.
- The ordinary app process now inventories all direct attributes exposed under each power-supply class entry; unreadable and failed reads remain explicitly labelled.
- Preserved raw values, source paths and the read-only charging-control boundary.
- Version code: 9.

## 1.7 — launcher identity
- Added Android adaptive launcher icon and round-icon resource.
- Configured manifest launcher icon references.
- Preserved package identity `com.batteryscope`.
- Version code: 8.

## 1.6 — telemetry labelling and CSV export
- Marked Android battery voltage outside 2500–5000 mV as invalid in the UI and excluded it from the battery-side power estimate.
- Clarified that charge counter is an Android remaining-charge estimate, not full-charge capacity.
- Clarified that Android battery health is framework status, not capacity health.
- Added Android Storage Access Framework document-picker export for CSV files. The app-specific copy remains if export is cancelled or fails.
- Version code: 7.

## 1.5 — Shizuku diagnostics baseline
- Included Android battery telemetry, sysfs audit, Shizuku diagnostic service, live power-path sampling and conditional Oplus `cool_down` control.
- Added CSV session logging and diagnostic text report.
- Version code: 6.

## Release notes
- This changelog records the verified source changes represented by the repository history; it does not imply that every version was installed or device-tested.
- Consult `AUDIT.md` for measurement provenance, safety guards and known limitations.
