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

启动器的窗口与系统托盘由宿主统一管理。系统托盘图标由 AWT 管理，右键通过 MouseInfo 的逻辑屏幕坐标和目标 GraphicsConfiguration 打开 Compose 绘制的独立 POPUP 窗口，与主界面共用文字渲染并避开 AWT 原生菜单的中文字形问题。弹出菜单不增加任务栏条目，失去焦点或按 Esc 时收起，使用紧凑的 148×69 逻辑尺寸并跟随目标屏幕缩放，以右键点击的鼠标位置为锚点（间距 2 个逻辑像素），空间不足时翻转方向，只按当前显示器的完整边界限制位置，允许覆盖任务栏而不吸附到工作区边缘，避免 Windows 托盘事件的物理像素坐标导致偏移；图标与菜单窗口随应用组合的释放清理。关闭窗口只设置可见性，不销毁窗口组合，因此 Tab 状态与后台任务继续保留；托盘菜单的“退出”结束应用并释放单实例与业务资源。托盘恢复和重复启动共用激活入口，取消最小化并聚焦原窗口。独立工具仍保持各自的关闭行为。

## 启动器内置 Tab

启动器保持独立的单模块 Compose Desktop 项目。宿主只负责窗口、主题、单实例及 Tab 切换；每个 Tab 的页面、状态、数据和操作位于自己的业务包。当前 `TabId` 枚举和编译时注册表包含“工具”、“CMD”及“仓库”Tab。工具 Tab 使用生成的便携工具目录和用户分类设置；CMD Tab 使用 `TowerLauncher\tabs\cmd` 管理项目配置及独立的分类、排序和收藏设置；仓库 Tab 使用 `TowerLauncher\tabs\repositories` 保存扫描路径、上次结果及独立的仓库分类、排序、收藏、展示名称和按钮样式设置，在设置对话框管理路径，手动刷新所有路径，并合并显示其直接子目录中的 Git 仓库。仓库卡片在后台读取各仓库的本地 Git 配置，识别 GitHub 远端并控制卡片末端的网页按钮；相邻按钮可用本机 VS Code 打开仓库目录，设置中可切换紧凑按钮样式。新 Tab 不创建 `tool.json` 或工具目录条目。详细边界、数据规则和开发步骤见 [启动器 Tab 开发规范](launcher-tabs.md)。

## 工具内部扩展

资源语义管理器从 1.2.0 起提供随应用一起编译的内部预览扩展。1.3.0 将列表、网格和画廊统一归主程序，插件提供缩略图、大预览内容、详情 Tab 与右键动作，共用宿主能力。它仍是单模块工具，不采用动态插件加载，也不改变仓库工具的创建与发布流程。实现现状及后续规范见 [预览扩展架构](../apps/resource-tagger/PREVIEW_ARCHITECTURE.md)。

内部架构约定服务于当前需求，可以随着实际插件经验调整；调整时同步实现、回归测试及文档，不把既有接口当成永久约束。

资源语义管理器 2.0.0 统一使用 `Root/bucket-000001/资源` 两层路径；Bucket 只是命名规则，不新增实体。数据库驻留本机，扫描手动触发，失败不提交部分结果。旧测试数据库不迁移，详见 [Bucket 与 NAS](../apps/resource-tagger/BUCKET_STORAGE.md)。

资源语义管理器 2.1.0 将 NAS 扫描枚举与本地查询队列隔离，提供进度及提交前协作式取消；后续功能必须考虑 NAS 延迟、离线与读写成本，功能完整性优先。规范可随实践优化，详见上述 Bucket 与 NAS 文档。

## 静读阅读器

静读位于 `apps/tower-reader/`，沿用单模块 Kotlin/JVM + Compose Desktop 的独立工具形态。Compose 负责书库、窗口和操作界面；随便携包携带的 JavaFX WebView 负责 TXT/EPUB 的离线动态分页，PDFBox 按需渲染 PDF。本地阅读不依赖系统预装浏览器，不运行书内脚本或加载外部资源。

书库使用 SQLite；导入时创建托管副本并按 SHA-256 去重。配置、书库与日志均位于工具自己的 `%LOCALAPPDATA%` 数据目录。TXT/EPUB 使用可重建的小节缓存与稳定内容位置恢复；PDF 使用页号及有字节上限的页面缓存。单个 PDF 文档在专用后台线程串行访问，导入的文件处理不持有数据库长锁。

静读 1.1.0 使用 category_order 表保存具体分类的侧栏顺序，首次打开已有书库时从书籍分类补齐。分类改名与关联书籍更新在同一事务中完成；空分类随书籍编辑或移出清理。拖动仅在界面预览，松开后由后台线程保存一次顺序。

TXT/EPUB 在一本书内保留同一个 Swing 阅读面板和两个可复用 WebView，当前章节保持显示，另一个页面预排相邻章节；完成排版和绘制后才切换前景，正文片段缓存上限三个。窗口级 AWT 键盘分发统一处理阅读快捷键，并排除对话框及编辑状态。JavaFX 页面创建仍须晚于 Swing 挂载，以避免首次初始化的类锁死锁。

静读 1.1.1 启用 Compose Swing 混合显示；全屏通过已有 JNA 去掉顶层 HWND 边框并覆盖当前显示器，退出时恢复 WINDOWPLACEMENT。避免 Skiko 原生全屏层遮挡 Swing/JavaFX 阅读区域，不重建阅读面板。真实可见窗口回归同时检查屏幕像素、全屏范围和普通/最大化窗口恢复。

阅读器通过本地临时 HTML 文件装载已过滤的内容，使 WebView 可以读取同机缓存中的 EPUB 图片；不使用无法访问本地图片的 about:blank 页面，也不把全部图片转成内嵌字符串。页面文件由专用串行后台线程写入，稳定时仅保留当前页与预排页，替换和关闭时清理；位置位于工具数据目录的 cache/pages/。页面仍用内容过滤及 CSP 禁止网络资源和书内脚本。

静读打包还会自动校验、下载并携带上游 OpenJFX 源码包，源码缓存只在被 Git 忽略的 `outputs/third-party-sources/tower-reader/`。这是静读项目内部的打包任务，不改变其他工具的生命周期入口。具体格式范围和验证记录见[静读说明](../apps/tower-reader/README.md)。

静读 1.2.0 在工具内部增加微信读书页面，不新增工具或启动器 Tab。Kotlin/JNA 适配官方 WebView2 COM 接口，在专用 STA 线程运行消息循环；浏览器显示为主窗口内的原生子窗口，避开 SwingPanel 离屏绘制无法显示 Chromium 子窗口的问题。该 STA 与 AWT 原生窗口线程连接输入队列，释放时断开，以支持网页输入与全屏快捷键。布局使用 Compose 计算的窗口像素边界，复用 1.1.1 顶层无边框全屏。

首次选择微信读书才创建实例。切回本地时隐藏并尝试挂起浏览器，返回后恢复；不重新导航、不重建实例。软件关闭时关闭 WebView2 控制器并等待其浏览器进程退出。网站 profile 与 navigation.properties 位于静读数据目录的 weread/ 下；书架与阅读 URL 可保存，登录回调查询参数不保存。清除登录数据前先卸载网站，再调用官方 profile 清理 API，不删除本地书库。

程序只携带 SDK 1.0.4258.31 的 Windows x64 WebView2Loader.dll，运行时复用机器已有 Evergreen Runtime。该加载器由项目内 Gradle 任务从校验 SHA-256 的官方 NuGet 包提取为构建资源，不加入 Git，不增加 .NET 或 C++ 构建依赖。SDK 缓存位于 outputs/build-dependencies/tower-reader/；许可核对见静读 DEPENDENCIES.md。

静读 1.2.1 将原始文字偏移与可见排版位置分离：保存进度仍使用原来的文字/图片索引，不改变现有书籍的 locator；重排时按有效矩形测量页面，翻页用可见节点范围进行二分定位，折叠空白回退到可见前缀。封面和正文共用 EPUB 归档内 URI 解析，封面失败只降级封面，不放宽必需 XML 或归档路径检查。

移出书籍时，书籍记录、分类、书籍编码设置与 pending_book_cleanup 清理任务在同一 SQLite 事务内更新；提交成功后才删除托管文件、封面和该书 UUID 下的缓存。物理清理受阻不恢复已移出的书卡，任务保留并在下次启动重试。清理路径限制在该工具的数据目录，不递归进入缓存中的目录联接；用户的原文件不参与清理。
