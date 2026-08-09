# Tower Tools

Tower Tools is Alice-tower's Windows-only collection of small GUI utilities. Every application uses Kotlin/JVM and Compose Desktop and is packaged as a self-contained portable directory.

## Use the portable applications

Run `outputs\launcher\TowerLauncher.exe` to browse and start the packaged tools. The complete directory below `outputs/` is portable and must be copied as a unit because every application includes its own trimmed Java runtime.

Current tools:

| Tool | Purpose | Application ID |
| --- | --- | --- |
| 代理环境变量 | View, set, or remove the current user's `HTTP_PROXY` and `HTTPS_PROXY` values. | `dev.towertools.proxyenvmanager` |

## Repository layout

- `apps/` contains independent tool projects.
- `template/windows-compose-app/` is the source template for new tools.
- `launcher/TowerLauncher/` is the lightweight launcher named **工具塔**.
- `catalog/tools.json` is the source catalog maintained by the lifecycle scripts.
- `scripts/` contains create, remove, build, and catalog commands.
- `outputs/` contains generated portable applications and is ignored by Git.
- `docs/` contains detailed conventions and architecture notes.

## Common commands

```powershell
.\scripts\New-Tool.ps1 -DisplayName "示例工具" -ProjectName "ExampleTool" -Description "用途说明"
.\scripts\Build-Tool.ps1 -Id "dev.towertools.exampletool"
.\scripts\Build-Launcher.ps1
.\scripts\Build-All.ps1
.\scripts\Remove-Tool.ps1 -Id "dev.towertools.exampletool"
```

See `docs/tool-lifecycle.md` before adding or removing a tool manually.

Launcher categories and ordering are user-owned settings stored below `%LOCALAPPDATA%`; rebuilding portable applications does not overwrite them. See `docs/launcher-catalog.md` for details.
