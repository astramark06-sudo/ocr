# BatteryScope release checklist

A source commit is not a release. Keep build verification, artifact integrity, installation and physical-device validation as separate results.

## Before release
- [ ] Confirm the active branch is batteryscope and inspect the complete diff from the previous verified checkpoint.
- [ ] Check all app files, manifest permissions/components, database schema/export, capacity-estimator logic, foreground-service lifecycle and boot receiver.
- [ ] Keep application ID / namespace com.batteryscope.
- [ ] Do not alter the separate Python project at the repository root.
- [ ] Confirm no Shizuku dependency/provider/AIDL, ADB setup, root or companion-app requirement exists.
- [ ] Confirm BatteryScope performs no sysfs or charging-control writes.

## Source and documentation
- [ ] Keep README.md, AUDIT.md, CHANGELOG.md, PROJECT_STATUS.md and this checklist consistent with the current code.
- [ ] Preserve source paths and raw readings; missing/denied is not zero.
- [ ] Check for secrets, device-private identifiers, APKs and build outputs in Git.
- [x] Added eight measurement-quality tests; all 22 JVM tests pass.

## Build and artifact
- [x] Increment versionCode and update versionName: 1.15 / code 16.
- [x] GitHub Actions Android build succeeds for app source commit 480a567cd7c90f552976dc60472fa6ef11a5e126 (run 45223861385).
- [x] APK package ID and version metadata are verified with aapt: com.batteryscope / 1.15 / code 16.
- [x] Workflow artifact 14840939491 downloaded from that exact run.
- [x] Artifact ZIP integrity, APK entry, nonzero size and SHA-256 are verified.
- [x] Recorded exact branch, commit, run, artifact, APK size and APK SHA-256 in FORENSIC_AUDIT_2026-11-23.md.
- [ ] Verify install-over-update compatibility when device testing is available.

## Physical RMX2161 validation — required before claiming device-tested
- [ ] Install/launch and verify update compatibility.
- [ ] Verify battery current/voltage/temperature/charge counter against system sources where available.
- [ ] Confirm current polarity agrees with Android BatteryManager semantics during both charging and unplugged discharge; invalid values must be flagged and excluded from wattage.
- [ ] Confirm raw voltage is a valid mV reading before battery-side wattage is displayed.
- [ ] Confirm quality-transition events are present in CSV and foreground UsageStats events preserve source timestamps.
- [x] Compared v1.15 and v1.14 debug signing certificates; they differ, so in-place update is not safe.
- [ ] Verify ordinary-app telemetry while unplugged and charging; unavailable paths remain explicitly unavailable.
- [ ] Confirm USB source identity and validate any VBUS/IBUS paths and units.
- [ ] Test recorder screen-off, multi-hour logging, notification, wake-lock power impact and gap diagnostics.
- [ ] Test reboot resume, force-stop behavior and Realme battery-management restrictions.
- [ ] Test Usage Access and foreground-app attribution.
- [ ] Verify the capacity estimator across several normal charge/discharge sessions; compare session spread and confidence.
- [ ] Test CSV content, chronological ordering, timestamps, SAF export and persistence of app-private copy.

## Final report
Report the exact commit/version, workflow run, artifact and checksums. Mark unperformed checks explicitly. A successful CI build is not proof of physical-device correctness, 24-hour continuity or real charger-side measurements.


## Verified v1.15 build record

- Workflow: https://github.com/astramark06-sudo/test/actions/runs/45223861385
- App source commit: `480a567cd7c90f552976dc60472fa6ef11a5e126`
- APK SHA-256: `0c8f37eb4777b9ece9a1241a85e4805bab06dd5fd4b0bdde339f748c384b32ac`
- Signer certificate SHA-256: `fe463192e3a055244ed627fbd8a8907f535263166e619c5bca686924b15094c2`
- Existing v1.14 debug certificate: `3c75edb5262b17164453949bb90113e133edc2daeceb10846cd233d2be8194b5` — mismatch; do not install over the existing app.
- Physical RMX2161 validation and stable production signing remain incomplete.
