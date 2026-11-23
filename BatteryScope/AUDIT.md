# BatteryScope 1.15 — Measurement and implementation audit

Audit date: 2026-11-23  
Branch: `batteryscope`  
Target: Realme Narzo 20 Pro (RMX2161), Android 12, non-rooted

## Architecture and safety boundary

BatteryScope is a standalone Android application. It uses standard Android framework APIs and best-effort reads available to its ordinary application UID. There is no Shizuku integration or dependency, no ADB setup, no root requirement and no companion application. The former privileged UserService and charging-control path have been removed. BatteryScope performs no sysfs writes, PMIC/I2C writes, shell commands or undocumented protocol commands.

## Battery telemetry provenance

| Signal | Source | Interpretation |
|---|---|---|
| State of charge | `ACTION_BATTERY_CHANGED` | Percentage computed only from a valid EXTRA_LEVEL/EXTRA_SCALE pair; missing or out-of-range inputs remain unavailable |
| Battery voltage | `EXTRA_VOLTAGE` | Battery-side voltage, mV |
| Battery temperature | `EXTRA_TEMPERATURE` | Tenths of °C |
| Instantaneous current | `BATTERY_PROPERTY_CURRENT_NOW` | Battery-side current, µA |
| Average current | `BATTERY_PROPERTY_CURRENT_AVERAGE` | Battery-side average current, µA |
| Charge counter | `BATTERY_PROPERTY_CHARGE_COUNTER` | Estimated remaining charge, µAh |
| Energy counter | `BATTERY_PROPERTY_ENERGY_COUNTER` | Energy counter, nWh |
| Framework health | `EXTRA_HEALTH` | Android status enum, not capacity health |
| Charger maximum current/voltage | `max_charging_current` / `max_charging_voltage` broadcast extras | Advertised maximum values when reported; not live measured USB current/voltage |
| Battery presence | `present` broadcast extra | Only reported when the extra exists |
| Invalid charger flag | `invalid_charger` broadcast extra | Framework-reported charger warning flag when supplied |
| Cycle count | optional `cycle_count` extra | Best-effort metadata; not guaranteed on Android 12/OEM builds |

Battery-side current is not USB input current. Voltage × current is a battery-side power estimate, not adapter output power. Android's unavailable sentinels and permission failures are retained as unavailable; they are not converted to zero. Live diagnostics distinguish the Android unavailable sentinel, SecurityException and other exception classes for each BatteryManager property, while the recorder logs only property-access state transitions to avoid event spam. Where ordinary-app-readable battery/BMS sysfs nodes expose `charge_full` and `charge_full_design`, BatteryScope reports their ratio as a separate kernel fuel-gauge capacity-health estimate. It does not silently merge this with the charge-counter/SOC empirical estimate. A `cycle_count` attribute is also surfaced when readable.

## Capacity estimation method

The capacity estimator uses the local recorder's percentage and charge-counter history. It considers a maximum 90-day window and partitions observations into continuous charging/discharging sessions. A session is eligible only when:
- timestamps are continuous within a 90-second sampling gap;
- boot count is unchanged;
- state-of-charge and charge-counter values are present;
- percentage changes by at least 12 percentage points;
- charge-counter movement has the same direction as percentage movement; and
- the derived capacity falls within a broad plausibility interval of 1,500–8,000 mAh.

For each eligible session:

[
FCC_{session}(mAh)=\frac{|\Delta chargeCounter(\mu Ah)|}{|\Delta SOC|/100\times1000}
]

The app reports the median session estimate, not a single selected session. Confidence is HIGH for at least five sessions with no more than 15% full-range spread, MODERATE for at least three sessions with no more than 25% spread, and LOW otherwise. Health is estimated as median FCC divided by the RMX2161's 4,500 mAh design capacity.

The result remains an estimate. Fuel-gauge calibration, charge-counter quantization, temperature, battery aging behavior and SOC rounding can bias it. The app does not force a 0% discharge cycle. If no valid charge-counter sessions exist, it reports that capacity is not measurable rather than inventing an estimate.

## Ordinary-app kernel visibility

The app attempts bounded, read-only enumeration of power_supply, Type-C, USB-PD, USB-role, thermal and hwmon class attributes. Android/Realme SELinux and file permissions may deny individual attributes. Each raw path/value is retained where readable. A same-supply voltage/current pair is only a candidate when supply identity indicates USB; its exact semantics still require physical-device validation.

PDO records describe advertised capabilities, not proof of an active contract. Oplus VOOC/SuperDart flags are driver state, not electrical wire capture. The app does not infer charger input watts from battery-side current.

## Recorder and storage limitations

The foreground recorder samples Android battery telemetry every 30 seconds and bounded class-tree inventory every 120 seconds. Live dashboard sysfs traversal and BatteryManager reads execute on a dedicated worker thread rather than the UI thread. Recorder event writes arriving from Service lifecycle callbacks and broadcast receivers are queued onto the recorder HandlerThread; shutdown queues SQLite closure after pending work. The BOOT_COMPLETED receiver no longer opens SQLite on Android's broadcast main thread; startup failures are persisted for the in-app diagnostics panel. The dashboard reports Android property sentinels, Usage Access status, kernel class-root visibility, per-file read/list errors, and root paths that may be absent or SELinux-hidden. It uses a partial CPU wake lock while active, increasing the app's own power use. Recording is best-effort and may be interrupted by force-stop, powered-off time, Android/Realme restrictions or service failure. Boot and app-replacement resume are now requested automatically without a user-enabled gate; this source change is not yet build- or device-verified.

SQLite history is local and has no automatic retention cap. CSV export merges samples and events chronologically and includes wall-clock, monotonic and boot-count fields plus charger maximum-current/voltage extras, battery presence, invalid-charger flag and optional cycle count. History analytics report SOC percentage/hour, charge-counter-derived mA, screen-on/off charging and discharging rates, plugged-in decline, foreground-app correlation, session durations and approximate historical time-to-full/time-to-empty. Recent sessions separately estimate display-on and display-off SOC rates, signed battery-side mA rates and time to the target SOC. Per-app reports include coincident SOC and charge-counter mAh movement only when the same foreground package spans both sample endpoints while the display is on; this remains temporal correlation, not direct per-app energy measurement or causal attribution.

## Verification boundary

JVM tests cover estimator arithmetic and invalid-session rejection. CI compilation and tests do not establish that RMX2161 exposes a usable charge counter or that the estimate matches a laboratory capacity test. Physical handset validation remains required.


## Build pipeline and source-review fixes

A dedicated GitHub Actions workflow now targets the `batteryscope` branch and Android project paths. It installs Java 17, Android SDK 35 and Gradle 8.9; runs `:app:testDebugUnitTest`; assembles the debug APK; verifies package ID and versionCode; calculates SHA-256, verifies the APK signature and prints the signing-certificate digest; and uploads the APK and test report. Workflow success and artifact integrity must be checked against the exact source commit before release.

A duplicate `MainActivity.onDestroy()` override discovered in source review was removed. This was a Kotlin compile blocker. Source version is now 1.15 / versionCode 16. Package identity and SQLite schema v4 remain unchanged.


## Device-wide API capability audit — 2026-11-23

A new [DEVICE_TELEMETRY_ARCHITECTURE.md](DEVICE_TELEMETRY_ARCHITECTURE.md) records the wider system-information objective and a source-by-source capability matrix. Identified public/user-granted sources include UsageStatsManager, NetworkStatsManager, StorageStatsManager, PowerManager thermal APIs, ActivityManager memory information and SensorManager inventory. Their permissions, time retention, query latency and measurement semantics are documented there.

BatteryStats system history and per-UID battery energy require privileged BATTERY_STATS access; the app must not claim these are available to an ordinary install. Usage duration, UID network bytes and coincident SOC change are not equivalent to per-app electrical energy.

The Realme Narzo 20 Pro official specification confirms Helio G95, Mali-G76 MC4, 4500 mAh typical battery, 65 W SuperDart, listed wireless radios and physical sensor categories. It does not confirm which sysfs nodes, fuel-gauge counters, charger input telemetry or GPU/CPU statistics are readable by BatteryScope on the installed Android 12 firmware. An on-device capability census is required.

Lifecycle source update: recorder start/stop UI and the service stop action have been removed; app resume requests monitoring, and boot/package-replacement receivers request recovery automatically. Continuous execution remains best-effort because force-stop, shutdown, Android power management and Realme OEM restrictions cannot be overridden by an ordinary app. Build and physical validation are pending.


## Field validation: RMX2161 export review — 2026-11-23

A user-provided CSV contained 22 samples and 51 events over 13m35.654s. SOC 83→80%; charge counter 3,735,000→3,600,000 µAh; temperature 33.8–35.4 °C. All samples were NOT_CHARGING with plug NONE. No charging session or USB-input power was observed.

The exported `battery_voltage_mv` values were 3 or 4, not values in the expected millivolt range. Source inspection confirms MainActivity only accepts 2,500–5,000 for battery voltage and therefore suppresses the battery-side watt calculation for these readings. The `battery_current_ua` values were 14–674 (mean 201.9; median 132), while the UI divides by 1,000 to display mA. The charge-counter endpoint delta corresponds to about 595.8 mA average over the full interval, but the counter is visibly quantized in 45,000 µAh steps. This points to unresolved OEM/API scaling and/or fuel-gauge quantization; it does not establish a safe conversion. Raw units must remain unchanged until independently validated.

Average current, energy counter, cycle count, charger max-current/max-voltage and USB V/I candidates were null in every sample. Usage Access was disabled for 20/22 samples. There were 21 intervals: mean 38.84s and one 154.741s gap. Kernel tree capture yielded no fields because roots were denied or absent/hidden. This dataset validates persistence/export only; it does not validate measurement semantics, charger input watts or continuous background reliability.


## 2026-11-23 — Forensic audit and measurement-quality correction

The complete source/APK review, CSV evidence, integrity metadata, confirmed defects, corrective changes and verification boundaries are recorded in [FORENSIC_AUDIT_2026-11-23.md](FORENSIC_AUDIT_2026-11-23.md). The extended RMX2161 CSV shows a current-polarity conflict with Android's BatteryManager contract and raw voltage values 3–4 in the mV field. Version 1.15 adds explicit polarity/range validation, suppresses battery-side watts when either input is invalid, preserves raw readings, logs quality transitions, simplifies the main battery panel and preserves source UsageStats event timestamps. Eight new JVM tests cover the quality guard.

The v1.14 APK artifact is independently checksum-matched to its workflow artifact and ZIP integrity-checked; its signature verification is recorded by CI. Version 1.15 still requires its own workflow build and physical RMX2161 validation. This correction does not establish charger-input watts or complete the device-wide collector roadmap.
