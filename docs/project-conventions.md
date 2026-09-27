# 项目约定

## 技术栈

- Windows 10 或更新版本，x64
- JDK 21 toolchain
- Kotlin/JVM
- Compose Desktop
- 在可行时使用稳定依赖
- UTF-8 源文件

## 标识

每个独立工具包含四个不同的标识字段：

- `displayName`：面向用户的中文名称。
- `projectName`：用于 Gradle 项目和可执行文件的 PascalCase 英文名称。
- `id`：位于 `dev.towertools` 下、稳定的小写 Application ID。
- `directoryName`：位于 `apps/` 下的 kebab-case 源目录名称。

独立工具的 Kotlin package 与 Gradle group 均等于 Application ID。ID 发布后不得复用给其他工具，也不得修改。

启动器 Tab 使用 `TabId` 枚举值标识页面，在 `launcher/TowerLauncher/src/main/kotlin/dev/towertools/launcher/tabs/<feature>/` 中维护业务包。Tab 共用 `dev.towertools.launcher` 的应用身份，没有自己的 `tool.json`、独立 Application ID 或 Gradle group。若 Tab 的标识进入持久化数据，须另设稳定数据键并处理后续迁移；详见 [Tab 开发规范](launcher-tabs.md)。

作者/vendor 为 `Alice-tower`。仓库原创代码采用根目录 `LICENSE` 中的 MIT 许可证；第三方组件保留各自的许可证。

## 版本

应用从 `1.0.0` 开始，并使用 semantic version。成功完成 staged build 后，同一 Application ID 的便携输出会替换旧输出。

Tab 的行为变化随启动器一起递增版本，不单独维护应用版本；仅文档变更无需改版本。

## 用户界面

所有工具必须提供 GUI。默认模板提供中文界面、系统明暗主题支持、标准窗口尺寸、可见的错误区域、日志与单实例激活。

小型应用应尽量保持结构简单；只有在功能集需要时才添加架构层。

Tab 共用启动器窗口和主题，但页面布局、状态、错误提示与后台任务由各自业务管理。分类侧栏属于工具 Tab，不是所有 Tab 的必备界面。

## 统一图标

启动器及所有工具使用 `branding/` 中的同一张绘制图。保留 `tower-tools-original.png` 作为原图，`app-icon.png` 为 512 × 512 的窗口资源，`app-icon.ico` 包含 16、24、32、48、64、128、256 像素的 Windows 图标。

每个项目在 `src/main/resources/app-icon.png` 放置窗口图标，在 `icons/app-icon.ico` 放置便携 EXE 图标。`template/windows-compose-app` 包含同样的文件和配置，新建工具时由 `New-Tool.ps1` 一起复制。更新统一图标时须同步模板、启动器及现有工具中的两份资源，并重新构建便携输出。

主程序在创建窗口前以稳定 Application ID 设置 Windows AppUserModelID，确保任务栏将窗口归为该应用并显示其图标。新工具不得省略模板中的这一步。

## Agent 指令

所有项目均继承仓库级 `AGENTS.md`。默认不要在每个工具内创建另一个 `AGENTS.md`；仅当某个工具具有不同于仓库默认规则的长期约束时，才添加工具专属文件。
