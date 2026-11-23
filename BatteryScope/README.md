# BatteryScope 1.14 — Realme Narzo 20 Pro (RMX2161)

BatteryScope is a standalone Android battery and charging telemetry app. It uses standard Android framework APIs and best-effort read-only access from its ordinary app process. It does not require Shizuku, root, ADB setup or a companion app.

Application ID: `com.batteryscope`  
Version: 1.14 (versionCode 15)  
Target handset: Realme Narzo 20 Pro RMX2161, Android 12, non-rooted  
Build toolchain: Java 17, Gradle 8.9, Android SDK 35

## Battery telemetry

Where the phone's fuel gauge and ROM expose them, Android BatteryManager provides state of charge, battery voltage, temperature, health state, plug type, technology, instantaneous and average battery-side current, charge counter, energy counter, battery-present state, invalid-charger flag, charger-advertised maximum charging current/voltage and (on newer Android/OEM builds) cycle-count metadata.

Important distinctions:
- BatteryManager current and voltage are battery-side measurements, not USB adapter output.
- The charge counter is an estimate of remaining charge, not full-charge capacity (FCC).
- Android health GOOD is a framework status, not a measured battery-health percentage. When readable, the app also reports kernel fuel-gauge `charge_full`, `charge_full_design` and `cycle_count` attributes; the charge-full/design ratio is shown separately from BatteryScope's empirical history estimate.
- Missing, denied or invalid readings remain unavailable; they are never substituted with zero.

BatteryScope also attempts bounded, read-only inspection of ordinary-app-visible power-supply, Type-C, USB-PD, USB-role, thermal and hwmon class attributes. Android/Realme permissions may deny individual paths. Any same-supply USB voltage/current pair remains a candidate until its semantics are verified on the handset. The app does not infer charger input watts from battery-side current, decode the proprietary SuperDart wire handshake, negotiate charging protocols or write sysfs controls.

## Battery health and capacity estimate

The Battery Health panel estimates current full-charge capacity from recorded Android charge-counter changes compared with state-of-charge changes across continuous charging/discharging sessions.

- Original design capacity used for this handset: 4,500 mAh.
- The estimator examines up to 90 days of local recorder history.
- A session is considered only when Android exposes a boot count, the samples remain continuous within that boot, percentage and charge-counter readings are valid, the session changes by at least 12 percentage points, and the charge-counter direction agrees with the percentage change.
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

The recorder stores raw samples and events in a local SQLite database. History views include 24-hour, 7-day, 30-day, 90-day, 365-day and all-time summaries; charging/discharging percentage rates and charge-counter-derived mA rates; screen-on/off comparisons; plugged-in battery decline; app foreground-time correlation; session start/end and threshold times; recent session history with separate display-on/display-off SOC and battery-side current rates; and sampling-gap diagnostics. The app estimates time to 100% and 0% using both historical screen-on/screen-off rates and the current session's own screen-state rates. These forecasts are approximate and are labelled as historical estimates. Foreground-app battery decline is correlation, not direct per-app current or energy measurement.

The recorder has two selectable modes. **Continuous** (default) holds a partial CPU wake lock to improve screen-off 30-second sampling continuity; this increases BatteryScope's own power use and can bias discharge measurements. **Low-impact** releases the permanent wake lock; Android may suspend the process and create screen-off sampling gaps, which history reports. Toggle the mode in the app and keep the same mode when comparing runs. The ongoing notification identifies the selected mode. Stop recording to release the wake lock. Recording is best-effort and can be interrupted by force-stop, powered-off time, Android/Realme restrictions or service failure. Boot resume is attempted only when the user previously left recording enabled.

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

GitHub Actions is configured to build a debug APK with Java 17, Gradle 8.9 and Android SDK 35, run JVM unit tests, check package metadata and upload the APK plus build information. The workflow's result must be checked for the exact commit before treating an artifact as verified. A successful CI build proves compilation and the configured checks only; it does not prove installation, measurement accuracy, background continuity or capacity-estimate accuracy on the physical RMX2161.

See `AUDIT.md` for measurement limits, `CHANGELOG.md` for source history, `PROJECT_STATUS.md` for verification state and `RELEASE_CHECKLIST.md` for the release gate.
