# BatteryScope — forensic analysis of all three 2026-11-23 exports

## Scope

Analysed the three supplied CSV exports and inspected the current `batteryscope` branch implementation of `BatteryRecorderService`, `MainActivity`, and `BatteryMeasurementQuality`. This is a data/source audit, not a physical electrical test of the RMX2161.

## Export inventory and overlap

| Export | Samples | Events | Duration | Battery % | Temperature | Charge counter |
|---|---:|---:|---:|---:|---:|---:|
| `BatteryScope_History_20261123_214733.csv` | 22 | 51 | 13.59 min | 83 → 80 | 33.8–35.4 °C | 3,735 → 3,600 mAh |
| `BatteryScope_History_20261123_222036.csv` | 55 | 122 | 46.38 min | 83 → 74 | 31.8–35.4 °C | 3,735 → 3,330 mAh |
| `BatteryScope_History_20261123_225715.csv` | 18 | 33 | 9.73 min | 65 → 68 | 34.4–35.2 °C | 2,880 → 3,060 mAh |

The 22 sample timestamps in the first export are all present in the second export. The second file is a later cumulative snapshot of the same recording, not an independent test. The third export is a separate recording. Do not count the first two as 77 independent samples.

## Findings supported directly by the CSVs

### 1. Battery voltage field is unusable as currently labelled

All 95 sample rows across the three exports contain `battery_voltage_mv` values of only 3 or 4. Those are not plausible millivolt readings for a phone battery. The app source reads `BatteryManager.EXTRA_VOLTAGE` and stores the integer unchanged in a column named `battery_voltage_mv`; v1.15's quality gate correctly rejects values outside 2,500–5,000 mV for calculations. The data does not establish whether this OEM value is a volts-scale integer, an API/firmware defect, or another representation. Do not multiply by 1,000 or reinterpret it without independent validation.

### 2. Current direction conflicts with Android's documented convention

In the extended recording, all 52 unplugged `NOT_CHARGING / NONE` samples have positive raw current (14 to 674); all three `CHARGING / AC` samples have negative raw current (-551 to -560).

In the later recording, the unplugged segment is mostly positive, charging begins with one positive transition sample (+223), followed by nine negative samples (-552 to -565), and the first sample after unplugging is -309. The charge counter rises during the charging interval while current is negative.

Android documents `BATTERY_PROPERTY_CURRENT_NOW` in microamperes, positive into the battery and negative out. These device observations run opposite to that convention in the stable charging/discharging segments. This strongly suggests an RMX2161 firmware/API polarity inversion, but the data alone does not prove the exact electrical direction at every instant. Do not simply invert the sign in production.

### 3. Current magnitude/unit is also unresolved

The raw column is named `battery_current_ua`, and the code treats `getLongProperty(BATTERY_PROPERTY_CURRENT_NOW)` as microamperes. Literal values around 500 µA are implausibly small for normal phone load and charging. The charge counter changes in exact 45,000 µAh steps, consistent with a coarse gauge/percentage quantum; it cannot validate instantaneous current scale. In the later recording, the charge-counter increase during the stable charging samples is 135 mAh over about 4.24 minutes (roughly 1.91 A endpoint-average), while raw current is mostly around -0.56 thousand units. The discrepancy is too large to infer a safe conversion from these exports. The charge counter itself may be quantized and is not an independent calibrated instrument.

### 4. Charger-input watts are not available

Across all three exports, `usb_voltage_candidate_uv` and `usb_current_candidate_ua` are absent from every sample. The power-supply diagnostic JSON reports inaccessible/hidden roots. There is no verified same-supply USB voltage/current pair, so adapter/USB input watts cannot be calculated. Battery-side power is also unavailable because voltage fails validation and current direction/scale is unresolved.

### 5. Other missing fields

Across all sample rows, average current, energy counter, max charging current and max charging voltage are absent. Cycle count is absent. Foreground package is empty in the exports. Usage Access is disabled for the later export; earlier exports show mixed permission state. Missing values must remain unavailable, not be represented as zero.

### 6. Recorder cadence and continuity

The 30-second sampler is a target, not a guarantee. The extended recording has 10 intervals longer than 60 seconds and a maximum gap of 307.79 seconds. The first short export has a maximum gap of 154.74 seconds. The later export has two gaps longer than 60 seconds, maximum 78.46 seconds, and two `RECORDER_STARTED` events. These exports demonstrate persisted sampling, but not uninterrupted 24/7 recording.

## Source-level assessment

- `BatteryRecorderService` obtains current/average/charge/energy through `BatteryManager.getLongProperty`, stores the current unchanged under a microamp-labelled field, and stores `EXTRA_VOLTAGE` unchanged under a millivolt-labelled field.
- `BatteryMeasurementQuality` enforces Android's documented current polarity and a 2,500–5,000 mV voltage range. This correctly prevents a false watt calculation for the observed voltage/current data, but it will mark the handset's apparently reversed current as conflicting.
- `MainActivity` derives battery-side watts only through that quality gate. This is not USB input power.
- The recorder uses a foreground service, 30-second target interval and optional partial CPU wake lock. A wake lock improves sampling opportunity but cannot guarantee Android/OEM scheduling or survive force-stop/shutdown.

## Conclusions

1. The app is persisting battery state, temperature, charge-counter and raw current values. It is not yet producing trustworthy live electrical measurements on this handset.
2. Voltage is the first hard failure: the current raw value cannot be treated as millivolts.
3. Current polarity appears reversed on this handset, and its unit scale is unverified.
4. No USB-side V/I source is currently exposed in these exports; actual charger-input watts cannot be derived.
5. The data is sufficient to identify these defects and prevent misleading calculations. It is insufficient to calibrate a correction or claim validated watts/capacity/continuous monitoring.

## Required next validation

Use independent reference measurements and controlled runs. For current direction/scale, compare a stable unplugged interval and a stable charging interval against a reputable inline USB power meter (USB input only) and/or a validated battery test reference; note that USB input current is not the same as battery current because conversion losses and phone load intervene. For voltage, obtain a trusted battery-voltage reading from a documented device interface or measurement instrument. Capture longer runs with screen-on and screen-off segments and retain raw CSVs. Do not change the app's sign or unit conversion based only on these three exports.

## Explicit limits

No physical meter was connected or read during this audit. No claim is made that USB input watts, battery current scale, battery capacity, or battery health has been calibrated. No app was installed, uninstalled, or overwritten, and no user database was changed.
