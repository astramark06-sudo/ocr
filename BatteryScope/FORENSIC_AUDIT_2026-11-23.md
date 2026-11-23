# BatteryScope forensic audit — 2026-11-23

## Scope and evidence

This audit examined the complete GitHub recursive tree returned for branch `batteryscope` (151 repository entries; tree response was not truncated), all BatteryScope source/configuration files in that tree, the Android workflow run and its uploaded artifact, the APK extracted from that artifact, and the user's 2026-11-23 extended CSV export.

Repository: `astramark06-sudo/test`  
Branch at audit start: `batteryscope`  
Branch head at audit start: `5ecd7523227ae67adabf9ed2905ecc86bb65f349`  
APK build commit: `1e1660e51bcae0912bb84381954467471e010e18`

The branch was five commits ahead of the APK build commit. GitHub's compare endpoint showed that those five commits changed documentation only; the Android source/configuration files were unchanged. The APK therefore represented the Android source present at that branch head before this audit's corrective changes.

## APK and artifact integrity

GitHub Actions run: [45211411009](https://github.com/astramark06-sudo/test/actions/runs/45211411009)  
Job: `137820951064` — Build, unit test and inspect APK  
Workflow conclusion: success. All listed steps, including JVM tests, APK assembly, metadata/checksum verification and artifact upload, succeeded.

Downloaded workflow artifact: `BatteryScope-debug-1e1660e51bcae0912bb84381954467471e010e18`  
Artifact ID: `14835568932`  
Artifact ZIP size: 921,318 bytes  
Artifact ZIP SHA-256: `dab39b841a5e961e8fcfaf2ef358836fdd96f21649ca222a173ad3df94cda3c3`

The ZIP integrity test passed. It contains the APK, five JVM test XML reports, test HTML assets, package metadata, APK SHA-256 and APK signing report.

APK size: 913,390 bytes  
APK SHA-256: `ea9a9a1dac3c8dbe291534cda11f2e5173d7ae64073bb8d2140e786a957b036b`  
Package: `com.batteryscope`  
Version: 1.14 / versionCode 15  
minSdk: 26; targetSdk: 35; compileSdk: 35  
APK signing report: v2 signature verified by the workflow; one Android Debug signer. Certificate SHA-256: `3c75edb5262b17164453949bb90113e133edc2daeceb10846cd233d2be8194b5`.

The APK ZIP container passed CRC integrity validation and contains 16 entries, including three DEX files, the compiled manifest, resources and Kotlin built-ins. The APK has no native `.so` library entries. The build report confirms the package/version metadata and signing result. This environment did not contain Android SDK `apksigner`, `aapt`, `apkanalyzer` or JADX, so the local audit did not independently re-run Android's v2 signature verifier or decompile every DEX instruction. The workflow's recorded verification is the signature evidence.

This APK is debug-signed. Its certificate is not a production release identity. Do not assume a later CI debug APK will share this certificate; compare the new certificate digest before attempting an in-place update.

## Extended handset CSV

File: `BatteryScope_History_20261123_222036.csv`  
Rows: 177 total — 55 SAMPLE records and 122 EVENT records  
Recording duration: approximately 46 minutes 23 seconds  
Battery level: 83% to 74%  
Charge counter: 3,735,000 to 3,330,000 µAh (decrease of 405 mAh)  
Temperature: 31.8–35.4 °C  
Battery status: 52 NOT_CHARGING samples and 3 CHARGING samples  
Power source: 52 NONE samples and 3 AC samples

The three charging samples occur after Android reports a transition to CHARGING / AC. Their raw current values are -551, -554 and -560 in the `battery_current_ua` column. During the unplugged NOT_CHARGING portion, raw current values are positive (14–674). Android documents `BATTERY_PROPERTY_CURRENT_NOW` as microamperes, positive into the battery and negative out of it. The observed sign pattern conflicts with that contract and with the recorded charge direction. It is not safe to invert the sign or multiply the magnitude without independent RMX2161 validation.

The `battery_voltage_mv` column contains only 3 or 4. Those values are outside the documented millivolt range expected by the app's 2,500–5,000 mV plausibility check. They are preserved as raw readings and cannot be used for a voltage/current watt calculation.

The charge counter declines by 405 mAh over the recording, corresponding to a coarse endpoint-average discharge of approximately 524 mA. The counter is visibly quantized in 45,000 µAh steps, so it cannot validate instantaneous current at each 30-second sample. It is not an independent laboratory measurement.

Average-current property, energy counter, cycle count, charger-advertised maxima and USB input V/I candidates are absent in this export. No USB input wattage can be established.

Sampling target is 30 seconds, but there are seven gaps longer than 90 seconds; the longest is 307.790 seconds. Four sampling-mode changes are recorded. The export proves that samples/events were persisted and exported during this short run; it does not prove uninterrupted 24/7 operation.

## Source findings

### Correct or bounded behaviour observed

- Package identity is `com.batteryscope`; source uses Java/Kotlin JVM 17, Android compile SDK 35, minSdk 26 and targetSdk 35.
- The app is a standalone ordinary Android app. The manifest has foreground-service, special-use foreground-service, notification, Usage Access, wake-lock and boot-completed permissions. No Shizuku, root, ADB or companion-app integration is present in the inspected BatteryScope source/manifest/dependency files.
- The foreground recorder uses `START_STICKY`, an ongoing notification, a worker HandlerThread, a 30-second target sampling interval and an optional partial CPU wake lock.
- Boot and package-replacement receivers request recorder recovery.
- SQLite schema v4 stores raw samples and events without an automatic retention cap. The current corrective change does not alter the schema, package identity or existing rows.
- CSV export merges samples and events chronologically and retains raw values; unavailable values are represented as empty fields rather than fabricated zeroes.
- Kernel inspection is read-only and bounded. Permission/SELinux denials are reported; no sysfs writes are performed.
- USB input power is not inferred from battery-side current. The source requires a same-supply USB voltage/current candidate and still labels its driver semantics as requiring handset validation.

### Defects or limitations confirmed

1. **Current direction was not validated.** The dashboard converted every BatteryManager current value from µA to mA and could display it without checking whether its sign agreed with the reported battery state. The user's RMX2161 export shows a persistent polarity conflict.
2. **Voltage quality was only partially handled.** The dashboard rejected values outside 2,500–5,000 mV for power calculation, but did not make the raw malformed value and reason sufficiently prominent in the main panel.
3. **The live dashboard was overly verbose.** Battery essentials were mixed with many diagnostic fields, making the important current/power result difficult to locate.
4. **UsageStats event times were stamped at polling time.** Foreground start/end events used the recorder's current wall clock instead of the source `UsageEvents.Event.timeStamp`, which collapsed event chronology to the poll time.
5. **The sample cadence is a target, not a guarantee.** The CSV shows long gaps despite the recorder's active state and selected wake-lock mode. Android/Realme lifecycle restrictions and force-stop/shutdown still apply.
6. **Device-wide collectors are not implemented.** The inspected BatteryScope source does not implement NetworkStatsManager, StorageStatsManager, SensorManager inventory, ActivityManager memory telemetry, installed-app inventory/change tracking, or CPU/GPU utilization collectors. Those remain architecture/roadmap items, not current app capabilities.
7. **Privileged per-app battery energy is not available to an ordinary app.** BatteryStats per-UID energy requires privileged access; foreground time and coincident battery-counter movement are correlation, not direct app energy.
8. **Automated tests are mostly pure arithmetic/CSV tests.** The previous APK's five test classes contained 14 JVM test cases. There were no instrumentation tests or physical RMX2161 tests in the artifact.

## Corrective changes made in source for version 1.15

- Added `BatteryMeasurementQuality` to validate BatteryManager current direction against Android's documented sign convention and validate battery voltage against the expected mV range.
- The code does not invert current, invent a scale multiplier, or derive charger-input power from battery-side readings.
- Battery-side wattage is now calculated only when both voltage and current pass validation. Invalid values remain visible as raw API readings with an explicit quality reason.
- The main Battery Telemetry panel is simplified to battery level, state/source, voltage, current, battery-side power, charger-input availability and temperature. Extended diagnostics remain available in the diagnostics panel.
- The recorder logs current/voltage quality transitions as events without changing the database schema.
- UsageStats foreground start/end events now retain their source event timestamps rather than poll timestamps.
- Added eight JVM tests for polarity validation, ambiguous states, voltage range and guarded battery-side power calculation.
- Version changed to 1.15 / versionCode 16. Package ID remains `com.batteryscope`; SQLite schema remains v4.

## v1.15 build and binary verification

The authoritative Android workflow [45223861385](https://github.com/astramark06-sudo/test/actions/runs/45223861385) completed successfully for app source commit `480a567cd7c90f552976dc60472fa6ef11a5e126`.

- Test result: 22 tests passed; 0 failures, 0 errors, 0 skipped.
- Workflow artifact ID: `14840939491`; artifact name: `BatteryScope-debug-480a567cd7c90f552976dc60472fa6ef11a5e126`.
- Artifact ZIP size: 924,594 bytes; SHA-256: `2c0b385d7416471e7c40817a5d2fe34aa05a2eaaf624307bfeebf001bb31195c`.
- APK size: 914,326 bytes; SHA-256: `0c8f37eb4777b9ece9a1241a85e4805bab06dd5fd4b0bdde339f748c384b32ac`.
- Metadata: `com.batteryscope`, version 1.15 / versionCode 16, minSdk 26, targetSdk 35, compileSdk 35; APK is debuggable.
- CI verified APK Signature Scheme v2. Signer certificate SHA-256: `fe463192e3a055244ed627fbd8a8907f535263166e619c5bca686924b15094c2`.

The APK ZIP CRC check passed. All three DEX headers passed file-size, SHA-1 and Adler-32 integrity checks; the v1.15 DEX contains `BatteryMeasurementQuality`, the current/voltage quality event names and the source-timestamp event writer. The APK contains 16 ZIP entries. Local Android `apksigner`/JADX tools were not available, so signature verification is based on the workflow's `apksigner` report and the DEX audit is structural, not a full instruction-by-instruction decompilation.

The repository had two push-triggered APK workflows. They produced APKs with identical uncompressed ZIP entries but different APK hashes/signatures because each independent runner used a different debug signing key. The older `batteryscope-build.yml` workflow did not perform signature verification and was removed in commit `831469704f50e6c5fff28974d4b2d8027156ee68`. `batteryscope-android.yml` is now the single authoritative build/verification workflow.

The v1.14 debug certificate was `3c75edb5262b17164453949bb90113e133edc2daeceb10846cd233d2be8194b5`; the v1.15 certificate is `fe463192e3a055244ed627fbd8a8907f535263166e619c5bca686924b15094c2`. They differ. The v1.15 debug APK is therefore **not compatible with an in-place update** of the v1.14 installation. No install or overwrite was attempted. A stable production signing key is still required for safe future upgrades.

## Verification boundary and outstanding work

This audit distinguishes source review, artifact integrity, CI build/test, installation and physical-device validation. The v1.14 and v1.15 artifacts have separate verified workflow records. The v1.15 build and 22-test suite passed; physical-device correctness and installation/update compatibility remain unverified.

No physical RMX2161 test has been performed by this audit. The v1.15 APK must still be tested while unplugged/discharging and while charging to confirm the quality flags on the actual device. Voltage must be independently confirmed before a numeric wattage can be reported. Actual USB input watts remain unavailable unless the handset exposes a semantically verified USB-side V/I source.

The app is not yet the complete device-wide monitor described in the architecture document. Its current implementation is primarily battery telemetry, local history, best-effort kernel inspection and optional foreground-app correlation. Implementing the remaining public Android collectors is a separate engineering phase.

No install/overwrite operation was performed. Compare signing certificate digests before any in-place update, and preserve existing app data.
