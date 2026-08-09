# 工具塔

集中浏览并启动 Tower Tools 便携工具。

- Project: `TowerLauncher`
- Application ID: `dev.towertools.launcher`
- Version: `1.0.0`
- Author: `Alice-tower`

## Run

```powershell
.\gradlew.bat run
```

## Build portable directory

```powershell
.\gradlew.bat createDistributable
```

The repository lifecycle scripts copy the resulting Windows application image into the shared `outputs/` directory.
