# 工具塔

在“工具”Tab 中浏览和启动便携工具，并在“CMD”Tab 中登记和启动本机 `.cmd` 项目。

- 项目：`TowerLauncher`
- Application ID：`dev.towertools.launcher`
- 版本：`1.2.0`
- 作者：`Alice-tower`

侧边栏的“收藏”位于“全部”上方；启动时如果有收藏工具则默认显示收藏，否则显示全部。收藏的工具在列表中显示“★ 已收藏”标记。右键工具可直接选择“加入收藏”或“取消收藏”，操作立即生效；“编辑分类和排序”对话框只修改分类和排序。

工具页的分类与收藏行为保持不变；切换 Tab 后筛选状态会保留。CMD 页读取和保存 `%LOCALAPPDATA%\Alice-tower\TowerLauncher\tabs\cmd\projects.properties`。项目列表可按名称或路径搜索，支持添加、编辑、删除登记、手动启动，以及每 3 秒检测本地 Web 端口；端口检测仅在 CMD Tab 显示时运行。

编辑分类时可从分类输入框的下拉列表选择已有分类，也可直接输入新分类。

分类、排序和收藏保存时会先写入临时文件，再原子替换用户配置。现有配置无法读取时会取消保存，并在编辑对话框或列表底部显示错误，保留原文件。

重复启动会通知现有窗口恢复并聚焦；激活连接设有读超时，避免无响应连接长期阻塞。

窗口与 Windows 可执行文件使用仓库统一图标。
启动时以稳定 Application ID 登记 Windows 任务栏身份，避免任务栏显示 Compose 默认图标。便携目录中的 `app-icon.ico` 可作为桌面快捷方式的显式图标来源。

## 运行

```powershell
.\gradlew.bat run
```

## 构建便携目录

```powershell
.\gradlew.bat createDistributable
```

仓库生命周期脚本会将生成的 Windows application image 复制到共享的 `outputs/` 目录。

## 开发 Tab

Tab 由 `TabId` 枚举和 `Main.kt` 中的 `LauncherTab` 列表编译时注册。工具页和 CMD 页分别维护在 `tabs/tools`、`tabs/cmd` 包；新 Tab 也应有独立业务包、状态和用户数据。新 Tab 的数据目录统一位于 `%LOCALAPPDATA%\Alice-tower\TowerLauncher\tabs\<stable-key>\`。完整约定及交付步骤见 [启动器 Tab 开发规范](../../docs/launcher-tabs.md)。
