# BatteryScope — Engineering Audit

Audit date: 2026-11-21
Target: Realme Narzo 20 Pro (RMX2161), Android 12, non-rooted
Branch: batteryscope

## Executive result

BatteryScope is a read-only diagnostic app. It can collect Android battery telemetry and, with Shizuku started through Wireless debugging, attempt privileged reads from Linux power-supply, USB Type-C, USB Power Delivery and Oplus vendor-driver interfaces.

It does not currently offer a safe charging-current or SuperDart negotiation control. The app must not imply otherwise. A generic sysfs write, writable permission bit, or USB role switch is not a validated charge-rate API.

## Measurement model

### Android framework battery data

| Signal | Source | Unit / interpretation |
|---|---|---|
| State of charge | ACTION_BATTERY_CHANGED | percent |
| Battery voltage | ACTION_BATTERY_CHANGED EXTRA_VOLTAGE | mV; raw value retained |
| Battery temperature | ACTION_BATTERY_CHANGED EXTRA_TEMPERATURE | tenths of °C |
| Instantaneous current | BatteryManager CURRENT_NOW | µA; battery-side current |
| Average current | BatteryManager CURRENT_AVERAGE | µA; battery-side current |
| Charge counter | BatteryManager CHARGE_COUNTER | µAh |
| Energy counter | BatteryManager ENERGY_COUNTER | nWh |
| Battery power estimate | calculated from battery voltage × battery current | W, battery side only; not USB input power |

Android battery current is not USB-C input current. Positive current indicates charging; negative indicates discharging. A suspiciously tiny value is reported as returned, not silently scaled into a plausible-looking number. Missing values remain unavailable.

BatteryScope retains the raw voltage broadcast value. It only uses a plausibility-bounded voltage for the optional battery-side power estimate; the raw value is not discarded when outside that range.

### Kernel power-supply data

Ordinary app UID access is attempted first. Shizuku UserService provides a second read path at the ADB-shell UID when Shizuku is started using Wireless debugging and the user grants BatteryScope access. This does not grant root. SELinux and per-node permissions remain authoritative.

Standard Linux power-supply attributes such as voltage_now/current_now have ABI-defined units, but the meaning depends on the power-supply node. Values under a USB/charger node can represent VBUS/IBUS; values under battery/BMS nodes represent battery-side quantities. BatteryScope preserves the full node path.

Vendor-defined Oplus properties are kept raw when units or enum mappings are not verified. In particular, current_max on an Oplus battery node is not blindly converted using the generic standard-sysfs assumption.

## Phone-to-charger communication inspection

The Shizuku audit attempts read-only inspection of:

- /sys/class/typec — port/partner power role, data role, mode and revision properties when exposed.
- /sys/class/usb_power_delivery — partner capabilities and PDO information when exposed.
- /sys/class/dual_role_usb and /sys/class/usb_role — legacy role information when exposed.
- /sys/class/power_supply — online state, USB type, charger type, input voltage/current limits and related nodes.
- dumpsys battery and dumpsys usb — Android framework snapshots.

USB-PD source/sink capability records describe advertised capabilities. They are not proof of which contract is currently selected. A current negotiated contract is shown only if the device exports a distinct live contract value.

A normal app cannot passively decode the proprietary SuperDart/VOOC electrical handshake just by reading BatteryManager. No USB packet capture, I²C transaction capture or waveform measurement is performed.

## Oplus / VOOC driver-source audit

Reference source inspected: https://github.com/EvilAnsh/kernel_realme_RM6785
Relevant files:
- drivers/power/oplus/oplus_charger.c
- drivers/power/oplus/oplus_vooc.c
- drivers/power/oplus/charger_ic/oplus_battery_mtk6785.c

This source family lists the following battery power-supply properties:
- FAST_CHARGE (published as the sysfs attribute `fastcharger` in the inspected `power_supply_sysfs.c`)
- VOOCCHG_ING (published as `voocchg_ing`)
- CHARGE_TECHNOLOGY (published as `charge_technology`)
- ADAPTER_FW_UPDATE (published as `adapter_fw_update`)
- FAST_CHG_TYPE (published as `fast_chg_type` on the USB power-supply node in the MTK USB driver)

The reference implementation maps:
- `fastcharger` / `POWER_SUPPLY_PROP_FAST_CHARGE` to `oplus_chg_show_vooc_logo_ornot()`, a VOOC-related fast-charge UI/driver indicator. It may include transition or warm-state conditions; it is not a measured wattage or USB contract.
- `voocchg_ing` to `oplus_vooc_get_fastchg_ing()`, which returns the driver's fastchg_ing state. This is the clearest driver state indicator in the inspected source, but it still does not report electrical VBUS/IBUS.
- `charge_technology` to `chip->vooc_project`, a vendor project value. It is retained as a raw enum.
- `adapter_fw_update` to the vendor adapter firmware update state. It is retained raw.

The MTK USB driver also publishes `fast_chg_type`, returning the VOOC fast-charger subtype when active or a charger-subtype value from the charger IC. BatteryScope retains the integer and path as raw driver evidence rather than guessing the enum mapping.

These properties are included in the diagnostic allowlist. Their presence and read permissions must still be confirmed on the exact installed RMX2161 firmware. The community kernel source is a reference baseline, not proof that every property exists in every production firmware build.

## Charging control safety

No control writes are performed.

The inspected Oplus source declares MMI_CHARGING_ENABLE writeable. Its setter calls the charge on/off path and can terminate VOOC; this is a binary charging enable/disable mechanism, not a negotiated input-current slider. The source also exposes OTG_SWITCH as a writeable USB-side property, which can change the port's source/OTG behavior. These are deliberately excluded.

A charge-rate control may only be added after identifying an exact control on the installed firmware and verifying:
1. documented units and allowed range;
2. intended effect (input current, battery charge current, charge enable, or USB role);
3. driver-specific preconditions and thermal protections;
4. privilege requirements and SELinux behavior;
5. safe read-after-write verification and rollback;
6. that the change does not bypass or destabilize the phone's charging protocol.

A DAC write-permission check is not evidence of semantic safety. BatteryScope will never expose a generic raw sysfs writer.

## Build and regression history

- Previous Shizuku prototype called Shizuku.newProcess(), which is private in API 13.1.5; CI caught this compile failure.
- Implementation moved to the documented Shizuku UserService Binder pattern.
- AIDL generation is explicitly enabled in Gradle.
- The service scanner is read-only and uses bounded recursion, bounded output, and timeouts for dumpsys commands.
- CI is required to finish successfully before any APK is described as installable.

## Acceptance criteria before release

- Debug APK builds successfully.
- Shizuku permission request and UserService bind/unbind tested on Android 12.
- Shizuku UID and SELinux context visible in returned audit.
- App telemetry values match independent Android battery status values.
- Each USB/PD/VOOC reading retains its source path and raw value.
- Absence/denial is reported as unavailable or denied, never as zero.
- USB-PD PDO capabilities are never labelled as the selected contract.
- No charge-control write or USB-role write is attempted.
- CSV and text audit export succeed.
- Live values are labelled with their actual polling/snapshot semantics; no packet-level live trace is claimed.

## Known limits

- The ADB-shell UID may not read the proprietary nodes on this production firmware.
- Some Android power-supply attributes are vendor-specific or missing.
- The app cannot infer charger output power from battery current alone.
- The app cannot force SuperDart/VOOC, select a different current profile, or guarantee that an 80 W adapter will negotiate above the phone's supported profile.
- No safe non-root charge-rate control has been verified for this handset.
