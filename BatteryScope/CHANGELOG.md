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


## 1.10 — persistent telemetry recorder (development milestone)
- Added a user-started foreground service so sampling is no longer tied to MainActivity visibility.
- Samples Android battery status, percentage, voltage, temperature, current, average current, charge counter, energy counter, health, plug type and technology every 30 seconds.
- Captures readable power-supply sysfs attributes on a slower two-minute interval; preserves raw values and does not label vendor states or configured limits as measured input power.
- Records power connection/disconnection, screen on/off, battery-low and foreground-app transition events.
- Added optional Android Usage Access tracking for the foreground package, screen state and sampled foreground duration.
- Added local SQLite raw history, 24-hour/7-day/30-day summary windows and full-history CSV export.
- Recorder is user-started and displays an ongoing notification. START_STICKY supports service recreation, but force-stop, power-off, OEM process restrictions and denied permissions can still interrupt recording.
- App-level drain is not measured electrically; foreground-app intervals are correlation data only.
- Version code: 11.


- Added session analytics: charge/discharge percentage rates, charge-counter change where available, screen-on versus screen-off rates, recent-session threshold times, and foreground-app-correlated discharge.
- Extended rolling history summaries to 24 hours, 7 days, 30 days and 90 days; raw SQLite history remains available for export.
- Added optional Shizuku UserService sampling while the foreground recorder is active, after the user has granted Shizuku permission; periodic privileged snapshots are retained with the sample data.
- Added an explicit partial wake lock while user-requested continuous recording is active to reduce CPU-suspend gaps. This increases BatteryScope's own power consumption and is recorded in the event log.
