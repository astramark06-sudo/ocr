# BatteryScope release checklist

Use this checklist for every APK release. A source commit is not itself a release.

## Before editing
- [ ] Confirm the active branch is `batteryscope`.
- [ ] Read current source, audit and open diffs.
- [ ] Identify impacted screens, data paths, AIDL contracts and shared helpers.
- [ ] Keep the change scoped; preserve unrelated working features.

## Source and documentation
- [ ] Update the existing implementation rather than starting a replacement app.
- [ ] Keep package/application ID `com.batteryscope`.
- [ ] Update `AUDIT.md` for evidence, behavior or capability changes.
- [ ] Update `README.md` for user-visible behavior or setup changes.
- [ ] Update `CHANGELOG.md`.
- [ ] Add/update automated checks where possible.
- [ ] Check no secrets, device-private data, APKs or build outputs entered Git.

## Build and artifact
- [ ] Increment versionCode; update versionName.
- [x] GitHub Actions build succeeds on the intended branch and commit (run #91; commit `700e6ac63be78f65a2d08b45bef367b79badef03`).
- [x] Build job's APK verification step passes.
- [x] Artifact upload succeeds.
- [x] Download the artifact from that exact run.
- [x] Inspect ZIP contents; confirm expected APK path and nonzero size.
- [x] Compute SHA-256 and record it with the exact commit/run.
- [x] CI aapt verification confirms manifest application ID `com.batteryscope` and APK metadata version 1.8 / versionCode 9.
- [ ] Confirm artifact is installable as an update over the prior package when device testing is available.

## Device validation (report separately)
- [ ] Install/update on RMX2161 / Android 12.
- [ ] Check launcher icon and app launch.
- [ ] Check telemetry while unplugged and while charging.
- [ ] Compare raw values with Android system sources where available.
- [ ] Test Shizuku permission, bind, audit and live polling.
- [ ] Confirm unavailable/denied data never appears as zero.
- [ ] Confirm control gates remain locked when write permission is absent.
- [ ] Test CSV save, cancel, export and file contents.
- [ ] Check log/report lifecycle and app restart behavior.

## Final report
Report exact commit, version, workflow run, artifact name/size/hash and each verification result. Explicitly mark checks not performed. Never call a release device-tested based only on CI.
