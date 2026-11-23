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
- [ ] Add/update automated tests for arithmetic and state classification where feasible.

## Build and artifact
- [ ] Increment versionCode and update versionName for a release.
- [ ] GitHub Actions Android build succeeds for the exact final commit.
- [ ] APK package ID and version metadata are verified with aapt.
- [ ] Workflow artifact is downloaded from that exact run.
- [ ] Artifact ZIP integrity, APK entry, nonzero size and SHA-256 are verified.
- [ ] Record the exact branch, commit, run ID/number, artifact name, APK size and APK SHA-256.
- [ ] Verify install-over-update compatibility when device testing is available.

## Physical RMX2161 validation — required before claiming device-tested
- [ ] Install/launch and verify update compatibility.
- [ ] Verify battery current/voltage/temperature/charge counter against system sources where available.
- [ ] Verify ordinary-app telemetry while unplugged and charging; unavailable paths remain explicitly unavailable.
- [ ] Confirm USB source identity and validate any VBUS/IBUS paths and units.
- [ ] Test recorder screen-off, multi-hour logging, notification, wake-lock power impact and gap diagnostics.
- [ ] Test reboot resume, force-stop behavior and Realme battery-management restrictions.
- [ ] Test Usage Access and foreground-app attribution.
- [ ] Verify the capacity estimator across several normal charge/discharge sessions; compare session spread and confidence.
- [ ] Test CSV content, chronological ordering, timestamps, SAF export and persistence of app-private copy.

## Final report
Report the exact commit/version, workflow run, artifact and checksums. Mark unperformed checks explicitly. A successful CI build is not proof of physical-device correctness, 24-hour continuity or real charger-side measurements.
