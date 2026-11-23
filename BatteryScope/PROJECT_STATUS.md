# BatteryScope project status and engineering handoff

Last audited source snapshot: 2026-11-23  
Active branch: batteryscope  
Current app version: 1.11  
Current version code: 12  
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
- Source was updated and inspected on 2026-11-23; the cumulative code diff from the last known v1.8 checkpoint spans the app files and the recorder/AIDL/manifest work.
- The previous v1.11 build for the pre-audit source snapshot (commit db9118677afc799ba78f1c651b73ee52cbc0db18, workflow run #147 / ID 45140341332) passed the Android 35 debug build and aapt package/metadata verification. It is superseded by the subsequent audit corrections.
- The post-audit source build is running through the BatteryScope GitHub Actions workflow; use the exact final commit/run and artifact after it completes.
- No APK has been installed or runtime-tested on the physical RMX2161 during this audit.
- No actual RMX2161 VBUS/IBUS source path or current limit writeability is verified without a handset audit.
- No automated Android unit/instrumentation test suite currently exists; CI compiles/packages the app but does not exercise service lifecycle or measurement arithmetic on a device.

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
