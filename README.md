# Tower Tools

Tower Tools is Alice-tower's Windows-only collection of small GUI utilities. Every application uses Kotlin/JVM and Compose Desktop and is packaged as a self-contained portable directory.

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
