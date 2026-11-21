# BatteryScope — Realme Narzo 20 Pro

Native Android battery telemetry starter app for RMX2161 / Android 12.

## Included
- Battery voltage, percentage and temperature from Android's battery broadcast.
- Instantaneous battery current and charge counter when exposed by the device.
- Estimated battery-side power (voltage × absolute current).
- 500 ms sampling while the app is open.
- CSV session export to the app-specific external files directory.

## Measurement caveat
Battery-side current is not USB charger-input current. Android's public APIs do not guarantee access to USB-C input voltage/current or proprietary SUPERVOOC/SuperDart negotiation. This app does not force charging speed.

## Build
The repository workflow builds a debug APK and uploads it as a GitHub Actions artifact.
