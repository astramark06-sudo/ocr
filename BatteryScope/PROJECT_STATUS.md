# BatteryScope project status and engineering handoff

Last audited source snapshot: 2026-11-23  
Active branch: batteryscope  
Current app version: 1.12  
Current version code: 13  
Application ID / namespace: com.batteryscope  
Target handset: Realme Narzo 20 Pro (RMX2161), Android 12, non-rooted

## Source layout
- app/src/main/java/com/batteryscope/MainActivity.kt — UI, Android telemetry, measurement diagnosis, interactive Shizuku audit, live panel, guarded control, recorder controls and CSV picker.
- app/src/main/java/com/batteryscope/BatteryRecorderService.kt — foreground recorder, worker thread, system event capture, ordinary-app sysfs inventory, optional recorder-owned Shizuku binding, SQLite schema/history analytics and CSV writer.
- app/src/main/java/com/batteryscope/BatteryRecorderBootReceiver.kt — user-intent-based BOOT_COMPLETED resume attempt.
- app/src/main/java/com/batteryscope/ShizukuDiagnosticsService.kt — read-only Shizuku audit/snapshot and exact guarded Oplus cool_down setter.
- app/src/main/aidl/com/batteryscope/IBatteryDiagnosticsService.aidl — Shizuku Binder contract.
- app/src/main/AndroidManifest.xml — permissions, Shizuku provider, launcher, boot receiver and specialUse foreground service declaration.
- app/src/main/res — adaptive launcher resources.
- app/build.gradle.kts — application identity, version and Shizuku dependencies.
- AUDIT.md — technical findings, evidence provenance, limitations and control guardrails.
- README.md — user setup, telemetry semantics and recorder operation.
- CHANGELOG.md — version history.
- RELEASE_CHECKLIST.md — build and device-validation gate.
- AGENTS.md — project-specific maintenance invariants.

## Current capability
The app exposes Android BatteryManager battery-side measurements, read-only class-tree sysfs inventory and optional Shizuku ADB-shell UID snapshots. The background recorder stores 30-second framework samples and 120-second class-tree samples in a local SQLite database; optional Shizuku path snapshots are stored every 120 seconds and focused BatteryStats output every 15 minutes. History summaries are streamed from SQLite for 24 hours, 7 days, 30 days, 90 days, 365 days and all time. CSV export includes wall-clock and monotonic timestamps plus chronological event/sample rows.

The persistent recorder is a best-effort Android foreground service, not a hard real-time or guaranteed 24-hour logger. It uses a partial wake lock, which increases BatteryScope's own energy use. It can be interrupted by force-stop, powered-off time, service failure, permission revocation, Shizuku shutdown or OEM restrictions. Boot resume is attempted only when the user had left recording enabled.

## Measurement boundaries
- BatteryManager current/voltage and derived watts are battery-side, not USB input power.
- USB V/I candidates require an external supply whose name/type identifies USB, and must still be validated against the RMX2161 driver semantics.
- PDO records are advertised capabilities, not proof of the selected active contract.
- Oplus VOOC/SuperDart flags are driver state, not protocol packet capture.
- Foreground-app battery decline is correlation, not direct per-app current or energy.
- BatteryManager CHARGE_COUNTER is remaining-charge estimate, not full-charge capacity.
- The only write path remains the narrowly gated Oplus cool_down property, with model/path/range/charging/write/confirmation/read-back checks.

## Verification state
- Current source revision before the v1.12 patch: 4db9f0a2a914d5c80c14a94a1e3a5c41368e8fd2 (v1.11 / versionCode 12).
- The v1.11 source had a verified GitHub Actions debug build: run #162 / ID 45146583835. Android 35 compilation and aapt package/metadata verification passed.
- The v1.11 artifact archive digest was sha256:9e456d07ffdb3cab2b9a77c071648d05582111fb9ee1d38948501fd2d266f33d. This is the ZIP digest, not the APK's internal SHA-256.
- The source audit found a CSV record-terminator defect, inconsistent wall-clock use in session screen-on duration, and a reboot-boundary ambiguity when post-reboot uptime exceeded the previous uptime. Version 1.12 centralizes CSV encoding, adds JVM unit tests, persists Android BOOT_COUNT with a non-destructive SQLite v1-to-v2 migration, and uses monotonic intervals within a boot and wall-clock intervals across boots.
- CI now runs testDebugUnitTest before assembleDebug. The v1.12 CI result and artifact must be checked against the final commit before distribution.
- No APK has been installed or runtime-tested on the physical RMX2161.
- No actual RMX2161 VBUS/IBUS source path or current-limit writeability is verified without a handset audit.
- No Android instrumentation/device test suite exists yet; JVM tests cover CSV row encoding only.

## Remaining device validation
1. Install the final APK over the earlier package and confirm application ID/update compatibility.
2. Start recording, turn the screen off, use the phone while charging, unplug it, reconnect it, and keep a multi-hour run.
3. Verify notification, wake-lock behavior, battery usage impact, screen-off sample intervals, and Realme background-management settings.
4. Test reboot resume with recording left enabled and verify the gap across powered-off time is not counted as observed recording.
5. Grant Usage Access and verify foreground package transitions and their correlation labels.
6. Start Shizuku, authorize BatteryScope, and verify service-owned periodic snapshots continue after MainActivity is closed.
7. Inspect sysfs source names/units and confirm whether any USB-specific VBUS/IBUS pair exists. Do not infer physical charger power from battery-side readings.
8. Confirm that the cool_down control stays disabled unless all exact-node, model, range, charging, access and read-back guards pass.
9. Export CSV, inspect timestamp ordering, elapsedRealtime continuity and raw JSON, then confirm internal app-private copy remains.
