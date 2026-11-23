# BatteryScope 1.14 — Measurement and implementation audit

Audit date: 2026-11-23  
Branch: `batteryscope`  
Target: Realme Narzo 20 Pro (RMX2161), Android 12, non-rooted

## Architecture and safety boundary

BatteryScope is a standalone Android application. It uses standard Android framework APIs and best-effort reads available to its ordinary application UID. There is no Shizuku integration or dependency, no ADB setup, no root requirement and no companion application. The former privileged UserService and charging-control path have been removed. BatteryScope performs no sysfs writes, PMIC/I2C writes, shell commands or undocumented protocol commands.

## Battery telemetry provenance

| Signal | Source | Interpretation |
|---|---|---|
| State of charge | `ACTION_BATTERY_CHANGED` | Percentage reported by Android |
| Battery voltage | `EXTRA_VOLTAGE` | Battery-side voltage, mV |
| Battery temperature | `EXTRA_TEMPERATURE` | Tenths of °C |
| Instantaneous current | `BATTERY_PROPERTY_CURRENT_NOW` | Battery-side current, µA |
| Average current | `BATTERY_PROPERTY_CURRENT_AVERAGE` | Battery-side average current, µA |
| Charge counter | `BATTERY_PROPERTY_CHARGE_COUNTER` | Estimated remaining charge, µAh |
| Energy counter | `BATTERY_PROPERTY_ENERGY_COUNTER` | Energy counter, nWh |
| Framework health | `EXTRA_HEALTH` | Android status enum, not capacity health |
| Charger maximum current/voltage | `max_charging_current` / `max_charging_voltage` broadcast extras | Advertised maximum values when reported; not live measured USB current/voltage |
| Battery presence | `present` broadcast extra | Only reported when the extra exists |
| Cycle count | optional `cycle_count` extra | Best-effort metadata; not guaranteed on Android 12/OEM builds |

Battery-side current is not USB input current. Voltage × current is a battery-side power estimate, not adapter output power. Android's unavailable sentinels and permission failures are retained as unavailable; they are not converted to zero. Where ordinary-app-readable battery/BMS sysfs nodes expose `charge_full` and `charge_full_design`, BatteryScope reports their ratio as a separate kernel fuel-gauge capacity-health estimate. It does not silently merge this with the charge-counter/SOC empirical estimate. A `cycle_count` attribute is also surfaced when readable.

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

The foreground recorder samples Android battery telemetry every 30 seconds and bounded class-tree inventory every 120 seconds. Live dashboard sysfs traversal and BatteryManager reads execute on a dedicated worker thread rather than the UI thread. The dashboard reports Android property sentinels, Usage Access status, kernel class-root visibility, per-file read/list errors, and root paths that may be absent or SELinux-hidden. It uses a partial CPU wake lock while active, increasing the app's own power use. Recording is best-effort and may be interrupted by force-stop, powered-off time, Android/Realme restrictions or service failure. Boot resume is attempted only when the user previously left recording enabled.

SQLite history is local and has no automatic retention cap. CSV export merges samples and events chronologically and includes wall-clock, monotonic and boot-count fields plus charger maximum-current/voltage extras, battery presence, invalid-charger flag and optional cycle count. History analytics report SOC percentage/hour, charge-counter-derived mA, screen-on/off charging and discharging rates, plugged-in decline, foreground-app correlation, session durations and approximate historical time-to-full/time-to-empty.

## Verification boundary

JVM tests cover estimator arithmetic and invalid-session rejection. CI compilation and tests do not establish that RMX2161 exposes a usable charge counter or that the estimate matches a laboratory capacity test. Physical handset validation remains required.
