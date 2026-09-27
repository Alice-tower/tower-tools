# 工具塔

在“工具”Tab 中浏览和启动便携工具，在“CMD”Tab 中登记和启动本机 `.cmd` 项目，在“仓库”Tab 中发现 Git 仓库。

- 项目：`TowerLauncher`
- Application ID：`dev.towertools.launcher`
- 版本：`1.4.6`
- 作者：`Alice-tower`

侧边栏的“收藏”和“全部”固定在最上方；其他分类可拖动左侧的 `≡` 调整顺序，松开后立即保存，并同步“全部工具”的分组顺序。启动时如果有收藏工具则默认显示收藏，否则显示全部。工具左侧的爱心可直接切换收藏状态：红色实心表示已收藏，灰色空心表示未收藏。操作立即生效；右键菜单中的“编辑分类和排序”对话框只修改分类和工具在分类内的排序。工具排序仍决定列表顺序，但数字不显示在工具卡片上。

三个 Tab 使用相同的页头布局。工具页的“设置”对话框左侧纵向排列“打开启动器配置”和“打开工具配置”，分别在资源管理器中打开 `%LOCALAPPDATA%\Alice-tower\TowerLauncher\` 与 `%LOCALAPPDATA%\Alice-tower\TowerTools\`；打开目录后对话框保持显示，右下角“完成”负责关闭。各工具的分类和排序通过右键菜单编辑，右键菜单不再提供“查看日志”；切换 Tab 后筛选状态会保留。CMD 页读取和保存 `%LOCALAPPDATA%\Alice-tower\TowerLauncher\tabs\cmd\projects.properties`。“设置”将添加项目表单放在独立的圆角边框内，不列出已登记项目；添加成功后清空表单并保持设置页打开，右下角“完成”负责关闭。已有项目从卡片的“更多”菜单编辑或删除。项目支持手动启动，并每 3 秒检测本地 Web 端口；端口检测仅在 CMD Tab 显示时运行。

仓库页将扫描路径和最近一次扫描结果保存在 `%LOCALAPPDATA%\Alice-tower\TowerLauncher\tabs\repositories\locations.properties`。右上角“设置”对话框管理路径并显示每个路径的子目录数量、仓库数量和上次扫描时间；其左侧的“刷新”按钮会扫描所有已添加路径。扫描只检查各路径的直接子目录，忽略路径本身；只有包含 `.git` 目录的子目录会显示为 Git 仓库。页面主体合并显示已扫描到的仓库名称和完整路径。重新打开启动器时显示上次结果，不自动扫描；移除路径只删除登记。

编辑分类时可从分类输入框的下拉列表选择已有分类，也可直接输入新分类。

分类、分类顺序、工具排序和收藏保存时会先写入临时文件，再原子替换用户配置。现有配置无法读取时会取消保存，并在编辑对话框或列表底部显示错误，保留原文件。

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

Tab 由 `TabId` 枚举和 `Main.kt` 中的 `LauncherTab` 列表编译时注册。工具页、CMD 页和仓库页分别维护在 `tabs/tools`、`tabs/cmd`、`tabs/repositories` 包；新 Tab 也应有独立业务包、状态和用户数据。新 Tab 的数据目录统一位于 `%LOCALAPPDATA%\Alice-tower\TowerLauncher\tabs\<stable-key>\`。完整约定及交付步骤见 [启动器 Tab 开发规范](../../docs/launcher-tabs.md)。
