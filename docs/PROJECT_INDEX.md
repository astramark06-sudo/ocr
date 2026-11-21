# Repository project index

This repository is a multi-project workspace. Keep each project's source, dependencies, build process and release history separate.

## Projects

### AI Operations Platform (Python)
- Root README: [../README.md](../README.md)
- Source: `src/aiops/`
- Tests: `tests/`
- Packaging/config: `pyproject.toml`, `configs/`
- Python CI: `.github/workflows/ci.yml`
- Security scanning: `.github/workflows/codeql.yml`

### BatteryScope (Android)
- Project root: [../BatteryScope/](../BatteryScope/)
- User/technical guide: [../BatteryScope/README.md](../BatteryScope/README.md)
- Engineering audit: [../BatteryScope/AUDIT.md](../BatteryScope/AUDIT.md)
- Release history: [../BatteryScope/CHANGELOG.md](../BatteryScope/CHANGELOG.md)
- Release gate: [../BatteryScope/RELEASE_CHECKLIST.md](../BatteryScope/RELEASE_CHECKLIST.md)
- Android source: `BatteryScope/app/src/main/`
- APK CI: `.github/workflows/batteryscope-build.yml`
- Target device: Realme Narzo 20 Pro (RMX2161), Android 12, non-rooted.

## Working rules
- Read [../AGENTS.md](../AGENTS.md) before making repository-wide changes.
- Read [../BatteryScope/AGENTS.md](../BatteryScope/AGENTS.md) before BatteryScope changes.
- Do not mix Python CI and Android APK release procedures.
- Keep personal datasets, local exports, credentials, APKs and build outputs out of this public repository.
- Use the source audit to distinguish what the handset exposes from what is inferred from reference kernel code.
