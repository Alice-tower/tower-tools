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
