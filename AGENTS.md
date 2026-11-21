# Repository working rules

## Repository identity
This repository contains more than one project. The root project is the AI Operations Platform (Python). BatteryScope is a separate Android application under `BatteryScope/`. Do not merge their build systems, dependencies, release numbering or runtime assumptions.

## Project selection
- For BatteryScope tasks, work only in `BatteryScope/` and the dedicated `.github/workflows/batteryscope-build.yml`, plus explicitly relevant shared documentation.
- For AI Operations Platform tasks, work in its Python source, tests, configuration and Python CI.
- Never perform broad cleanup or restructure an unrelated project as part of a focused task.

## Change discipline
1. Read the current file and its callers before editing.
2. Prefer the smallest targeted change that fixes the requested issue.
3. Preserve public interfaces, package identity, stored data formats and existing behavior unless the task explicitly requires a change.
4. Update the relevant tests and documentation with behavior changes.
5. Do not claim a build, test, install, runtime behavior or device validation unless that exact check ran and passed.
6. Keep generated APKs, build directories, local databases, datasets, credentials and user data out of Git.
7. Never add secrets, signing keys, tokens, private diagnostic dumps or personal device exports.
8. Use a new app versionCode for every distributable BatteryScope APK. Preserve applicationId `com.batteryscope` so updates replace the existing app.
9. Record known limitations and unverified assumptions explicitly. Never convert missing telemetry into zero or infer proprietary charging protocol state from unrelated driver flags.

## BatteryScope source of truth
- Android source: `BatteryScope/app/src/main/`
- Android build config: `BatteryScope/*.gradle.kts` and `BatteryScope/gradle.properties`
- Product documentation: `BatteryScope/README.md`
- Engineering evidence and limits: `BatteryScope/AUDIT.md`
- Release history: `BatteryScope/CHANGELOG.md`
- Release gate: `BatteryScope/RELEASE_CHECKLIST.md`
- CI build: `.github/workflows/batteryscope-build.yml`

## BatteryScope release procedure
1. Inspect the current branch and diff; do not start a replacement project.
2. Make the focused change and update docs/tests.
3. Increment `versionCode` and `versionName` for a distributable APK.
4. Push to `batteryscope`; the APK workflow runs on changes under `BatteryScope/**` or its workflow.
5. Verify the exact run, job steps, APK artifact, ZIP contents and SHA-256 before sharing a download.
6. State separately whether CI build, artifact inspection, installation and physical-device testing passed.
7. Keep the latest release artifact link and its SHA in the handoff/release notes only after artifact verification.

## Current BatteryScope device target
Realme Narzo 20 Pro (RMX2161), Android 12, non-rooted. Shizuku uses the ADB-shell privilege level unless the user explicitly changes the device setup. Do not assume root access.
