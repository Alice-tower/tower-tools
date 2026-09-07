# 工具生命周期

## 创建

请按照 `docs/build-environment.md` 中的 PowerShell 调用方式运行 `scripts/New-Tool.ps1`。该脚本会验证标识字段、复制模板、替换显式 token、将 Kotlin 源文件移动到 package path、创建元数据、构建便携目录、同步目录并重新构建启动器。

缺少必填值时，命令会接受交互式输入。Codex 通常会在调用前提出 display name、project name、description 与 ID。

## 更新

编辑 `apps/` 下的独立项目，在 `tool.json` 与 `build.gradle.kts` 中递增版本，然后运行 `scripts/Build-Tool.ps1`。构建默认采用增量模式；仅在需要干净重建时传入 `-Clean`。成功构建只会替换程序目录，用户配置和日志仍保留在 `%LOCALAPPDATA%`。

## 删除

请使用 `scripts/Remove-Tool.ps1`。该脚本会显示精确的源路径与输出路径；除非明确传入 `-Force`，否则需要确认。它会验证删除目标始终位于仓库内、删除目录条目和便携输出，并重新构建启动器。

删除工具时会刻意保留启动器的用户 override，以便将来恢复同一稳定 ID 时，原分类与排序仍可恢复。

## 失败行为

构建会在 `outputs/.staging/` 下组装。仅在新的 application image 完成后才替换现有便携输出。如果正在运行的可执行文件锁定旧目录，操作会以清晰错误停止，而不会终止该应用。
