# 工具塔

集中浏览并启动 Tower Tools 便携工具。

- 项目：`TowerLauncher`
- Application ID：`dev.towertools.launcher`
- 版本：`1.0.4`
- 作者：`Alice-tower`

侧边栏的“收藏”位于“全部”上方；启动时如果有收藏工具则默认显示收藏，否则显示全部。在“编辑分类和排序”对话框右上角可切换收藏状态，界面会提示待保存，点击“保存更改”后生效。

分类、排序和收藏保存时会先写入临时文件，再原子替换用户配置。现有配置无法读取时会取消保存，并在编辑对话框显示错误，保留原文件。

重复启动会通知现有窗口恢复并聚焦；激活连接设有读超时，避免无响应连接长期阻塞。

窗口与 Windows 可执行文件使用仓库统一图标。
启动时以稳定 Application ID 登记 Windows 任务栏身份，避免任务栏显示 Compose 默认图标。便携目录中的 `app-icon.ico` 可作为桌面快捷方式的显式图标来源。

## 运行

```powershell
.\gradlew.bat run
```

## 构建便携目录

```powershell
.\gradlew.bat createDistributable
```

仓库生命周期脚本会将生成的 Windows application image 复制到共享的 `outputs/` 目录。
