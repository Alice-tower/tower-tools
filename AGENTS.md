# Tower Tools 仓库规则

- 本仓库收录面向 Windows x64 的个人 GUI 工具，使用 Kotlin/JVM 与 Compose Desktop 编写。
- 每个工具都必须提供 GUI，即使其核心功能本质上是命令行工作流。
- 新工具必须位于 `apps/` 下，并通过 `scripts/New-Tool.ps1` 及 `template/windows-compose-app` 创建。
- 工具必须通过 `scripts/Remove-Tool.ps1` 删除；新增、删除或重命名工具时，必须同步启动器目录并重新构建启动器的便携输出。
- 用户明确要求“新工具”时创建独立应用；明确要求“新 Tab”时在启动器内开发。需求未说明且两者都合理时先澄清，具体边界见 `docs/launcher-tabs.md`。
- 新 Tab 的业务代码与测试必须放在启动器对应的 `tabs/<feature>/` 包，通过 `TabId` 和 `LauncherTab` 编译时注册；不为 Tab 创建 `apps/` 项目、`tool.json`、独立 Application ID 或工具目录条目。Tab 内状态、数据和任务由该业务管理。
- 新 Tab 的业务数据默认放在 `%LOCALAPPDATA%\Alice-tower\TowerLauncher\tabs\<stable-key>\`；`<stable-key>` 是稳定的小写短名，由该 Tab 自己维护。旧数据迁移不得覆盖现有 Tab 配置。
- Application ID 必须稳定，并使用 `dev.towertools.*` 命名空间。已发布的 ID 不得变更。
- 便携输出只能位于 `outputs/`，且不得提交到 Git。
- 新增或升级第三方依赖时，发布前须核对实际打包组件及其许可，更新 `THIRD_PARTY_NOTICES.md` 和必要的 `third-party/licenses/` 文本；仓库根目录的 MIT `LICENSE` 仅适用于原创代码，不得用它替代第三方许可。
- 运行时配置和日志必须位于 `%LOCALAPPDATA%\Alice-tower\...`；重新构建不得覆盖用户数据。
- 必须通过 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File` 调用生命周期脚本，避免仓库工作依赖机器的 PowerShell 执行策略。
- 生命周期脚本拥有位于 `.gradle-user-home/` 的共享 Gradle user home；不得为仓库构建覆盖 `GRADLE_USER_HOME`。
- 在受管沙箱中，运行会调用 Gradle 的生命周期脚本前，必须先请求构建权限，不得等到 Gradle 子进程写入失败后才处理。
- 修改架构、命名、目录、生命周期脚本或构建行为前，必须先阅读 `docs/` 下的相关文档。
- 每次完成会改变项目行为、工具元数据、版本或仓库结构的 Git 提交后，必须检查根目录 `README.md` 与每个受影响组件（`apps/` 或 `launcher/` 下）的 README。若提交使文档不再准确，必须在后续提交中更新；仅文档变更的提交无需再次检查。

详细参考：

- `docs/architecture.md`
- `docs/project-conventions.md`
- `docs/tool-lifecycle.md`
- `docs/launcher-catalog.md`
- `docs/launcher-tabs.md`
- `docs/build-environment.md`
