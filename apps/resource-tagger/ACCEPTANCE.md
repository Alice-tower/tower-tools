# 第一版验收记录

日期：2026-09-22。版本：1.0.0。目标：Windows x64、Kotlin/JVM、Compose Desktop；对照 [产品方案 v0.2](PRODUCT_SPEC.md)。

## 交付范围对照

| 方案第 14 节 | 实现与验证证据 |
| --- | --- |
| 1. Windows GUI | `Main.kt` / `LibraryApp.kt`；Compose 真实控件流程测试、便携 EXE 原生窗口启动与明暗主题截图检查 |
| 2. Root 增改、重新定位、移除 | `Library.saveRoot/renameRoot/removeRoot`；嵌套、重复、磁盘根目录、空路径拒绝测试；离线改名与重新定位身份保留测试；GUI 添加和移除确认测试 |
| 3. 直接子项扫描 | `LocalFileSystem`；只发现 A 和 B.txt、跳过 inner.txt / Hidden / System 的测试；Windows 真实目录联接跳过验证 |
| 4. SQLite 持久化 | `Database`；`user_version=1`、外键、唯一约束及索引；重新打开数据库及便携程序重启验证 |
| 5. 稳定身份及状态 | `Library`；缺失、恢复、再次缺失保留 ID / 创建时间 / 标签的测试 |
| 6. Review Queue | New / Missing / TypeChanged 独立于资源存在状态；重复扫描去重、提醒确认、类型确认与回退测试 |
| 7. 忽略及取消忽略 | 忽略后无队列、普通结果隐藏、按状态查询、恢复时检查缺失或类型变化；GUI 忽略流程测试 |
| 8. 重新定位 | 保留旧 ID 和标签；类型与直接子项校验；新发现无标签记录需勾选确认合并；语义冲突拒绝；核心与 GUI 测试 |
| 9. Tag 增改删 | 名称规范化、冲突、重命名保留关联、删除级联；GUI 删除确认与取消验证 |
| 10. Alias 管理与搜索 | 全局统一名称空间；Unicode 等价名称冲突、别名增删搜索、GUI 别名筛选验证 |
| 11. 单个 / 批量标签 | 指定标签增加和移除事务；单个与多选 GUI 流程验证，不覆盖其他标签 |
| 12. AND / NOT | `Query` 三态枚举；两项 Include 与一项 Exclude 组合查询测试，GUI 直接切换验证 |
| 13. 附加筛选 | 名称 / 相对路径、Root、类型、状态、无标签与待处理；核心组合测试 |
| 14. 资源管理器导航 | `Navigator`；真实文件选中、目录选中及进入目录验证，包含中文、空格、逗号、`&`；核对 Shell 选中项与目录路径 |
| 15. 失败反馈和数据保护 | 枚举失败、Root 离线、路径大小写冲突、数据库中途写入失败均不提交部分扫描；事务回滚测试；数据库删除不影响文件测试；界面错误显示与重试流程 |
| 16. 启动器与便携构建 | `catalog/tools.json` 与 `outputs/catalog/tools.json` 包含稳定 ID；工具和启动器生命周期构建成功；EXE 使用自带 Java 运行时启动成功 |

## 自动化结果

最终 `Build-Tool.ps1`：**BUILD SUCCESSFUL**，22 项测试，0 失败、0 跳过。

- `AppMetadataTest`：1 项。
- `LibraryTest`：19 项，覆盖扫描、状态、身份、路径、事务、查询、约束和删除保护。
- `LibraryUiTest`：2 条完整界面流程，覆盖添加 Root → 扫描 → 收录 → 标签 / 别名 → 筛选 → 忽略，以及批量标签 → 移动后重新扫描 → 显式合并确认 → 删除标签 / Root。
- 测试报告：`build/reports/tests/test/index.html`；界面截图：`build/verification/library-ui.png`。这些生成文件不提交 Git。
- `Build-Launcher.ps1` 成功，启动器已有测试通过；源端和输出目录都包含新工具。

## Windows 实机检查

由开发代理执行，使用 `outputs/.verification/resource-tagger/` 内的隔离测试目录与子进程 `LOCALAPPDATA`，没有向正式资料库写入示例记录。

- EXE 正常打开，实际显示 2 条资源、共享标签和无待处理状态；自带 Java runtime、SQLite DLL 均可工作。
- 正常退出并重新启动，资源、标签、别名和关联保持不变；重复启动只保留一个应用窗口。
- 真实资源管理器文件选择、目录选择、打开目录均正确；测试路径含中文、空格、逗号及 `&`，未经过命令解释器拼接。
- 创建真实 Windows 目录联接后扫描，联接被跳过。
- 资料库在重建便携包前后保留相同记录与关联，示例文件内容未改变。
- 所有本次启动的工具测试实例和测试资源管理器窗口均正常关闭。

## 许可与文档

核对实际便携 `app/` JAR、SQLite JDBC 内 Windows x64 `sqlitejdbc.dll` 和 `runtime/legal/`。新增 Xerial SQLite JDBC 3.50.3.0 / SQLite 3.50.3，声明见根 `THIRD_PARTY_NOTICES.md`；Zentus BSD 文本从实际 JAR 提取，工具和启动器便携包中的文本与源文件 SHA-256 一致。JUnit / Compose UI test 仅用于测试，不进入便携包。

根 README 已增加工具，工具 README 说明使用、数据路径、构建和限制；启动器 README 已检查，本次仅新增目录条目，没有使其说明失效。

## 交付位置、限制与差异

- 程序：`outputs/tools/dev.towertools.resourcetagger/ResourceTagger.exe`，需要携带整个目录。
- 启动器：`outputs/launcher/TowerLauncher.exe`。
- 正式数据：`%LOCALAPPDATA%\Alice-tower\TowerTools\dev.towertools.resourcetagger\library.sqlite`，日志位于同级 `logs/`。
- 无产品范围缩减；Root 选择采用下拉列表，Root / Tag 管理为独立页，待处理共用资源列表，均符合方案允许的布局调整。
- 操作在后台串行执行，同一时间只执行一个资料库操作；列表惰性渲染，查询使用内存快照，尚未做大规模资料库性能基准。
- 与方案一致，不自动识别同路径同类型内容替换、不递归、不实时监听、不预览媒体、不打开文件、不操作实际资源内容。不能替代用户在其实际资料目录上的最终使用验收。
