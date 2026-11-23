# BatteryScope 1.12 — Realme Narzo 20 Pro (RMX2161)

BatteryScope is an evidence-first Android battery and charging-path diagnostic application. It collects Android framework battery data, read-only kernel power-supply and USB class attributes, optional Shizuku shell-UID diagnostics, and long-running local telemetry history.

Application ID: com.batteryscope  
Version: 1.12 (versionCode 13)  
Target handset: Realme Narzo 20 Pro RMX2161, Android 12, non-rooted  
Build toolchain: Java 17, Gradle 8.9, Android SDK 35

## Measurement sources and interpretation

Android framework telemetry includes state of charge, battery voltage, temperature, health, plug type, technology, instantaneous and average battery current, charge counter and energy counter where the fuel gauge exposes them. BatteryManager current and voltage are battery-side measurements. The app's battery-power calculation is a battery-side estimate, not USB adapter output power. Charge counter is remaining-charge estimate, not full-charge capacity (FCC); Android health GOOD is framework status, not a measured capacity-health percentage.

The ordinary app process reads available class attributes under:
- /sys/class/power_supply
- /sys/class/typec
- /sys/class/usb_power_delivery
- /sys/class/dual_role_usb
- /sys/class/usb_role
- /sys/class/thermal
- /sys/class/hwmon

The app preserves source paths and raw values. Missing, unreadable and failed reads are not converted to zero. A same-supply voltage/current pair is considered a USB input candidate only when the supply name or its exported type identifies USB. It remains a candidate until the installed RMX2161 driver's semantics and units are confirmed on-device. Non-USB external supply pairs are reported separately.

USB-PD PDO/source/sink capability data describes advertised capabilities, not proof of an active selected contract. Oplus VOOC/SuperDart driver flags are internal driver state, not protocol packet capture or a direct measurement at the connector. BatteryScope does not decode the proprietary SuperDart wire handshake, select a USB-PD contract, alter charger protocol negotiation, or infer USB input watts from battery-side current.

## Interactive Shizuku diagnostics

Shizuku is optional and is not root. When Shizuku is running through Android Wireless debugging and BatteryScope has been authorized, the interactive UserService audit captures:
- power_supply, Type-C, USB-PD, USB role, thermal and hwmon class trees;
- non-serial USB device descriptors;
- dumpsys battery, usb, thermalservice, power and batterystats --charged;
- process UID, PID, SELinux context and device build identity.

The interactive live power-path panel polls while MainActivity is in the foreground. It is not a packet capture or oscilloscope trace. The full multi-section scan is on-demand.

The background recorder has its own Shizuku UserService binding. When authorized, it stores a live read-only power-path snapshot every 120 seconds and a focused dumpsys batterystats --charged snapshot every 15 minutes. The complete multi-section scan is not repeatedly run in the background. If Shizuku disconnects or permission is unavailable, ordinary app telemetry continues and the privileged source is marked unavailable.

## Conditional Oplus COOL_DOWN control

The inspected RM6785 reference source defines a single Oplus cool_down property with levels 0–6. The reference level table is documented in AUDIT.md. Those values are driver input-current limits, not measured current and not a negotiated adapter profile. The installed retail firmware may differ from the reference source.

The only write path in BatteryScope is the exact /sys/class/power_supply/battery/cool_down property. It remains gated by RMX2161 identity, node presence, read/write access, numeric current level 0–6, requested level 0–6, Android CHARGING status plus a connected power source, explicit user confirmation, a single property write and exact read-back. A read-back only verifies the driver setting. No arbitrary shell command, generic sysfs write, PMIC/I2C write, USB role change or proprietary protocol command is implemented. Unsafe Oplus controls such as MMI_CHARGING_ENABLE, STOP_CHARGING_ENABLE and OTG_SWITCH remain excluded.

## Continuous local recorder

Start **Start all-day recorder** in the app. A foreground service owns sampling on a dedicated worker thread, independently of MainActivity visibility:
- BatteryManager telemetry every 30 seconds.
- Read-only power-supply, Type-C, USB-PD, USB role, thermal, hwmon and non-serial USB descriptor inventory every 120 seconds.
- Power connection, battery state, screen on/off, battery-low and foreground-app transitions.
- Optional foreground-app tracking after the user grants Android Usage Access.
- Optional privileged Shizuku snapshots when Shizuku is running and authorized.

The recorder stores raw samples and events in a local SQLite database. The history panel provides 24-hour, 7-day, 30-day, 90-day, 365-day and all-time summaries, charge/discharge trends, charge-counter changes where available, screen-on/off comparisons, plugged-in battery decline, recent session timing, threshold observations/estimates, foreground-app correlation and sampling-gap diagnostics.

The service holds a partial CPU wake lock while recording so normal deep CPU suspend is less likely to defer the 30-second sampler. This increases BatteryScope's own power consumption and may affect the battery-drain experiment being measured. The ongoing notification explains this; stop the recorder to release the lock.

The recorder uses Android's specialUse foreground-service type with a descriptive subtype and attempts to resume after BOOT_COMPLETED only when the user left recording enabled. It cannot collect data while the phone is powered off, before user data becomes available, or when Android/Realme process restrictions, force-stop, permission changes or service failure prevent it from running. A timestamp gap does not reveal its exact cause. Continuous 24-hour operation is best-effort, not guaranteed.

Usage Access identifies foreground packages and approximate sampled foreground duration. Battery percentage/charge-counter change coincident with an app's foreground time is correlation, not direct per-app current or energy measurement. BatteryStats output is Android's system attribution, not an external per-app electrical meter.

## Exports and storage

**Export all history** streams sample and event rows to a CSV file in BatteryScope's private app storage and opens Android's document picker to copy it to a user-selected location. The CSV includes wall-clock timestamps, monotonic elapsedRealtime timestamps, the Android boot counter, source data and event rows ordered chronologically. The boot counter lets history distinguish same-boot monotonic intervals from reboot-spanning wall-clock intervals; older rows without a boot counter use a conservative agreement check. The internal export file is stored in app-private exports storage, not a temporary cache. CSV generation and copying to the selected document destination run on background threads so large multi-month exports do not block the UI. Raw SQLite history has no automatic retention cap; storage use grows with recording duration. Export important history before uninstalling or clearing app data.

The full Shizuku diagnostic report is shown in the app. A separate user-selected text-report export is not currently implemented.

## Permissions and setup

1. Install BatteryScope.
2. Start the recorder from the app; allow notification visibility when Android asks.
3. Grant Usage Access only if foreground-app tracking is needed.
4. For privileged kernel/framework history, install/start Shizuku using Android Wireless debugging and grant BatteryScope access in Shizuku.
5. Review Battery optimization settings. The app provides a shortcut, but the device/OEM may still impose its own background restrictions.
6. Stop the recorder when collection is complete.

## Build and validation

GitHub Actions builds a debug APK with Java 17, Gradle 8.9 and Android SDK 35, checks the package/application ID and launcher resources, runs aapt metadata verification, computes SHA-256 and uploads the APK plus BUILD-INFO.txt. A successful CI build proves compilation and package-metadata checks only. It does not prove installation, charger measurement semantics, boot-resume behavior, 24-hour continuity or Shizuku behavior on the physical RMX2161.

See AUDIT.md for implementation detail and safety boundaries, CHANGELOG.md for source history, PROJECT_STATUS.md for current verification state, and RELEASE_CHECKLIST.md for the release gate.
