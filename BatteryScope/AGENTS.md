# BatteryScope engineering instructions

This is an existing Android app. Extend it in place; do not regenerate it from scratch for routine changes.

## Compatibility invariants
- `applicationId` and namespace remain `com.batteryscope`.
- Preserve installed-app update compatibility and app data.
- Target device: Realme Narzo 20 Pro (RMX2161), Android 12, non-rooted.
- Java/Kotlin JVM target 17; Android compile SDK 35; current Android Gradle Plugin and Kotlin versions are in `build.gradle.kts`.
- Shizuku is a non-root ADB-shell service. Never assume it can write root-owned sysfs nodes.

## Measurement integrity
- Label BatteryManager current as battery-side current, not USB input current.
- Label voltage/current-derived wattage as battery-side estimate, not adapter output power.
- Charge counter is remaining-charge estimate, not full-charge capacity (FCC).
- Android health GOOD is a framework state, not battery capacity health.
- Preserve raw sysfs values and full source paths. Decode vendor enums only with a verified source mapping.
- Missing/denied/unavailable is not zero.
- USB-PD PDO advertisements are capabilities, not proof of active contract.
- VOOC/SuperDart driver flags are not protocol packet capture or direct electrical measurements.

## Safety boundaries
- Never add generic shell execution, arbitrary sysfs writes, PMIC/I2C writes or undocumented protocol commands.
- The only permitted charging control is the narrowly gated Oplus `cool_down` property path already implemented and documented. Preserve exact-path, range, device, charging-state, effective-write-permission, explicit-confirmation and read-back checks.
- Do not weaken control guards to make buttons appear enabled.

## Change and verification
- Read `MainActivity.kt`, Shizuku service/AIDL and relevant manifest/config before editing shared interfaces.
- Keep unrelated UI, telemetry, logging and Shizuku behavior unchanged during a focused fix.
- Add/update tests where feasible. At minimum run the GitHub Actions APK build for Android source changes.
- A successful compile does not prove readings are accurate on the handset. Distinguish compile, artifact, install and device validation.
- Keep `README.md`, `AUDIT.md`, `CHANGELOG.md` and `RELEASE_CHECKLIST.md` consistent with the code.
- Do not commit APKs or build outputs; distribute verified workflow artifacts.
