# BatteryScope project status and handoff

Last source organization pass: 2026-11-21
Active working branch: `batteryscope`
Current app version: `1.8`
Current version code: `9`
Application ID: `com.batteryscope`
Target device: Realme Narzo 20 Pro (RMX2161), Android 12, non-rooted

## Project layout
- `app/src/main/java/com/batteryscope/MainActivity.kt`: primary UI, Android battery telemetry, logging/export flow and Shizuku client.
- `app/src/main/java/com/batteryscope/ShizukuDiagnosticsService.kt`: Shizuku UserService, read-only power-path audit/live sampling and tightly scoped Oplus control.
- `app/src/main/aidl/com/batteryscope/IBatteryDiagnosticsService.aidl`: Binder contract; keep client and service signatures synchronized.
- `app/src/main/res/`: adaptive launcher icon resources.
- `README.md`: user/developer-facing capabilities and setup.
- `AUDIT.md`: evidence, measurement units, kernel reference findings, guardrails and limitations.
- `CHANGELOG.md`: version history.
- `RELEASE_CHECKLIST.md`: release verification gates.
- `AGENTS.md`: project-specific maintenance rules.

## Current source changes recorded
- Android voltage outside 2500–5000 mV is flagged invalid and excluded from the battery-side power estimate.
- Charge counter is labelled as remaining-charge estimate, not full-charge capacity.
- Android battery health is labelled as framework health, not capacity health.
- CSV stop/export uses Android's document picker; an internal copy remains if cancelled or failed.
- v1.8 expands ordinary-app and Shizuku sysfs inventory across power-supply, Type-C, USB-PD, USB-role, thermal and hwmon class trees, and adds thermalservice/power dumpsys output.
- Adaptive launcher icon is declared in the manifest, including round variant.
- APK workflow checks application ID and launcher resources, builds the debug APK, inspects package metadata with aapt, computes SHA-256 and uploads APK plus build metadata.

## Verification state — do not overclaim
- Source files and documentation were read back from the `batteryscope` branch after updates.
- Verified source commit: `700e6ac63be78f65a2d08b45bef367b79badef03`.
- GitHub Actions BatteryScope build run #91 (ID `44992143220`) passed, including APK package/metadata verification and artifact upload: https://github.com/astramark06-sudo/test/actions/runs/44992143220
- Verified artifact: `BatteryScope-1.8-debug-700e6ac63be78f65a2d08b45bef367b79badef03` (artifact ID `14742337632`).
- Artifact ZIP: 894,165 bytes; entries `BatteryScope-debug.apk` and `BUILD-INFO.txt`; ZIP integrity check passed.
- APK: 901,602 bytes; application ID `com.batteryscope`; version `1.8`; versionCode `9`; SHA-256 `f29947e6a93e231767eac3e343266f5bee87ca625bdaa3617042d4fc882db260`.
- Artifact ZIP SHA-256: `6dd1bc94bc9f7804c842190ec8a9e390ec561325df98044b798ad398ea593656`.
- CI/package verification and artifact integrity passed. The APK has not been installed or runtime-tested on the physical RMX2161.
- No physical RMX2161 runtime test has been performed for the 1.8 changes.
- The source inventory is broader but does not prove the retail ROM exposes VBUS/IBUS. The actual handset audit is still required to discover readable nodes and values.
- v1.8 remains activity-bound for its CSV sampler and Shizuku live poll. It does not yet provide 24-hour background recording; leaving the app screen can still stop continuous logging/polling.
- There is no verified full-charge-capacity (FCC) reading yet. BatteryManager CHARGE_COUNTER is not FCC.
- The diagnostics text report still saves to app-specific storage; only CSV has user-selected document export.
- The repository does not yet have a dedicated automated Android unit/instrumentation test suite.

## Next actions
1. Install/update the 1.8 APK on RMX2161 and run the expanded Shizuku audit while unplugged, charging, and during active phone use.
2. Export the full diagnostic report and inspect which charger-side voltage/current/power nodes actually exist and are readable.
3. Implement the separate persistent 24-hour recorder; the current CSV and Shizuku samplers remain activity-bound.
4. Add tests for pure conversion/validation logic without broad UI refactoring.
5. On the handset, verify launcher icon, install-over-update behavior, document picker, live telemetry and Shizuku flow.
6. Investigate FCC/design-capacity exposure using actual RMX2161 runtime data; do not infer it from charge counter divided by percentage.
7. Consider document-picker export for diagnostic text report as a separate scoped improvement.

## Continuation rule
Continue this existing project and branch. Read `AGENTS.md`, this file, `AUDIT.md` and the relevant source before making changes. Do not recreate BatteryScope or restructure the unrelated Python project.
