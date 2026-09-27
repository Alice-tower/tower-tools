# 本地项目启动器

登记并手动启动多个本地 `.cmd` 项目。首次打开不会自动运行脚本；没有旧设置时项目列表为空。

- 项目：`ResearchLibraryLauncher`
- Application ID：`dev.towertools.researchlibrarylauncher`（沿用旧 ID，保留用户数据）
- 版本：`1.1.1`
- 作者：`Alice-tower`

在界面中可添加、编辑和删除项目登记，并按名称或路径搜索。每项填写名称和 `.cmd` 完整路径；需要浏览器访问的项目还可勾选“本地 Web 服务”并填写端口。列表每 3 秒检测一次 `127.0.0.1` 的连接状态，用彩色圆点显示“可连接／未连接”，并提供 `http://127.0.0.1:<端口>/` 的“打开浏览器”按钮。可连接不代表登记的项目已经运行。项目行的“更多”菜单可打开目录、编辑或删除登记；删除登记不会删除脚本或项目目录。

项目配置保存在 `%LOCALAPPDATA%\Alice-tower\TowerTools\dev.towertools.researchlibrarylauncher\projects.properties`，重新构建后保留。如果旧版 `settings.properties` 中保存过研究资料库脚本路径，首次读取时会将它作为“研究资料库”项目导入，并保留端口 `4173`。旧设置文件不会删除。

## 运行

```powershell
.\gradlew.bat run
```

## 构建便携目录

在仓库根目录运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Build-Tool.ps1 -Id "dev.towertools.researchlibrarylauncher"
```

便携应用位于 `outputs/tools/dev.towertools.researchlibrarylauncher/`。
