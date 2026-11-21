# BatteryScope 1.3 — Realme Narzo 20 Pro (RMX2161)

BatteryScope is a read-only Android battery and charging-path diagnostic application. It is designed to show what the phone's own Android framework and kernel expose, without inventing hidden values.

## What it measures

- State of charge, battery voltage, temperature and health from Android battery broadcasts.
- Battery-side instantaneous and average current, charge counter and energy counter from BatteryManager.
- Optional battery-side power estimate, calculated from battery voltage × battery current. This is not charger USB input power.
- Standard Linux power-supply attributes with node paths and raw values.
- Oplus/VOOC driver states when the installed firmware exposes them: fastcharger, fast_chg_type (USB supply), voocchg_ing, charge_technology and adapter_fw_update.

## Phone ↔ charger inspection

With Shizuku running via Android Wireless debugging and BatteryScope authorized, the app runs a read-only Shizuku UserService scan of:

- /sys/class/power_supply
- /sys/class/typec
- /sys/class/usb_power_delivery
- /sys/class/dual_role_usb
- /sys/class/usb_role
- dumpsys battery
- dumpsys usb

The results distinguish Type-C roles, USB-PD advertised capabilities, charger/USB supply telemetry, Oplus VOOC driver flags and the driver's charger-subtype code when those attributes are exposed. The scan is an on-demand snapshot. It is not a continuous electrical packet trace.

USB-PD PDOs are advertised capabilities; they do not prove the selected live contract. A VOOC driver flag is evidence of the driver's internal state, not a direct voltage/current measurement at the USB connector.

## What it cannot control

BatteryScope does not force SuperDart/VOOC negotiation and does not offer a generic charge-current slider.

The inspected Oplus RM6785 source family includes MMI_CHARGING_ENABLE, whose setter turns charging off/on and can terminate VOOC. That is not a charge-rate control and is deliberately not written. USB OTG/power-role switches are also excluded.

No write to sysfs, USB roles, I²C or PMIC is performed. A control can only be added when its exact semantics, units, permitted range and rollback behavior are verified on the installed RMX2161 firmware.

## Shizuku setup

1. Install Shizuku from https://shizuku.rikka.app/.
2. Start Shizuku using Android 12 Wireless debugging.
3. Open BatteryScope and choose **Run Shizuku power-path audit**.
4. Grant BatteryScope access in the Shizuku permission prompt.
5. Run the audit again if needed. The report displays the service UID and SELinux context.

Shizuku gives an app ADB-shell privileges when started through Wireless debugging; it is not root. The phone's SELinux policy may still deny some nodes.

## Exports

CSV telemetry and the latest Shizuku audit report are written to the app-specific files directory. Values are raw or explicitly labelled with their measurement domain and units.

## Build

GitHub Actions builds the debug APK from this branch using Java 17, Gradle 8.9 and Android SDK 35. CI success is required before an APK is considered installable.

See [AUDIT.md](AUDIT.md) for the detailed measurement model, kernel-source findings, known risks, and release acceptance criteria.
