# BatteryScope 1.11 — Realme Narzo 20 Pro (RMX2161)

## 1.9 measurement diagnosis

The ordinary app process now inventories read-only attributes across `/sys/class/power_supply`, `/sys/class/typec`, `/sys/class/usb_power_delivery`, `/sys/class/dual_role_usb`, `/sys/class/usb_role`, `/sys/class/thermal` and `/sys/class/hwmon`, with a bounded attribute count. The automatic diagnosis reports which roots were visible, external power-supply entries, whether a same-supply `voltage_now` + `current_now` pair is available, configured limits separately from live measurements, and examples of denied/error reads. A plausible same-node pair is labelled a candidate until the device driver's semantics are validated; it is not automatically asserted to be a measured USB contract.

The diagnosis explicitly states when ordinary-app access cannot see USB VBUS/IBUS, when no Type-C/PD class attributes are discoverable, and whether a Shizuku audit has been run. Root access is not invoked automatically and no root prompt is triggered. The expanded scan is read-only. Android BatteryManager values remain battery-side telemetry, not charger input measurements.

BatteryScope is an evidence-first Android battery and charging-path diagnostic app. It shows values returned by Android and the handset's kernel, preserves source paths and raw values, and labels unavailable data as unavailable.

## Battery telemetry

- State of charge, battery voltage, temperature, health, plug source and battery chemistry string from Android battery broadcasts.
- Battery-side instantaneous and average current, charge counter and energy counter from BatteryManager.
- Battery-side power estimate calculated from battery voltage × battery current. This is not USB charger input power.
- Raw Android voltage and current properties are retained. Unavailable values are not replaced with zero or guessed.

## Phone ↔ charger visibility

When Shizuku is running through Android Wireless debugging and BatteryScope is authorized:

- A full audit inventories readable attributes (not only a fixed whitelist) under /sys/class/power_supply, /sys/class/typec, /sys/class/usb_power_delivery, USB-role nodes, thermal zones and hardware-monitor sensor trees. It also captures dumpsys battery, usb, thermalservice and power output.
- A lightweight live sampler polls discovered power-supply, Type-C, USB-PD, thermal and hardware-monitor attributes every two seconds while the app activity is in the foreground; the full Shizuku audit remains on demand.
- The full audit also captures Android battery statistics from `dumpsys batterystats --charged`.
- **Current limitation:** CSV sampling and Shizuku live polling still stop when the app activity pauses. This version is not yet a 24-hour background recorder; keep BatteryScope visible during collection until the persistent recorder is implemented.
- The inventory uses bounded tree depth and per-tree output caps and skips generic device/subsystem/driver symlink branches to avoid traversing unrelated sysfs trees.
- The ordinary app process also inventories all direct attributes exposed under each power-supply class entry; unreadable values are marked rather than silently omitted.
- The app shows the exact sysfs source path and unmodified value. Oplus vendor enums remain raw unless their mapping is verified in the inspected source.
- fastcharger is the Oplus VOOC-related UI/driver indicator; voocchg_ing is the driver fast-charge-in-progress flag; fast_chg_type is a driver-reported charger subtype. These are driver states, not measured VBUS/IBUS or a captured protocol handshake.

USB-PD source/sink capability records describe advertised capabilities; they do not prove the currently selected contract. The app does not capture electrical packets or proprietary SuperDart/VOOC wire traffic. A live sample is a kernel-state snapshot, not a protocol trace.

## Conditional Oplus current-limit control

The Oplus RM6785 reference kernel contains a COOL_DOWN power-supply property when CONFIG_OPLUS_SMART_CHARGER_SUPPORT is enabled. Its cool_down setter accepts level 0 (restore driver defaults) and levels 1–6 (discrete input-current limits). The reference source maps the normal / VOOC input caps as:

| Level | Normal input cap | VOOC input cap |
|---|---:|---:|
| 1 | 500 mA | 1000 mA |
| 2 | 900 mA | 1500 mA |
| 3 | 1200 mA | 2000 mA |
| 4 | 1500 mA | 2500 mA |
| 5 | 2000 mA | 3000 mA |
| 6 | 2000 mA | 3500 mA |

These are reference-driver limit settings, not measured current and not the negotiated charger profile. Installed firmware may differ.

BatteryScope exposes those level buttons only when all live checks pass: the exact /sys/class/power_supply/battery/cool_down node exists, is readable, reports a numeric level within 0–6, and the Shizuku service process reports effective write access. It also requires Android and the battery sysfs node to report charging. A user must confirm the requested change. BatteryScope writes only the bounded value 0–6 to that one property, then reads it back. A successful read-back confirms the driver setting only; it does not prove the physical input current changed.

**Non-root limitation:** Shizuku started with Wireless debugging runs as ADB shell, not root. In the inspected Linux power-supply implementation, a writable property receives owner-write permission (S_IWUSR). A shell process normally does not own that root-owned sysfs file. If the live permission check fails, the controls remain disabled. BatteryScope does not try to bypass permissions.

The app deliberately does not write MMI_CHARGING_ENABLE (binary charging on/off that can terminate VOOC), STOP_CHARGING_ENABLE, OTG_SWITCH, USB power/data roles, PMIC/I²C registers or protocol commands. It cannot force VOOC/SuperDart, request an arbitrary negotiated voltage/current profile, or increase the charger's hardware capability.

Reference source: [EvilAnsh/kernel_realme_RM6785](https://github.com/EvilAnsh/kernel_realme_RM6785/tree/271e01b9109b6efde028e6011125b6328cda673a). This is a community kernel reference, not proof that every retail firmware build has identical code or permissions.

## Shizuku setup

1. Install Shizuku from https://shizuku.rikka.app/.
2. Start it using Android 12 Wireless debugging.
3. Open BatteryScope and tap **Run Shizuku power-path audit**.
4. Grant BatteryScope access in the Shizuku permission prompt.
5. The full audit then runs and the live panel polls the exposed driver state every two seconds.

Shizuku is not root. The app displays the service UID and SELinux context so the diagnostic privilege level is explicit.

## Logging and exports

Start CSV logging to record Android battery telemetry. Stop & export opens Android's document picker and copies the CSV to the location you choose. An app-specific copy remains if you cancel or export fails. The separate text report containing the latest full Shizuku audit and latest live sample remains in app-specific storage. Control results are reported only after the requested sysfs value reads back successfully.

## Build

The APK is built by GitHub Actions using Java 17, Gradle 8.9 and Android SDK 35. A successful build and uploaded artifact are required before a build is called installable.

See [AUDIT.md](AUDIT.md) for the source audit, units, control semantics and known limits. See [CHANGELOG.md](CHANGELOG.md) for release history and [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md) for the repeatable build and validation gate.


## Persistent battery telemetry recorder
Version 1.10 adds an optional user-started recorder independent of the app screen. It stores raw battery samples and system/app activity events in a local SQLite database, with CSV export. Samples are taken every 30 seconds; the power-supply sysfs inventory is refreshed every two minutes. The dashboard reports rolling 24-hour, 7-day and 30-day summaries.

For foreground app tracking, grant BatteryScope **Usage Access** from the app's button. This records which app was in the foreground during sampled intervals; it does not provide a direct electrical measurement of that app's battery current. The recorder shows an ongoing foreground-service notification. It can be interrupted by force-stop, device power-off, OEM process management, permission changes or unavailable hardware interfaces.

For charger input readings, check the separate Shizuku audit and measurement diagnosis. USB-side voltage/current candidates are retained with their sysfs source data but must not be treated as confirmed VBUS/IBUS until the RMX2161 driver semantics are validated.


The recorder samples the Android battery APIs every 30 seconds and refreshes bounded power-supply, Type-C, USB-PD, USB-role, thermal and hwmon sysfs trees every 120 seconds. If Shizuku is running and BatteryScope has been authorized, the recorder binds its own read-only Shizuku UserService and stores periodic privileged power-path snapshots. This binding remains tied to the recorder service rather than the Activity.

For best-effort screen-off sampling, the recorder holds a partial CPU wake lock while active. This prevents normal CPU suspend from deferring the sampling handler, but it increases BatteryScope's own battery consumption. The ongoing notification discloses this. Stop the recorder to release the wake lock.

The history view includes 24-hour, 7-day, 30-day and 90-day summaries; charge/discharge session rates; screen-on/off rates; charge-counter deltas where supported; most recent session time to 50/80/90/100% or 80/50/20/10/0%; and per-app foreground-time correlation with coincident battery-level loss. Threshold times are sample-resolution observations or estimates, not exact hardware event times. App drain remains correlation, not direct per-app current measurement.


## 1.11 continuous telemetry recorder

The recorder is user-started and foreground-notification-backed. It samples core BatteryManager telemetry every 30 seconds on a dedicated worker thread, independent of the Activity, and stores raw samples/events in local SQLite. It refreshes class-tree telemetry every 120 seconds and, when Shizuku is running and authorized, stores privileged snapshots every 120 seconds plus a focused dumpsys batterystats --charged snapshot every 15 minutes.

History reports 24-hour, 7-day, 30-day and 90-day summaries; recent charge/discharge session timing; charge-counter deltas where the fuel gauge exports them; screen-on/off charge/discharge rates; foreground-app time and coincident battery-level changes; and sampling gaps. Threshold times are sample-resolution observations or rate-based estimates. App-level drain is correlated with foreground time and is not a direct per-app current measurement.

For screen-off sampling, the recorder holds a partial CPU wake lock while active. This improves sampling continuity but increases the app's own power consumption and can prevent deep CPU sleep. The ongoing notification states this. Stop recording to release the lock.

When the user leaves recording enabled, BatteryScope attempts to resume after Android's boot-completed broadcast. The phone cannot be monitored while powered off, before user data is unlocked, or when Android/Realme restrictions prevent a restart. Force-stop, service termination, revoked permissions and Shizuku shutdown can still create gaps. The history view reports gap duration but cannot always infer its cause.

Grant Usage Access for foreground app tracking. Grant Shizuku permission and keep Shizuku running for privileged kernel and dumpsys history. Ordinary app telemetry continues when Shizuku is unavailable.
