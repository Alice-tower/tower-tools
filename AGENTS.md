# Tower Tools repository rules

- This repository contains personal Windows x64 GUI tools written with Kotlin/JVM and Compose Desktop.
- Every tool must present a GUI, even when its primary work is command-line oriented.
- New tools live under `apps/` and must be created with `scripts/New-Tool.ps1` from `template/windows-compose-app`.
- Tools must be removed with `scripts/Remove-Tool.ps1`; add/remove/rename operations must keep the launcher catalog in sync and rebuild the launcher portable output.
- Application IDs are stable and use the `dev.towertools.*` namespace. Do not change a released ID.
- Portable outputs belong only under `outputs/` and must never be committed.
- Runtime configuration and logs belong under `%LOCALAPPDATA%\Alice-tower\...`; rebuilding must not overwrite user data.
- Read the relevant document under `docs/` before changing architecture, naming, the catalog, lifecycle scripts, or build behavior.

Detailed references:

- `docs/architecture.md`
- `docs/project-conventions.md`
- `docs/tool-lifecycle.md`
- `docs/launcher-catalog.md`
- `docs/build-environment.md`
