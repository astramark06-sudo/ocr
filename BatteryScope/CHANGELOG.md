# BatteryScope changelog

All versions are debug APK development builds unless a signed release is explicitly documented.

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
