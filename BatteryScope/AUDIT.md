# BatteryScope 1.11 — Engineering Audit

## 1.9 ordinary-app access diagnosis

The ordinary app process now attempts bounded, read-only enumeration of power_supply, Type-C, USB-PD, dual-role USB, USB-role, thermal and hwmon class attributes. Individual permission/read errors are retained as `READ_DENIED`, `LIST_DENIED` or an error string rather than silently dropped. The UI's automatic diagnosis separates external power-supply nodes from battery/BMS nodes, checks whether a plausible `voltage_now` and `current_now` pair is exported by the same external supply, and reports configured input limits separately from live draw. Type-C/PD/role and thermal/hwmon discovery counts are reported independently.

A same-node pair is only a candidate until the installed driver's exact semantics are confirmed. The app does not infer USB input power from battery current, does not treat PDO capabilities or limits as live measurements, does not invoke `su` or trigger a root prompt, and does not write sysfs. Shizuku remains the optional ADB-shell-UID path for a more privileged read-only audit. The actual availability of charger-side measurements still requires a runtime audit on the handset.

Audit date: 2026-11-23
Source revision: telemetry and export correction
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

The manual Shizuku audit inventories all readable attributes (rather than only a preselected property-name list) in:
- /sys/class/power_supply
- /sys/class/typec
- /sys/class/usb_power_delivery
- /sys/class/dual_role_usb
- /sys/class/usb_role
- /sys/class/thermal
- /sys/class/hwmon
- Android dumpsys battery
- Android dumpsys usb
- Android dumpsys thermalservice
- Android dumpsys power
- Android dumpsys batterystats --charged

The class-tree walk has explicit maximum depths and per-tree attribute caps. It skips generic device, subsystem, driver, power, firmware_node and of_node branches so a class alias does not cause a broad traversal through unrelated /sys/devices content. Attribute values are raw and individual read failures are retained as status text. This is a bounded inventory of relevant exported class interfaces, not a recursive dump of the entire kernel sysfs filesystem.

It reports readable port/partner power and data roles, port mode, charger online state, charger type, USB-PD data and current/voltage fields when exposed by the running kernel.

### Live sampler

After Shizuku connects, a lightweight sampler polls discovered power-supply, Type-C, USB-PD, thermal and hardware-monitor attributes every two seconds while the activity is in the foreground. It reports the last sample timestamp and raw source values. It does not execute dumpsys on every poll. The interactive Shizuku live panel remains activity-bound. Separately, BatteryRecorderService owns a persistent foreground recorder and a recorder-specific Shizuku UserService binding; the recorder samples framework values every 30 seconds, class-tree sysfs every 120 seconds and focused BatteryStats output every 15 minutes. The background recorder is best-effort, not guaranteed 24-hour continuity.

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

If the exact node is not present, unreadable, outside the expected 0–6 range, or not writable by the Shizuku service, the UI disables all control buttons. The Shizuku-side setter also requires RMX2161 to appear in model/device/product/fingerprint, and the app-side UI applies the same identity gate before enabling the buttons.

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
- Android battery voltage values outside 2500–5000 mV are retained as raw readings but explicitly marked invalid in the UI and excluded from the battery-side power estimate.
- Charge counter is labelled as Android's remaining-charge estimate, not full-charge capacity. Battery health is labelled as Android framework status, not capacity health.
- Stopping a CSV session opens Android's Storage Access Framework document picker and copies the CSV to the user-selected destination. If export is cancelled or fails, the internal app-specific copy remains.
- The separate diagnostics text report is still saved in app-specific storage; user-selected export for that report is not yet implemented.
- An Android 8+ adaptive launcher icon is provided in both standard and round resource variants. The icon uses a dark navy field with a green battery/lightning mark, cyan scope ring and diagnostic waveform. The foreground is vector-based and scales cleanly across launcher densities.
- Raw vendor enums are not translated without a verified mapping.
- Battery/BMS quantities are not labelled USB input measurements.
- PDO advertisements are not labelled an active contract.
- File paths are kept with raw values.
- Live link snapshots are sampled state, not protocol packet capture.
- Control requests are explicitly confirmed and read back.
- CSV logs stream Android framework telemetry and ordinary-app sysfs snapshots to disk, with periodic flushes and close-on-stop/activity-destroy behavior. The current UI displays the interactive Shizuku report; a separate user-selected text-report export is not implemented. CSV export comes from the SQLite sample/event history.

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
- CSV export completes and retains an app-private copy. A separate diagnostic text-report file export is not currently implemented.

## 8. Known limits

- The installed RMX2161 production firmware may differ from the inspected community RM6785 reference tree.
- /proc/config.gz may not be exposed.
- The Shizuku ADB-shell UID will usually not have owner-write access to root-owned sysfs properties. On a non-root phone the Oplus current-limit buttons may remain disabled even though the kernel property exists.
- Type-C, USB-PD and VOOC fields may not be published to userspace.
- USB-PD PDO data does not prove the selected contract.
- No public Android API grants arbitrary control over charger protocol negotiation.
- No non-root capability to force SuperDart/VOOC or choose an arbitrary adapter profile has been verified.


## 9. Persistent recording and history (v1.10 development milestone)

### Recorder lifecycle
- The user explicitly starts and stops BatteryRecorderService from the visible app UI.
- The service uses the Android foreground-service type `specialUse` with an explicit subtype declaration and an ongoing notification. Android 14+ requires an appropriate declared type and permission; Google Play review may require a justification for this service type.
- The service samples core Android battery values every 30 seconds and refreshes ordinary-app power-supply sysfs attributes every 120 seconds.
- SQLite stores raw samples and event rows locally. There is no automatic raw-history deletion in this milestone. The app exports all stored sample and event rows to CSV.
- START_STICKY allows Android to recreate the service after some process deaths. It does not guarantee survival after force-stop, reboot, power-off, OEM battery management or user termination. v1.11 adds a BOOT_COMPLETED receiver that attempts to resume only when the user left recording enabled. It cannot observe powered-off time, and platform/OEM restrictions may still block resume.

### Usage and attribution
- `PACKAGE_USAGE_STATS` is a special access permission granted manually from Android Settings.
- When granted, BatteryScope records foreground package transitions and the package associated with each sampled interval, plus whether the screen was interactive.
- Foreground duration is approximate at the 30-second sampling resolution.
- The current implementation does not measure a per-app electrical current. Foreground app time and coincident battery percentage/charge-counter changes are correlation/estimates, not direct measurements.
- Android BatteryStats attribution remains a separate source and requires Shizuku access to dumpsys output. The recorder now binds its own Shizuku UserService for the lifetime of the foreground recorder, after explicit user authorization; the Activity can bind separately for interactive audit controls.

### Analytics limits
- Rolling 24-hour, 7-day, 30-day, 90-day, 365-day and all-time summaries report charge/discharge time represented by valid adjacent samples and net percentage change per recorded hour.
- Gaps longer than three sample intervals are excluded from rate-duration calculations to avoid treating recorder downtime as a measured charge/discharge interval.
- This was the v1.10 milestone limitation. v1.11 adds a recent-session ledger and observed/estimated threshold timing; projections are suppressed when the measured percentage trend contradicts the charging/discharging state.
- App foreground intervals are not app energy attribution.
- USB sysfs values are candidate source telemetry only; the exact RMX2161 path and driver units must be verified on-device.


### v1.10 recorder updates
- The foreground service owns sampling on a dedicated HandlerThread and is independent of MainActivity lifecycle.
- Core framework telemetry is sampled every 30 seconds; class-tree sysfs inventories are refreshed every 120 seconds.
- A partial wake lock is held while recording to reduce CPU-suspend gaps. This raises the app's own power consumption and is disclosed in the notification and event log; it is released when recording stops or the service is destroyed.
- If Shizuku is already started and BatteryScope has been granted permission, the recorder can bind a separate read-only Shizuku UserService and persist periodic privileged snapshots. If the user grants Shizuku permission after recording starts, running the power-path audit in the UI asks the recorder to bind.
- Shizuku telemetry is best-effort and stops when Shizuku is killed or permission is revoked. Ordinary app telemetry continues.
- History summary windows: 24 hours, 7 days, 30 days and 90 days. Raw sample/event rows remain stored locally and can be exported.
- Session analytics report charge/discharge rates, screen-on/off rates, charge-counter deltas, recent-session duration and percentage threshold times. Estimates are explicitly marked; gaps over 90 seconds are excluded from interval rates.
- Foreground app attribution uses Usage Access and adjacent samples. Coincident discharge is correlated/estimated; it is not a direct per-app electrical measurement.


## 9. 1.11 recorder engineering updates

- \`BatteryRecorderService\` samples BatteryManager data every 30 seconds on a dedicated HandlerThread and is independent of MainActivity lifecycle.
- A partial CPU wake lock is held for the user-enabled recording session to prevent ordinary deep-sleep suspension from deferring the sampling handler. This increases BatteryScope's own energy consumption; the notification and event log disclose it. The lock is released on explicit stop and service destruction.
- On Android 14+, the service declares \`specialUse\` with a descriptive subtype. This is a narrowly described ongoing user-visible recorder and may require justification/review if distributed through Google Play.
- User-enabled recording is persisted separately from runtime service state. The boot receiver attempts to restart only a recorder the user left enabled. It cannot collect data while powered off, before unlock, or when OS/OEM restrictions reject the foreground-service start.
- If Shizuku is available and permission has been granted, the recorder binds its own read-only UserService. It saves \`snapshotLink()\` every 120 seconds and a full \`scan()\` every 15 minutes; the full scan includes dumpsys battery, usb, thermalservice, power and batterystats --charged. Shizuku data is retained in the raw \`power_supply_json\` field with source text intact.
- The recorder continues ordinary-app reads if Shizuku is unavailable. It records unavailable/error states for the privileged source.
- The database stores sample rows and event rows; raw history is retained until user deletion. CSV export streams all rows.
- Rolling summaries cover 24 hours, 7 days, 30 days and 90 days. They calculate total and screen-on/off charge/discharge percentage rates, charge-counter changes where available, recent session threshold times, foreground-app durations and coincident battery-level changes.
- App-level drain figures are time-correlated estimates, not direct per-app electrical measurements. BatteryStats dumpsys is an Android/system attribution estimate, not a meter inserted per app.
- Intervals longer than 90 seconds are excluded from rate calculations. The dashboard reports gap count, estimated uncovered duration, longest gap and average interval. A timestamp gap alone cannot prove whether the cause was force-stop, powered-off state, OS/OEM process management, Doze or a permission/source failure.



## 10. v1.11 source-level audit corrections and recorder limits

- MainActivity still updates Android BatteryManager values at one-second UI cadence, but its ordinary-app sysfs inventory is cached for 15 seconds to avoid traversing class trees on every display refresh.
- Ordinary-app USB V/I diagnostics classify a candidate as USB only when the external supply name or exported supply type identifies USB. A numeric pair on an unrelated external supply is reported as non-USB and is not called VBUS/IBUS.
- The background recorder samples at a 30-second target cadence. It refreshes class-tree attributes every 120 seconds, and its independently authorized Shizuku binding stores live link snapshots at that interval plus dumpsys batterystats --charged every 15 minutes. The full multi-section Shizuku scan is on demand, not a periodic 15-minute operation.
- Sample records keep both timestamp_ms (wall clock) and elapsed_realtime_ms (monotonic since boot). Duration/rate/gap calculations prefer monotonic differences within one boot and fall back to wall time across a reboot. Wall-clock timestamp remains the database order and sample key; device clock corrections can still complicate chronology.
- Session threshold predictions are only made when the observed battery-percentage trend has the expected direction. A battery decline during CHARGING state or increase during DISCHARGING state is reported as contradictory state/trend evidence, not converted into an invalid threshold forecast.
- A plugged/charging-decline interval is included when both samples report a connected plug source or both samples retain Android CHARGING status. This handles ROMs that temporarily publish EXTRA_PLUGGED=NONE while the battery status remains CHARGING. It is evidence of a state/source mismatch, not proof of the electrical source or USB power level.
- CSV includes elapsedRealtime and merges sample/event rows by timestamp. The export source file is retained under app-private exports storage before the Android document-picker copy.
- SQLite raw history has no automatic retention/rotation policy. It grows with recording time and with retained raw JSON/Shizuku snapshots; the user must export important history before clearing app data or uninstalling.
- The database schema is currently version 1. Future schema additions require explicit migrations; onUpgrade does not currently perform a migration.
- A shared database lock prevents recorder sample/event writes racing the close operation during service destruction.
- The service holds a partial CPU wake lock for the user-enabled recording interval. This reduces CPU-suspend gaps but raises BatteryScope's own drain and changes the workload being measured. The notification discloses the wake lock.
- The service uses foregroundServiceType=specialUse and a declared subtype. Android/Google Play policy may require justification for distribution; CI does not establish store-policy approval.
- Boot resume is a best-effort BOOT_COMPLETED attempt using persisted user intent. It does not record time while powered off or guarantee service resurrection against force-stop or vendor process management.
- No physical RMX2161 installation/runtime test has been performed in this audit. CI compile/package success is not evidence that the retail kernel exports valid USB VBUS/IBUS paths or that the recorder survives real 24-hour use.

### Export schema

CSV record rows use a record_type field with SAMPLE or EVENT. SAMPLE rows contain wall-clock timestamp_ms, monotonic elapsed_realtime_ms, BatteryManager readings, screen state, foreground package, Usage Access state, raw sysfs/Shizuku JSON, and candidate USB source values. EVENT rows contain timestamp, event_type and details. The event and sample records are emitted in timestamp order. Per-app attribution remains correlation only.

### Audit verification scope

The source audit compares fetched Git blob identifiers and byte lengths with the recursive Git tree for the BatteryScope subtree and associated workflows. The exact final source snapshot, file inventory, commit, build and APK digest are recorded in the separate byte-audit report. No source-level inspection can substitute for installing and exercising the application on the target handset.
