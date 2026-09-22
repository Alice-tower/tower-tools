# 代理环境变量

查看并管理当前 Windows 用户的 `HTTP_PROXY`、`HTTPS_PROXY`、`ALL_PROXY` 和 `NO_PROXY` 环境变量。

功能：

- 显示四个变量在 `HKEY_CURRENT_USER\Environment` 中的当前值，并提示与当前预设不一致的项目。
- 首次使用时预设为空。请先通过“设置预设”填写 `HTTP_PROXY`、`HTTPS_PROXY` 和 `ALL_PROXY`；在此之前不能应用预设。`NO_PROXY` 可以留空。
- 未填写必要预设时，`NO_PROXY` 也显示“未配置”。保存预设后，未设置的当前 `NO_PROXY` 与空字符串预设会显示“一致”，因为两者都没有绕过代理的主机列表。
- 右上角的“设置预设”可分别编辑四项值。预设保存在 `%LOCALAPPDATA%\Alice-tower\TowerTools\dev.towertools.proxyenvmanager\proxy-presets.properties`，重启或重新构建后仍会保留。保存预设本身不会修改环境变量。
- 每行提供“使用预设”和“删除”，只修改对应变量，保留其他变量的当前值。使用预设前仍须先完成必要的预设配置；删除不依赖预设。
- 底部保留“全部应用预设”和“全部删除”，可一键操作四项。
- 主窗口默认使用 700 × 450 的紧凑尺寸，表格按内容高度展示，一屏展示四项变量及全部操作，不需要上下滚动；过长的变量值可在对应单元格内横向滚动查看。
- 修改后提示重启需要使用新配置的程序。
- 单项操作失败时只恢复该变量；批量操作失败时恢复修改前的四个值。

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
