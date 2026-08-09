# Launcher catalog

The launcher merges generated program metadata with persistent user overrides.

## Generated catalog

`outputs/catalog/tools.json` is regenerated from `apps/*/tool.json`. A typical entry contains:

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

Paths are relative to the `outputs/` directory. The source-side `catalog/tools.json` is deterministic and contains the same logical tool registrations without build timestamps.

## User overrides

`%LOCALAPPDATA%\Alice-tower\TowerLauncher\user-settings.json` stores only user-owned values keyed by stable tool ID:

```json
{
  "tools": {
    "dev.towertools.exampletool": {
      "category": "文本工具",
      "order": 10
    }
  }
}
```

Packaging never writes this file. Missing overrides fall back to category `未分类` and order `0`. Tools sort by ascending order and then by display name.

## Launcher interface

The launcher uses a category sidebar and a compact tool list designed for larger collections. Category counts and each tool's effective order are always visible. Use the tool's right-click menu and select `编辑分类和排序` to change both values; the dialog can reuse an existing category and shows the order values of other tools in that category for comparison. Saving updates `user-settings.json` and immediately reloads the list. An empty category is normalized to `未分类`; order accepts any integer, including negative values.

Double-clicking a row or pressing its `启动` button launches the tool. The remaining right-click actions open the portable directory or the tool's log directory. The launcher intentionally has no installation, update, shortcut, search, drag-ordering, or window-state system.
