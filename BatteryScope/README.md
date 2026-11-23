# BatteryScope

A lightweight Android battery monitor for Android 6.0+ devices, including the Realme Narzo 20 Pro.

## Features
- Live state of charge and charging/discharging state.
- Device-reported instantaneous current, voltage and calculated electrical power when supported.
- Charge-counter reading in mAh when exposed by the device.
- Battery temperature, reported health, chemistry and external power source.
- Approximate time-to-20% while discharging and time-to-full while charging, based on observed SOC trend.
- Seven-day local history with CSV export.

## Measurement limits
Android hardware and firmware determine which battery properties are exposed. Unsupported values are shown as unavailable, not fabricated. Current is an instantaneous device estimate, not a calibrated external meter. Battery health is the Android-reported health state, not a battery-capacity or aging test. Forecasts are estimates.

History is sampled at most once per minute while the app is open. There is no background service; continuous logging after the app is closed is not promised.

## Build
Open this directory in Android Studio with JDK 17 and Android SDK 35, or run gradle tasks:
- gradle :app:assembleDebug
- gradle :app:testDebugUnitTest

The debug APK is at app/build/outputs/apk/debug/app-debug.apk.

## Compatibility
minSdk 23, targetSdk 35. No runtime permissions or network access required.
