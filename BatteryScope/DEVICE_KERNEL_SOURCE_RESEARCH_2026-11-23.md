# RMX2161 kernel-source investigation — 2026-11-23

## Purpose

Investigate BatteryScope's malformed voltage and conflicting current readings using public kernel source for the Realme 7 / Narzo 20 Pro device family. This is source-level evidence only; it does not prove which kernel nodes are readable to the ordinary BatteryScope UID on the user's installed firmware.

## Sources inspected

Public kernel repository: https://github.com/Evolution-X-Devices/kernel_realme_salaa
Branch inspected: `bka`
Device family is identified by the repository as Realme 7 / Narzo 20 Pro / Narzo 30 4G. The repository contains MT6785-specific Oplus charging implementation:
- `drivers/power/oplus/charger_ic/oplus_battery_mtk6785.c`
- `drivers/power/oplus/charger_ic/oplus_battery_mtk6785.h`
- `drivers/power/oplus/charger_ic/mtk_battery.c`
- `drivers/power/oplus/oplus_charger.c`
- `drivers/power/oplus/oplus_gauge.c`

This is a community-maintained kernel tree, not a guarantee that every file exactly matches the stock firmware build on the user's handset. The source is valuable for identifying likely interfaces and semantics, not for claiming runtime access.

## Findings from source

### Battery voltage

In `drivers/power/oplus/oplus_charger.c`, the Oplus battery power-supply property handler implements:
- `POWER_SUPPLY_PROP_VOLTAGE_NOW` as `chip->batt_volt * 1000`.

The code therefore exposes the battery voltage through the standard power-supply interface in microvolts, with `chip->batt_volt` held in millivolts. This is a much stronger candidate for a correctly scaled battery voltage than the user's broadcast extra, which BatteryScope exported as only 3 or 4 in its `battery_voltage_mv` column.

### Battery current

The same property handler implements:
- `POWER_SUPPLY_PROP_CURRENT_NOW` by assigning `oplus_gauge_get_batt_current()` to `chip->icharging` and returning it.

In `drivers/power/oplus/oplus_gauge.c`, `oplus_gauge_get_batt_current()` returns the gauge driver's `get_average_current()` result. The inspected generic Oplus layer does not itself establish the unit scale or polarity of the platform gauge implementation. Therefore, do not infer a conversion or invert sign from this wrapper alone.

The separate older MediaTek battery driver code in `drivers/power/oplus/charger_ic/mtk_battery.c` has a different `battery_get_property` implementation: it uses `gauge_get_current()`, conditionally negates according to `b_ischarging`, and divides by 10. This must not be confused with the MT6785 Oplus property path; the exact compiled configuration matters.

### Charger-side voltage and current candidates

In `oplus_battery_mtk6785.c`:
- `show_ADC_Charger_Voltage()` reads `battery_get_vbus()` and returns the value. Its code can substitute 5000 when a condition indicates VBUS failure or KPOC shutdown is disabled. This is a diagnostic candidate only; the fallback means it cannot be blindly treated as a live measured VBUS value.
- `show_input_current()` returns `thermal_input_current_limit`. This is a configured thermal current limit, **not measured input current**. It must never be used to calculate live input watts.
- `show_chg1_current()` and `show_chg2_current()` similarly return configured thermal charging-current limits, not measured current.

In `oplus_charger.c`:
- The proc entry `input_current_now` returns `chip->ibus / 1000`.
- `chip->ibus` is updated from the charger operation `get_charger_current()` when the relevant charger/gating conditions permit; otherwise it is set to -1.
- The source code's unit scaling and procfs access policy must be validated on the exact stock build before using this as a measurement.

### Battery gauge diagnostic attribute

In `mtk_battery.c`, `FG_Battery_CurrentConsumption` calls `battery_get_bat_avg_current()` and its log explicitly labels the return value in mA. This is a possible average battery-current diagnostic interface if the corresponding device attribute is actually registered and readable by an ordinary app. Its exact device path and stock-build accessibility remain unverified.

## Reconciliation with BatteryScope exports

The three supplied CSVs show:
- `battery_voltage_mv` equals 3 or 4, not a plausible millivolt value.
- Raw `BATTERY_PROPERTY_CURRENT_NOW` signs conflict with the Android documented convention in stable charge/discharge segments.
- `power_supply_json` repeatedly records `power_supply/@root = READ_DENIED`, with zero readable fields in the supplied snapshots. Type-C and thermal roots are also denied or absent.
- Consequently, the source-identified kernel interfaces were not actually available to the app in those exports. The current scanner's attempt to enumerate `/sys/class/power_supply` did not reach the device attributes under the ordinary app UID.

This is a critical distinction: a kernel interface existing in source does not mean an ordinary Android application can read it. SELinux and file permissions can block it.

## Engineering decisions

1. Do not patch a universal sign inversion or scale multiplier into `BATTERY_PROPERTY_CURRENT_NOW`.
2. Do not treat `input_current`, `chg1_current`, or `chg2_current` limit attributes as measured current.
3. Do not treat `ADC_Charger_Voltage` as live VBUS until the fallback path and access are understood.
4. First use standard, read-only Android APIs and any already-readable `/sys/class/power_supply/battery/voltage_now` and `current_now` attributes. Record exact source path, raw value, unit, read status and timestamp separately.
5. Add narrowly scoped read-only probes for source-identified Oplus attributes only when their exact paths are found in the running device tree and the app UID can read them. Never invoke shell, root, Shizuku, ADB, writes, or undocumented charging commands.
6. A source can be promoted to a displayed live measurement only after it is present on the stock handset, its units/polarity are established, and it tracks independent charging/discharging references. Otherwise show unavailable with the precise reason.
7. Charger-input watts require a measured same-side VBUS and IBUS pair. Battery-side voltage/current cannot be substituted for adapter output.

## Immediate implementation sequence

- Inspect the current app's raw supply JSON and diagnostics to distinguish root-level SELinux denial from missing individual attributes. (The supplied exports currently show root denial.)
- Add a device/source inventory report that captures known candidate paths and their exact access/read errors, without attempting to bypass restrictions.
- Refactor voltage/current measurement into source-tagged readings so the app can prefer a verified standard power-supply node over a malformed broadcast extra while retaining both raw sources.
- Keep live battery watts hidden until both selected battery-side sources pass range, unit and polarity validation.
- Keep charger input watts separate and hidden until same-supply VBUS/IBUS is verified.
- Run unit tests and CI; physical handset validation remains a separate gate.

## Verification boundary

No kernel was built or installed. No device files were read directly from the handset during this investigation. The stock firmware's exact source revision, runtime sysfs/proc paths, ordinary-app permissions, and electrical calibration remain unverified. This report is not evidence that any restricted path can be accessed by BatteryScope.
