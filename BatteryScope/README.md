# BatteryScope 1.13 — Realme Narzo 20 Pro (RMX2161)

BatteryScope is a standalone Android battery and charging telemetry app. It uses standard Android framework APIs and best-effort read-only access from its ordinary app process. It does not require Shizuku, root, ADB setup or a companion app.

Application ID: `com.batteryscope`  
Version: 1.13 (versionCode 14)  
Target handset: Realme Narzo 20 Pro RMX2161, Android 12, non-rooted  
Build toolchain: Java 17, Gradle 8.9, Android SDK 35

## Battery telemetry

Where the phone's fuel gauge exposes them, Android BatteryManager provides state of charge, battery voltage, temperature, health state, plug type, technology, instantaneous and average battery-side current, charge counter and energy counter.

Important distinctions:
- BatteryManager current and voltage are battery-side measurements, not USB adapter output.
- The charge counter is an estimate of remaining charge, not full-charge capacity (FCC).
- Android health GOOD is a framework status, not a measured battery-health percentage.
- Missing, denied or invalid readings remain unavailable; they are never substituted with zero.

BatteryScope also attempts bounded, read-only inspection of ordinary-app-visible power-supply, Type-C, USB-PD, USB-role, thermal and hwmon class attributes. Android/Realme permissions may deny individual paths. Any same-supply USB voltage/current pair remains a candidate until its semantics are verified on the handset. The app does not infer charger input watts from battery-side current, decode the proprietary SuperDart wire handshake, negotiate charging protocols or write sysfs controls.

## Battery health and capacity estimate

The Battery Health panel estimates current full-charge capacity from recorded Android charge-counter changes compared with state-of-charge changes across continuous charging/discharging sessions.

- Original design capacity used for this handset: 4,500 mAh.
- The estimator examines up to 90 days of local recorder history.
- A session is considered only when it is continuous within one boot, has valid percentage and charge-counter readings, changes by at least 12 percentage points, and the charge-counter direction agrees with the percentage change.
- The app takes the median of valid session estimates and reports session count, spread and a qualitative confidence level.
- Health percentage is estimated capacity divided by 4,500 mAh. It is not clamped to 100%; a result above 100% indicates estimation variance, not a larger guaranteed battery.
- If the handset does not expose a usable charge counter, the app reports that capacity is not measurable rather than inventing a value.

Keep the recorder enabled during ordinary use and charging to collect enough sessions. A full 0–100% discharge cycle is not required. The result is an estimate, not a laboratory capacity test; temperature, fuel-gauge calibration, percentage quantization and charging behavior can affect it.

## Continuous local recorder

Start **Start all-day recorder** in the app. A foreground service samples:
- Android BatteryManager telemetry every 30 seconds.
- Read-only ordinary-app-visible power-supply, Type-C, USB-PD, USB-role, thermal, hwmon and non-serial USB descriptor inventory every 120 seconds.
- Power connection, battery state, screen on/off and battery-low transitions.
- Optional foreground-app tracking after the user grants Android Usage Access.

The recorder stores raw samples and events in a local SQLite database. History views include 24-hour, 7-day, 30-day, 90-day, 365-day and all-time summaries, charging/discharging trends, screen-on/off comparisons, plugged-in battery decline, session timing and sampling-gap diagnostics. Foreground-app battery decline is correlation, not direct per-app current or energy measurement.

The service uses a partial CPU wake lock while recording to reduce deep-suspend sampling gaps. This increases BatteryScope's own power consumption and can affect the battery-drain experiment being measured. The ongoing notification discloses this; stop recording to release the wake lock. Recording is best-effort and can be interrupted by force-stop, powered-off time, Android/Realme restrictions or service failure. Boot resume is attempted only when the user previously left recording enabled.

## Export and storage

**Export all history** creates a CSV in app-private storage and opens Android's document picker to copy it to a user-selected destination. CSV rows include wall-clock timestamps, monotonic elapsedRealtime timestamps, boot count, sample data and event rows in chronological order. The app retains the internal export copy. CSV generation and copying run on background threads.

Raw SQLite history has no automatic retention cap; storage use grows with recording duration. Export important history before uninstalling or clearing app data.

## Permissions and setup

1. Install BatteryScope.
2. Start the recorder and allow notification visibility if Android asks.
3. Grant Usage Access only if foreground-app tracking is wanted.
4. Review Android/Realme battery-management settings if background recording is interrupted.
5. Stop the recorder when collection is complete.

No Shizuku app, root, ADB setup or companion service is required.

## Build and validation

GitHub Actions builds a debug APK with Java 17, Gradle 8.9 and Android SDK 35, runs JVM unit tests, checks package metadata and uploads the APK plus build information. A successful CI build proves compilation and the configured checks only. It does not prove installation, measurement accuracy, background continuity or capacity-estimate accuracy on the physical RMX2161.

See `AUDIT.md` for measurement limits, `CHANGELOG.md` for source history, `PROJECT_STATUS.md` for verification state and `RELEASE_CHECKLIST.md` for the release gate.
