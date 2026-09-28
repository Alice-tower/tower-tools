# Tower Tools

Tower Tools 是 Alice-tower 面向 Windows 的小型 GUI 工具集合，使用 Kotlin/JVM 和 Compose Desktop。`apps/` 下的工具是独立便携应用；启动器自身也是便携应用，并可包含内置 Tab。

本仓库的原创代码采用 [MIT 许可证](LICENSE)。便携包中第三方组件的许可与来源见 [第三方声明](THIRD_PARTY_NOTICES.md)；仓库构建脚本会将声明和许可文本复制到便携目录。

## 使用便携应用

运行 `outputs\launcher\TowerLauncher.exe` 可在“工具”Tab 中浏览并启动已打包的工具，在“CMD”Tab 中登记、分类、排序、收藏和启动本机 `.cmd` 项目，在“仓库”Tab 的设置中登记目录、手动刷新，并按分类、排序和收藏浏览已扫描出的 Git 仓库。三个 Tab 的列表条目等高、收藏爱心对齐；双击工具或 CMD 条目可启动，双击仓库条目可打开目录，卡片不显示对应的启动或打开按钮。CMD 的“打开浏览器”按钮仍可访问项目 Web 端口。CMD 条目在标题下显示路径，宽度不足时按实际文字宽度省略中间部分并保留文件名末尾。仓库条目可通过右键菜单设置展示名称：有名称时以其为主标题、目录名为副标题，否则只显示目录名；不显示详细路径。每个仓库条目末端有带 GitHub 图标的 GITHUB 按钮，识别到 GitHub 远端时可点击打开网页，否则置灰；其右侧带 VS Code 图标的按钮可在新窗口打开该仓库目录，未找到 VS Code 时会显示错误原因。每个仓库条目还提供 README 和 AGENTS 按钮，用系统默认程序打开仓库目录根部的对应 Markdown 文件；文件不存在时按钮置灰。右键菜单在鼠标点击处打开，前两项依次为“打开所在目录”和“编辑分类和排序”；长按条目不触发操作。`outputs/` 下的完整目录可整体携带；每个应用都包含精简的 Java runtime，因此必须整体复制。

当前工具：

| 工具 | 用途 | Application ID |
| --- | --- | --- |
| 代理环境变量 | 查看、设置或删除当前用户的 `HTTP_PROXY`、`HTTPS_PROXY`、`ALL_PROXY` 与 `NO_PROXY`。 | `dev.towertools.proxyenvmanager` |
| 图片裁剪与分割 | 对单张图片进行旋转、裁剪、预览和分割，不修改原图。 | `dev.towertools.imageprocessor` |
| 图片元数据解析器 | 本地解析 NovelAI、A1111、角色卡和 PNG/JPEG/WebP 图片元数据，查看内嵌 PNG。 | `dev.towertools.naibox` |
| 影音转写 | 从媒体中提取 MP3，并借助本地 Whisper CUDA 模型转写为 TXT。 | `dev.towertools.mediatranscriber` |
| 本地资源语义管理器 | 扫描指定目录下 Bucket 内的资源，以标签和别名组织文件、目录，处理缺失与重新定位。 | `dev.towertools.resourcetagger` |

## 仓库结构

- `apps/`：独立工具项目。
- `template/windows-compose-app/`：新工具的源模板。
- `launcher/TowerLauncher/`：名为“工具塔”的启动器，内置工具、CMD 和仓库 Tab。
- `catalog/tools.json`：由生命周期脚本维护的源目录。
- `branding/`：用户绘制的统一图标原图和缩小后的 Windows 图标资源。
- `scripts/`：创建、删除、构建和目录同步脚本。
- `outputs/`：生成的便携应用，已被 Git 忽略。
- `docs/`：详细的约定和架构说明。

## 开发新功能

- **新工具**：需要独立窗口、进程和便携 EXE 时，在 `apps/` 下按 [工具生命周期](docs/tool-lifecycle.md) 创建。
- **新 Tab**：功能直接出现在工具塔窗口中时，在启动器内按 [Tab 开发规范](docs/launcher-tabs.md) 注册和实现；业务数据不写入工具目录。

提出新功能时请明确选择“新工具”或“新 Tab”。两类功能的源码、标识、用户数据和构建流程分别维护。

## 常用命令

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\New-Tool.ps1 -DisplayName "示例工具" -ProjectName "ExampleTool" -Description "用途说明"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Build-Tool.ps1 -Id "dev.towertools.exampletool"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Build-Launcher.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Build-All.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Remove-Tool.ps1 -Id "dev.towertools.exampletool"
```

构建会复用仓库本地 `.gradle-user-home/` 缓存，且默认采用增量构建。仅在确实需要干净重建时，才为构建命令传入 `-Clean`。

启动器和所有工具共用 `branding/` 下的图标。新工具由模板创建时会自动带上相同图标；现有应用的可执行文件、窗口和 Windows 任务栏也使用它。便携目录中另有 `app-icon.ico`，可为桌面快捷方式明确指定图标。

新增或删除独立工具前，请阅读 [工具生命周期](docs/tool-lifecycle.md)；开发 Tab 前，请阅读 [Tab 开发规范](docs/launcher-tabs.md)。工具页的分类、分类拖拽顺序、工具排序与收藏为用户专属设置，存储于 `%LOCALAPPDATA%`；重新构建便携应用不会覆盖这些设置。详见 [启动器目录](docs/launcher-catalog.md)。
