# Tower Tools

Tower Tools 是 Alice-tower 面向 Windows 的小型 GUI 工具集合。每个应用均使用 Kotlin/JVM 和 Compose Desktop 编写，并打包为可独立运行的便携目录。

本仓库的原创代码采用 [MIT 许可证](LICENSE)。便携包中包含的第三方组件仍遵循各自的许可证；分发便携包时应一并提供适用的许可声明。

## 使用便携应用

运行 `outputs\launcher\TowerLauncher.exe` 可浏览并启动已打包的工具。`outputs/` 下的完整目录可整体携带；每个应用都包含精简的 Java runtime，因此必须整体复制。

当前工具：

| 工具 | 用途 | Application ID |
| --- | --- | --- |
| 代理环境变量 | 查看、设置或删除当前用户的 `HTTP_PROXY`、`HTTPS_PROXY`、`ALL_PROXY` 与 `NO_PROXY`。 | `dev.towertools.proxyenvmanager` |
| 图片裁剪与分割 | 对单张图片进行旋转、裁剪、预览和分割，不修改原图。 | `dev.towertools.imageprocessor` |
| 影音转写 | 从媒体中提取 MP3，并借助本地 Whisper CUDA 模型转写为 TXT。 | `dev.towertools.mediatranscriber` |
| 本地项目启动器 | 登记并手动启动本地 CMD 项目，可查看 Web 端口状态。 | `dev.towertools.researchlibrarylauncher` |

## 仓库结构

- `apps/`：独立工具项目。
- `template/windows-compose-app/`：新工具的源模板。
- `launcher/TowerLauncher/`：名为“工具塔”的轻量启动器。
- `catalog/tools.json`：由生命周期脚本维护的源目录。
- `scripts/`：创建、删除、构建和目录同步脚本。
- `outputs/`：生成的便携应用，已被 Git 忽略。
- `docs/`：详细的约定和架构说明。

## 常用命令

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\New-Tool.ps1 -DisplayName "示例工具" -ProjectName "ExampleTool" -Description "用途说明"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Build-Tool.ps1 -Id "dev.towertools.exampletool"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Build-Launcher.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Build-All.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Remove-Tool.ps1 -Id "dev.towertools.exampletool"
```

构建会复用仓库本地 `.gradle-user-home/` 缓存，且默认采用增量构建。仅在确实需要干净重建时，才为构建命令传入 `-Clean`。

新增或删除工具前，请阅读 `docs/tool-lifecycle.md`。启动器的分类、排序与收藏为用户专属设置，存储于 `%LOCALAPPDATA%`；重新构建便携应用不会覆盖这些设置。详见 `docs/launcher-catalog.md`。
