# 启动器 Tab 开发规范

本文适用于编译进 `launcher/TowerLauncher/` 的功能页。需求明确写“新工具”时走 [独立工具生命周期](tool-lifecycle.md)；明确写“新 Tab”时按本文开发。两者都合理而需求未说明时，先确认交付形态。

## 工具与 Tab 的边界

| | 独立工具 | 启动器 Tab |
| --- | --- | --- |
| 运行 | 自己的窗口、进程、Application ID 和便携目录 | 共用 `TowerLauncher.exe` 的窗口、进程、Application ID 与便携目录 |
| 源码 | `apps/<directory-name>/`，独立 Gradle 项目 | `launcher/TowerLauncher/src/main/kotlin/dev/towertools/launcher/tabs/<feature>/`，属于启动器 Gradle 项目 |
| 注册 | `tool.json` 经脚本生成 `catalog/tools.json`，显示在“工具”Tab | `TabId` 枚举和 `Main.kt` 中的 `LauncherTab` 列表，编译时注册 |
| 新业务的用户数据 | `%LOCALAPPDATA%\Alice-tower\TowerTools\<application-id>\` | `%LOCALAPPDATA%\Alice-tower\TowerLauncher\tabs\<stable-key>\` |
| 构建 | `Build-Tool.ps1`；新增或删除按 `New-Tool.ps1`、`Remove-Tool.ps1` | `Build-Launcher.ps1`；不使用工具创建或删除脚本 |

新 Tab 不创建 `apps/` 项目、`tool.json`、独立 EXE 或新的 Application ID，也不写入 `catalog/tools.json`。当前注册方式是编译时组合；不把 Tab 当成可从外部目录动态加载的插件。

## 标识、注册与代码边界

1. 在 `LauncherApp.kt` 的 `TabId` 枚举中增加一个语义明确的枚举值和界面标题。枚举值负责页面选择；它不是工具 Application ID。若以后将某个标识写入用户配置，须单独定义稳定的数据键，变更时提供迁移。
2. 在 `Main.kt` 的 `LauncherTab` 列表中注册页面。保持“工具”Tab 为默认入口，除非需求明确改变。宿主只负责窗口、主题、单实例、Tab 导航和构造所需对象。
3. 把新业务的页面、状态、模型、读写和操作放在 `tabs/<feature>/` 包，测试放在对应的 `src/test/.../tabs/<feature>/` 包。Tab 内部可以按功能再拆文件；不要把业务逻辑堆进 `Main.kt` 或 `LauncherApp.kt`。
4. Tab 之间不直接读写彼此的状态或配置。“工具”Tab 的分类、收藏和 `CatalogRepository` 只管理独立工具。确有共同界面需求时再提取不含业务含义的组件；新 Tab 可自行决定页面布局，不必套用工具分类侧栏。

目前的 `TOOLS`、`CMD` 与 `REPOSITORIES` 分别管理生成目录与用户工具设置、用户登记的 `.cmd` 项目及其分类、排序、收藏设置、用户登记的扫描路径及其上次 Git 仓库扫描结果和独立的仓库分类、排序、收藏、展示名称、按钮样式设置，各自维护数据与操作边界。

## 页面状态与后台工作

- 窗口和系统明暗主题由宿主提供；Tab 提供可组合的页面，并自行处理输入、空状态、错误、操作反馈与必要的确认。
- 需要在切换 Tab 后仍保留的筛选、列表或编辑状态，由该 Tab 定义状态对象，再由宿主持有并传回页面。是否保留未保存的表单内容，应按该业务的需求明确决定。
- `LaunchedEffect`、`rememberCoroutineScope` 等页面作用域会在 Tab 离开组合时结束。轮询、临时任务可以跟随页面停止；如果某项工作必须跨 Tab 切换继续，由该 Tab 设计长生命周期的状态和作用域，不把所有业务任务默认交给全局管理。
- 涉及文件、网络或外部进程的较慢工作应避免阻塞界面。失败应在所属 Tab 中可见，不能把配置读取失败默认为可覆盖的空数据。

## 用户数据与兼容

新 Tab 的数据目录使用 `%LOCALAPPDATA%\Alice-tower\TowerLauncher\tabs\<stable-key>\`；`<stable-key>` 采用稳定的小写短名，并在该 Tab 的路径代码中集中定义。Tab 数据归属启动器，子目录名直接表明对应业务，不借用工具的 Application ID。运行时数据、缓存与日志不得写在源码或 `outputs/` 旁。`user-settings.json` 的 `tools` 字段只属于“工具”Tab，不作为新 Tab 的通用配置容器。

更新启动器不得覆盖用户数据。配置解析失败时应停止写入并显示错误，避免以空数据覆盖原文件。若从独立工具迁入 Tab，先核对原配置格式和路径，迁移过程须可重复执行、不覆盖已有 Tab 配置，并保留旧文件供核对。CMD Tab 将项目登记与分类偏好分别写入 `TowerLauncher\tabs\cmd\projects.properties` 和 `TowerLauncher\tabs\cmd\appearance.properties`。

## 开发与交付顺序

1. 确定 Tab 名称、`TabId`、业务包、数据目录、页面离开时的任务行为和需要保留的状态。
2. 实现该包的业务与页面，并针对数据格式、关键操作和失败保护添加必要测试；再注册 Tab。
3. 更新启动器版本（`AppMetadata.kt`、`build.gradle.kts`、`tool.json`）、启动器 README、根目录 README 和受影响的架构文档。仅文档改动无需改版本。
4. 按 [构建环境](build-environment.md) 的方式运行 `scripts/Build-Launcher.ps1`，确认测试和便携输出。新增或升级第三方依赖时，还须核对实际打包组件和许可，并更新 `THIRD_PARTY_NOTICES.md` 及必要的许可文本。
5. 若 Tab 取代一个独立工具，先让新页面通过验证，再用 `scripts/Remove-Tool.ps1` 删除旧工具并同步目录、重建启动器；保留用户数据及已发布 ID 的历史归属。单纯删除一个 Tab 时，移除枚举值、注册和业务包，更新文档并构建启动器；用户数据是否迁移或删除由具体需求决定。

生命周期脚本均使用 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File` 调用，并遵守仓库构建权限与 Gradle 缓存规则。
