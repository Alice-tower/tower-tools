# 工具塔

集中浏览并启动 Tower Tools 便携工具。

- 项目：`TowerLauncher`
- Application ID：`dev.towertools.launcher`
- 版本：`1.0.2`
- 作者：`Alice-tower`

## 运行

```powershell
.\gradlew.bat run
```

## 构建便携目录

```powershell
.\gradlew.bat createDistributable
```

仓库生命周期脚本会将生成的 Windows application image 复制到共享的 `outputs/` 目录。
