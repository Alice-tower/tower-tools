# 本地资源语义管理器

文件系统之上的标签、筛选和导航工具。只扫描指定 Root 的直接子项，不读取文件内容，不复制、移动、改名或删除实际资源。

- 项目：`ResourceTagger`
- Application ID：`dev.towertools.resourcetagger`
- 版本：`1.0.0`
- 作者：`Alice-tower`
- 源目录：`apps/resource-tagger`
- 产品依据：[完整 v0.2 方案](PRODUCT_SPEC.md)

## 使用

1. 从“工具塔”启动，或运行 `outputs\tools\dev.towertools.resourcetagger\ResourceTagger.exe`。便携目录包含运行时，必须整体复制。
2. 点击“添加 Root”，选择目录并设置名称；在“Root 管理”点击“扫描”。不允许磁盘根目录、重复或相互嵌套的 Root。
3. 进入“待处理”，选择新发现的目录或文件，编辑标签后“确认收录”；也可不打标签直接收录，或忽略该项。
4. 左栏搜索标签或别名，选择“不限 / 包含 + / 排除 −”。所有包含项必须同时满足，命中任一排除项即排除。
5. 资源可按名称、Root、类型、状态或无标签筛选。勾选多个资源，使用“批量标签”添加或移除指定标签。标签管理支持改名、删除和别名维护。
6. 单击查看详情；右键或详情按钮可导航。目录支持进入自身和在父目录中选中，文件仅支持在父目录中选中，不提供文件打开和预览。

## 状态与保护

- 扫描在后台执行；界面显示进度和错误。当前操作完成前不接受新的写入操作，同一 Root 不会并发扫描。
- 只在完整扫描成功后事务性更新资源、状态和待处理项。Root 离线、权限错误或中途失败时保留原有资源，不批量判定缺失。
- 默认跳过 Hidden、System、符号链接、目录联接及其他重解析对象，不进入子目录。
- 缺失资源保留 ID、创建时间和标签。“保留记录”确认该次提醒；持续缺失不重复提醒，恢复后再次缺失才重新提醒。
- 同路径发生文件 / 目录类型变化时必须明确确认关联当前对象。确认前保留原类型和标签，导航不可用。
- 忽略项默认隐藏且不进入待处理队列；左侧状态选择“已忽略”可查看、取消忽略。取消忽略会重新检查对象。
- 重新定位必须选择已配置 Root 的直接子项，类型相同。明确勾选合并选项后，可以合并无标签且未确认的新发现记录，保留旧 ID 和标签；其他冲突被拒绝。
- Root 重新定位保留资源身份、相对路径与关联，完成后需手动扫描。离线 Root 也可单独修改名称。
- 删除 Root、资源记录或标签只修改数据库；Root 和标签删除显示影响范围并确认。现存资源记录移除后下次扫描会重新发现，长期隐藏请使用忽略。
- 名称和别名使用 Unicode NFKC、首尾去空白及大小写不敏感的统一命名空间。别名指向同一 Tag ID，重命名不会丢失关联。

## 数据

数据库：`%LOCALAPPDATA%\Alice-tower\TowerTools\dev.towertools.resourcetagger\library.sqlite`。
日志：同目录下 `logs\application.log*`。数据库结构版本保存在 SQLite `user_version` 中；首次创建版本 1，拒绝打开未来版本，后续迁移在 `Database` 中按版本事务性添加。构建只替换便携输出，不触及用户数据。

关闭程序后可以备份整个数据目录。软件不提供自动备份或云同步。

## 构建与验证

在仓库根目录运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Build-Tool.ps1 -Id dev.towertools.resourcetagger
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Build-Launcher.ps1
```

构建包含核心逻辑和界面流程测试。受管沙箱先申请构建权限；共享 Gradle 缓存由生命周期脚本管理，不自行覆盖 `GRADLE_USER_HOME`。依赖和便携包许可见根目录 `THIRD_PARTY_NOTICES.md`。

第一版测试结果、实机检查和完整范围对照见 [验收记录](ACCEPTANCE.md)。

## 实现结构与限制

`FileSystem` 集中处理路径、扫描和导航；`Database` 定义版本及约束；`Library` 处理事务和身份；`Models` 定义查询；`LibraryController` 在后台串行执行操作；`LibraryApp` 提供三栏界面、Root / 标签管理与确认流程。

第一版不含递归索引、实时监听、内容解析、哈希身份、OR 查询、标签继承、自动移动识别或媒体预览。相同路径、相同类型的内容替换无法自动识别；遵循 Windows 常规大小写不敏感路径规则，检测到冲突时拒绝扫描。列表采用惰性渲染，但资料库快照和筛选在内存中完成。
