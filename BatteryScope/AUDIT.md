# BatteryScope 1.5 — Engineering Audit

Audit date: 2026-11-21
Target handset: Realme Narzo 20 Pro, RMX2161, Android 12, non-rooted
Project branch: batteryscope

## 1. Executive finding

BatteryScope now separates four classes of evidence:

1. Android framework battery telemetry.
2. Kernel power-supply telemetry, preserving the node path.
3. Type-C, USB-PD and Oplus/VOOC driver state.
4. A conditional, single-property Oplus input-current limiter.

The full audit is read-only. A live link sampler reads approved sysfs attributes every two seconds after Shizuku connects. The only code path capable of a write is the explicitly user-confirmed Oplus cool_down operation, and it is gated by the exact node, numeric range, charging state, effective write permission and read-back.

BatteryScope does not decode the proprietary SuperDart electrical handshake, select a USB-PD contract, negotiate an adapter profile, measure connector waveforms or invent values that the phone does not expose.

## 2. Telemetry provenance and units

### Android framework

| Signal | Source | Unit / domain |
|---|---|---|
| Battery state of charge | ACTION_BATTERY_CHANGED | percent |
| Battery voltage | EXTRA_VOLTAGE | mV, raw value retained |
| Battery temperature | EXTRA_TEMPERATURE | tenths of °C |
| Battery instantaneous current | BatteryManager CURRENT_NOW | µA, battery-side |
| Battery average current | BatteryManager CURRENT_AVERAGE | µA, battery-side |
| Charge counter | BatteryManager CHARGE_COUNTER | µAh |
| Energy counter | BatteryManager ENERGY_COUNTER | nWh |
| Health / plug type / status | ACTION_BATTERY_CHANGED | enum values decoded from Android constants |
| Battery power estimate | battery voltage × battery current | W, battery-side estimate only |

A battery-current reading is not USB input current. The app retains raw current and raw voltage. The optional wattage is explicitly labelled a battery-side estimate. Android's unavailable sentinel is treated as unavailable, not zero.

The app does not use a "plausible" substitute when a sensor property is absent. A very small reported value remains the literal reported value until a second independent source validates it.

### Linux power-supply sysfs

The ordinary app UID first attempts reads from /sys/class/power_supply. Shizuku UserService then attempts a separate read using the process UID granted by Shizuku. The report includes UID, PID and SELinux context.

Standard kernel power-supply attributes have ABI-defined units, for example voltage_now in µV, current_now in µA and power_now in µW. Units are applied only to fields whose ABI supports that interpretation. Oplus vendor-specific enums and fields remain raw. The complete sysfs path is preserved so battery-side and USB-side sources are not conflated.

## 3. Phone-to-charger communication

### Full audit

The manual Shizuku audit scans:
- /sys/class/power_supply
- /sys/class/typec
- /sys/class/usb_power_delivery
- /sys/class/dual_role_usb
- /sys/class/usb_role
- Android dumpsys battery
- Android dumpsys usb

It reports readable port/partner power and data roles, port mode, charger online state, charger type, USB-PD data and current/voltage fields when exposed by the running kernel.

### Live sampler

After Shizuku connects, a lightweight sampler polls the power-supply, Type-C and USB-PD trees every two seconds. It reports the last sample timestamp and raw source values. It does not execute dumpsys on every poll.

A missing file or denied read is not represented as zero. The app displays "not exposed", "denied" or "unavailable", according to the evidence received.

### USB-PD and SuperDart interpretation

USB-PD PDO/source/sink capability files describe advertised capabilities. They do not prove which contract is currently selected. BatteryScope only labels a negotiated contract if the phone exports a distinct live selected-contract value.

SuperDart/VOOC is a proprietary charging path. Kernel flags are evidence of the driver's internal state, not captured electrical packets. This app does not sniff USB-C CC lines, I²C, PMIC transactions or adapter handshake waveforms. Without an exposed debug interface or external instrumentation, a normal Android app cannot reconstruct that proprietary wire conversation.

## 4. Oplus / VOOC source audit

Reference tree inspected: [EvilAnsh/kernel_realme_RM6785 at 271e01b](https://github.com/EvilAnsh/kernel_realme_RM6785/tree/271e01b9109b6efde028e6011125b6328cda673a).

Relevant source files:
- [RM6785_defconfig](https://github.com/EvilAnsh/kernel_realme_RM6785/blob/271e01b9109b6efde028e6011125b6328cda673a/arch/arm64/configs/RM6785_defconfig)
- [Oplus charger implementation](https://github.com/EvilAnsh/kernel_realme_RM6785/blob/271e01b9109b6efde028e6011125b6328cda673a/drivers/power/oplus/oplus_charger.c)
- [Power-supply sysfs implementation](https://github.com/EvilAnsh/kernel_realme_RM6785/blob/271e01b9109b6efde028e6011125b6328cda673a/drivers/power/supply/power_supply_sysfs.c)
- [MediaTek charger implementation](https://github.com/EvilAnsh/kernel_realme_RM6785/blob/271e01b9109b6efde028e6011125b6328cda673a/drivers/power/supply/mediatek/charger/mtk_charger.c)
- [MTK6785 Oplus battery driver](https://github.com/EvilAnsh/kernel_realme_RM6785/blob/271e01b9109b6efde028e6011125b6328cda673a/drivers/power/oplus/charger_ic/oplus_battery_mtk6785.c)

The reference RM6785 defconfig sets CONFIG_OPLUS_SMART_CHARGER_SUPPORT=y. The relevant Kconfig symbol defaults to n, so the active device kernel configuration must still be confirmed at runtime where possible. The app attempts to read /proc/config.gz; some production kernels do not export this file.

Reference Oplus properties:
- fastcharger is the sysfs attribute for POWER_SUPPLY_PROP_FAST_CHARGE. Its getter is VOOC-related driver/UI state, not an electrical measurement.
- voocchg_ing reads the Oplus driver's fastchg_ing state.
- charge_technology is a vendor project enum; BatteryScope leaves it raw.
- adapter_fw_update is a vendor adapter-firmware state; BatteryScope leaves it raw.
- fast_chg_type is a USB-supply charger subtype. BatteryScope displays the raw value and does not guess enum meanings.

These are reference-tree findings. The app only reports them as handset evidence when the installed firmware exposes the corresponding files and Shizuku can read them. A community kernel source tree is not proof that the production ROM uses the exact same code revision.

## 5. Conditional Oplus COOL_DOWN input-current limiter

The inspected RM6785 source family defines POWER_SUPPLY_PROP_COOL_DOWN under CONFIG_OPLUS_SMART_CHARGER_SUPPORT. The RM6785_defconfig enables that option. The Oplus battery driver's property writeability callback marks COOL_DOWN writable when the option is compiled, and the setter calls oplus_smart_charge_by_cool_down().

The reference setter uses:
- Level 0: restore the driver's configured OEM input-current defaults.
- Levels 1–6: select discrete input-current limits.

Reference current-cap tables from oplus_charger.c:

| Level | Normal input current | VOOC input current |
|---|---:|---:|
| 1 | 500 mA | 1000 mA |
| 2 | 900 mA | 1500 mA |
| 3 | 1200 mA | 2000 mA |
| 4 | 1500 mA | 2500 mA |
| 5 | 2000 mA | 3000 mA |
| 6 | 2000 mA | 3500 mA |

These values are driver limit settings. They are not a measurement of the current currently drawn from the adapter and are not a request to select a different USB-PD or VOOC voltage contract. The driver may enforce additional conditions or replace limits in response to thermal or charger state.

### Permission model

In the inspected power_supply_sysfs.c, power_supply_attr_is_visible() starts with read permissions for user/group/other. If the driver reports a property as writeable, the function adds S_IWUSR (owner-write only). A normal Shizuku service started with Wireless debugging runs as ADB shell, not root, and does not own a root-owned sysfs node. Therefore the property may exist and be readable while remaining unwritable to this app.

BatteryScope does not bypass this. Its scan reports:
- exact node presence;
- read permission;
- the Shizuku process's effective canWrite() check;
- raw current cooldown level;
- CONFIG_OPLUS_SMART_CHARGER_SUPPORT from /proc/config.gz when exported.

If the exact node is not present, unreadable, outside the expected 0–6 range, or not writable by the Shizuku service, the UI disables all control buttons.

### Write guard

If the device exposes the exact /sys/class/power_supply/battery/cool_down node and the Shizuku service can write it, a user may select OEM default or level 1–6. The operation requires:
1. explicit confirmation;
2. Android battery status CHARGING and a nonzero plug source;
3. the exact battery/cool_down path;
4. current value numeric and within 0–6;
5. requested value within 0–6;
6. an effective write-permission check;
7. a single write to that property;
8. an immediate exact read-back.

A result is called READ-BACK VERIFIED only when the property returns the requested value. This confirms the driver property value, not the actual physical current or power at the USB connector. If any gate fails, the app refuses the operation and reports the reason.

The Oplus setter does not itself validate negative levels in the reference source. BatteryScope never forwards values outside 0–6. It does not offer arbitrary input text, shell commands or generic sysfs writes.

### Controls deliberately excluded

- MMI_CHARGING_ENABLE is binary charge enable/disable and its setter can terminate VOOC.
- STOP_CHARGING_ENABLE changes charging stop state; it is not a charge-rate control.
- OTG_SWITCH changes USB OTG/source behavior.
- USB Type-C power/data role switching changes port behavior, not negotiated charger current.
- Raw I²C, PMIC register and proprietary protocol writes are not implemented.

## 6. Accuracy, error handling and logging

- Missing values are not displayed as zero.
- Raw vendor enums are not translated without a verified mapping.
- Battery/BMS quantities are not labelled USB input measurements.
- PDO advertisements are not labelled an active contract.
- File paths are kept with raw values.
- Live link snapshots are sampled state, not protocol packet capture.
- Control requests are explicitly confirmed and read back.
- CSV logs contain Android framework telemetry and ordinary-app sysfs snapshots. The text export contains the latest full Shizuku report and latest live link sample.

## 7. CI and release acceptance

Release acceptance requires:
- GitHub Actions debug APK build succeeds.
- APK output exists and artifact upload succeeds.
- AIDL/UserService Shizuku permission and bind flow is tested on Android 12.
- Shizuku UID and SELinux context are visible.
- Telemetry values are checked against Android broadcasts and BatteryManager.
- Each charger/VOOC field retains its path and raw value.
- Live sampler runs without overlapping Binder calls.
- No unavailable value becomes zero.
- COOL_DOWN buttons remain disabled without the exact live writable node and all guards.
- Any permitted control action returns exact read-back; physical input current is not claimed without an independent measurement.
- CSV and audit export complete.

## 8. Known limits

- The installed RMX2161 production firmware may differ from the inspected community RM6785 reference tree.
- /proc/config.gz may not be exposed.
- The Shizuku ADB-shell UID will usually not have owner-write access to root-owned sysfs properties. On a non-root phone the Oplus current-limit buttons may remain disabled even though the kernel property exists.
- Type-C, USB-PD and VOOC fields may not be published to userspace.
- USB-PD PDO data does not prove the selected contract.
- No public Android API grants arbitrary control over charger protocol negotiation.
- No non-root capability to force SuperDart/VOOC or choose an arbitrary adapter profile has been verified.
