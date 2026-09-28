# 启动器目录

启动器的“工具”Tab 会合并生成的程序元数据与持久化的用户 override。`catalog/tools.json` 只登记 `apps/` 下的独立工具；其他内置 Tab 在代码中注册，各自管理业务数据，规范见 [Tab 开发规范](launcher-tabs.md)。

## 生成的目录

`outputs/catalog/tools.json` 会根据 `apps/*/tool.json` 重新生成。典型条目如下：

```json
{
  "id": "dev.towertools.exampletool",
  "projectName": "ExampleTool",
  "displayName": "示例工具",
  "description": "用途说明",
  "version": "1.0.0",
  "executablePath": "tools/dev.towertools.exampletool/ExampleTool.exe",
  "defaultCategory": "未分类",
  "defaultOrder": 0
}
```

路径相对于 `outputs/` 目录。源端 `catalog/tools.json` 具有确定性，包含同样的逻辑工具注册信息，但不包含 build timestamp。

## 用户 override

`%LOCALAPPDATA%\Alice-tower\TowerLauncher\user-settings.json` 仅保存按稳定工具 ID 归属的用户设置，不作为其他 Tab 的通用数据文件：

```json
{
  "tools": {
    "dev.towertools.exampletool": {
      "category": "文本工具",
      "order": 10,
      "favorite": true
    }
  },
  "categoryOrder": ["文本工具", "图片&影音", "本地管理"]
}
```

打包永不写入此文件。缺少 override 时，回退为分类 `未分类`、排序 `0` 和未收藏。旧设置中没有 `favorite` 字段时也视为未收藏。工具按 order 升序排序，随后按 display name 排序。旧设置中没有 `categoryOrder` 时，普通分类按名称排序，`未分类` 排在最后；新分类接在已保存的分类之后。

## 工具 Tab 界面

顶层窗口提供编译时注册的“工具”、“CMD”和“仓库”Tab。以下分类、排序与收藏规则仅属于“工具”Tab；切换 Tab 时当前筛选状态会保留。

启动器使用分类侧边栏与紧凑工具列表，适合较大的工具集合。侧边栏最上方是固定的“收藏”和“全部”；普通分类可拖动左侧手柄调整顺序，松开后立即保存到 `user-settings.json`。全部工具列表的分组顺序与侧边栏一致。启动时如果有收藏工具则默认显示收藏，否则显示全部。每个工具左侧的爱心可直接切换收藏状态，红色实心表示已收藏，灰色空心表示未收藏；操作会保存到 `user-settings.json` 并立即刷新列表。分类数量仍可见，工具卡片不显示排序数字。通过右键菜单选择“编辑分类和排序”，可修改分类和工具在分类内的排序；分类输入框可下拉选择已有分类，也可输入新分类，对话框还会显示该分类中其他工具的 order 以供比较。保存后会更新 `user-settings.json` 并立即重新加载列表。空分类会规范为 `未分类`；order 可接受任意整数，包括负数。

双击条目可启动工具；卡片不显示“启动”按钮。其余右键操作可打开便携目录；“查看日志”不再出现在工具右键菜单中。工具页的“设置”在左侧纵向提供“打开启动器配置”和“打开工具配置”两个入口，分别在资源管理器中打开 `%LOCALAPPDATA%\Alice-tower\TowerLauncher\` 与 `%LOCALAPPDATA%\Alice-tower\TowerTools\`。点击任一入口后对话框保持打开，右下角“完成”关闭对话框。工具 Tab 不提供安装、更新、shortcut、搜索、工具拖拽排序或 window-state system。

“CMD”Tab 管理用户登记的 `.cmd` 项目，不属于本目录；它使用 `%LOCALAPPDATA%\Alice-tower\TowerLauncher\tabs\cmd\projects.properties`。功能说明见 [启动器 README](../launcher/TowerLauncher/README.md)。
