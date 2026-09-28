# 独立工具生命周期

本文只适用于 `apps/` 下拥有独立 Application ID、GUI 和便携输出的工具。开发启动器内置页面请改看 [Tab 开发规范](launcher-tabs.md)；新 Tab 不运行 `New-Tool.ps1`，也不进入 `catalog/tools.json`。

## 创建

请按照 [构建环境](build-environment.md) 中的 PowerShell 调用方式运行 `scripts/New-Tool.ps1`。该脚本会验证标识字段、复制模板、替换显式 token、将 Kotlin 源文件移动到 package path、创建元数据、构建便携目录、同步目录并重新构建启动器。

缺少必填值时，命令会接受交互式输入。Codex 通常会在调用前提出 display name、project name、description 与 ID。

例如，在仓库根目录创建一个独立工具：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\New-Tool.ps1 -DisplayName "示例工具" -ProjectName "ExampleTool" -Description "用途说明"
```

## 更新

编辑 `apps/` 下的独立项目，在 `tool.json` 与 `build.gradle.kts` 中递增版本，然后运行 `scripts/Build-Tool.ps1`。构建默认采用增量模式；仅在需要干净重建时传入 `-Clean`。成功构建只会替换程序目录，用户配置和日志仍保留在 `%LOCALAPPDATA%`。

## 删除

请使用 `scripts/Remove-Tool.ps1`。该脚本会显示精确的源路径与输出路径；除非明确传入 `-Force`，否则需要确认。它会验证删除目标始终位于仓库内、删除目录条目和便携输出，并重新构建启动器。

删除工具时会刻意保留启动器的用户 override，以便将来恢复同一稳定 ID 时，原分类与排序仍可恢复。

例如，删除上面创建的示例工具：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Remove-Tool.ps1 -Id "dev.towertools.exampletool"
```

若独立工具迁入启动器 Tab，须先让 Tab 的功能和用户数据兼容通过验证，再用上述脚本退役旧工具。旧 Application ID 不得复用；用户数据的路径或迁移办法由 Tab 规范明确。

## 失败行为

构建会在 `outputs/.staging/` 下组装。仅在新的 application image 完成后才替换现有便携输出。如果正在运行的可执行文件锁定旧目录，操作会以清晰错误停止，而不会终止该应用。
