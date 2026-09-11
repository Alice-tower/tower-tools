# 工具塔

集中浏览并启动 Tower Tools 便携工具。

- 项目：`TowerLauncher`
- Application ID：`dev.towertools.launcher`
- 版本：`1.0.3`
- 作者：`Alice-tower`

分类和排序保存时会先写入临时文件，再原子替换用户配置。现有配置无法读取时会取消保存，并在编辑对话框显示错误，保留原文件。

重复启动会通知现有窗口恢复并聚焦；激活连接设有读超时，避免无响应连接长期阻塞。

## 运行

```powershell
.\gradlew.bat run
```

## 构建便携目录

```powershell
.\gradlew.bat createDistributable
```

仓库生命周期脚本会将生成的 Windows application image 复制到共享的 `outputs/` 目录。
