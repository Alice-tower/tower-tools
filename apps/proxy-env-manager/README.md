# 代理环境变量

查看并管理当前 Windows 用户的 `HTTP_PROXY`、`HTTPS_PROXY`、`ALL_PROXY` 和 `NO_PROXY` 环境变量。

功能：

- 显示四个变量在 `HKEY_CURRENT_USER\Environment` 中的当前值，并提示与当前预设不一致的项目。
- 首次使用时预设为空。请先通过“设置预设”填写 `HTTP_PROXY`、`HTTPS_PROXY` 和 `ALL_PROXY`；在此之前不能应用预设。`NO_PROXY` 可以留空。
- 右上角的“设置预设”可分别编辑四项值。预设保存在 `%LOCALAPPDATA%\Alice-tower\TowerTools\dev.towertools.proxyenvmanager\proxy-presets.properties`，重启或重新构建后仍会保留。保存预设本身不会修改环境变量；点击底部“应用预设”才会应用。
- 一键删除四项。
- 修改后提示重启需要使用新配置的程序。
- 任一步写入失败时恢复修改前的四个值。

已有的本地预设文件会继续使用，不会因升级而覆盖或清空。

已经运行的程序通常需要重新启动后才会读取新值。

## 从当前项目运行

```powershell
.\gradlew.bat run
```

## 发布便携目录

在仓库根目录运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Build-Tool.ps1 -Id "dev.towertools.proxyenvmanager"
```

发布后的应用位于：

```text
outputs/tools/dev.towertools.proxyenvmanager/
```

如需创建不发布到 `outputs/` 的项目本地 Compose application image，请运行：

```powershell
.\gradlew.bat createDistributable
```
