# 启动器目录

启动器会合并生成的程序元数据与持久化的用户 override。

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

`%LOCALAPPDATA%\Alice-tower\TowerLauncher\user-settings.json` 仅保存按稳定工具 ID 归属的用户设置：

```json
{
  "tools": {
    "dev.towertools.exampletool": {
      "category": "文本工具",
      "order": 10,
      "favorite": true
    }
  }
}
```

打包永不写入此文件。缺少 override 时，回退为分类 `未分类`、排序 `0` 和未收藏。旧设置中没有 `favorite` 字段时也视为未收藏。工具按 order 升序排序，随后按 display name 排序。

## 启动器界面

启动器使用分类侧边栏与紧凑工具列表，适合较大的工具集合。侧边栏最上方是“收藏”，其下是“全部”和普通分类。启动时如果有收藏工具则默认显示收藏，否则显示全部。收藏的工具在所有列表视图中显示“★ 已收藏”标记。分类数量与每个工具的有效 order 始终可见。右键工具可直接选择“加入收藏”或“取消收藏”，操作会保存到 `user-settings.json` 并立即刷新列表。通过同一右键菜单选择“编辑分类和排序”，可修改分类和排序；分类输入框可下拉选择已有分类，也可输入新分类，对话框还会显示该分类中其他工具的 order 以供比较。保存后会更新 `user-settings.json` 并立即重新加载列表。空分类会规范为 `未分类`；order 可接受任意整数，包括负数。

双击条目或点击其 `启动` 按钮可启动工具。其余右键操作可打开便携目录或工具日志目录。启动器刻意不提供安装、更新、shortcut、搜索、drag-ordering 或 window-state system。
