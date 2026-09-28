# 架构

## 目标

Tower Tools 包含相互独立的 Windows x64 桌面工具，以及可承载内置 Tab 的工具塔启动器。每个独立工具均可在 IntelliJ IDEA 中单独打开、独立构建，并作为便携目录复制到未安装 Java 的目标机器运行。Tab 随启动器一同编译和分发。

## 项目

每个项目都是单模块 Kotlin/JVM 应用，使用 Compose Desktop。为保持范围明确，刻意不采用 Multiplatform source set，也不支持非 Windows 打包。保留 Google Maven 的唯一原因是当前 Compose Desktop artifact 依赖 AndroidX foundation component；项目未配置 Android target。

独立工具与启动器分别使用自己的 Gradle build，以便工具数量增加时仓库仍保持响应迅速。每个生成的独立工具项目都复制 Gradle Wrapper 文件，以保留可复现且可独立执行的构建；Tab 不新建 Gradle 项目。

## 运行时数据

程序二进制文件可以替换；可变数据不得存放在可执行文件旁。

- 工具数据：`%LOCALAPPDATA%\Alice-tower\TowerTools\<application-id>\`
- 启动器数据：`%LOCALAPPDATA%\Alice-tower\TowerLauncher\`
- Tab 业务数据：`%LOCALAPPDATA%\Alice-tower\TowerLauncher\tabs\<stable-key>\`；从旧工具迁入时将旧配置迁移到该目录。
- 日志位于各应用数据目录下的 `logs/`。

## 便携输出

`outputs/` 是唯一的发布位置，且已被 Git 忽略：

```text
outputs/
  launcher/               TowerLauncher.exe、app-icon.ico、LICENSE、第三方声明和 runtime 文件
  tools/<application-id>/ Tool.exe、app-icon.ico、LICENSE、第三方声明和 runtime 文件
  catalog/tools.json      生成的运行时目录
```

目录中的可执行文件路径相对于 `outputs/`，因此无需修改路径即可整体移动该目录。
仓库生命周期脚本会将根目录的 MIT `LICENSE`、`THIRD_PARTY_NOTICES.md` 和 `third-party/licenses/` 复制到每个便携应用目录。精简 Java runtime 的许可文件位于输出目录下的 `runtime/legal/`。

## 单实例行为

每个应用都会使用由稳定 Application ID 派生的按用户 lock file 与 loopback activation channel。第二个进程会请求第一个进程恢复并聚焦其窗口，随后退出。

## 启动器内置 Tab

启动器保持独立的单模块 Compose Desktop 项目。宿主只负责窗口、主题、单实例及 Tab 切换；每个 Tab 的页面、状态、数据和操作位于自己的业务包。当前 `TabId` 枚举和编译时注册表包含“工具”、“CMD”及“仓库”Tab。工具 Tab 使用生成的便携工具目录和用户分类设置；CMD Tab 使用 `TowerLauncher\tabs\cmd` 管理项目配置及独立的分类、排序和收藏设置；仓库 Tab 使用 `TowerLauncher\tabs\repositories` 保存扫描路径、上次结果及独立的仓库分类、排序和收藏设置，在设置对话框管理路径，手动刷新所有路径，并合并显示其直接子目录中的 Git 仓库。新 Tab 不创建 `tool.json` 或工具目录条目。详细边界、数据规则和开发步骤见 [启动器 Tab 开发规范](launcher-tabs.md)。

## 工具内部扩展

资源语义管理器从 1.2.0 起提供随应用一起编译的内部预览扩展。1.3.0 将列表、网格和画廊统一归主程序，插件提供缩略图、大预览内容、详情 Tab 与右键动作，共用宿主能力。它仍是单模块工具，不采用动态插件加载，也不改变仓库工具的创建与发布流程。实现现状及后续规范见 [预览扩展架构](../apps/resource-tagger/PREVIEW_ARCHITECTURE.md)。

内部架构约定服务于当前需求，可以随着实际插件经验调整；调整时同步实现、回归测试及文档，不把既有接口当成永久约束。

资源语义管理器 2.0.0 统一使用 `Root/bucket-000001/资源` 两层路径；Bucket 只是命名规则，不新增实体。数据库驻留本机，扫描手动触发，失败不提交部分结果。旧测试数据库不迁移，详见 [Bucket 与 NAS](../apps/resource-tagger/BUCKET_STORAGE.md)。

资源语义管理器 2.1.0 将 NAS 扫描枚举与本地查询队列隔离，提供进度及提交前协作式取消；后续功能必须考虑 NAS 延迟、离线与读写成本，功能完整性优先。规范可随实践优化，详见上述 Bucket 与 NAS 文档。
