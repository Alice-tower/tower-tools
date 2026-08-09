# 代理环境变量

查看并管理当前 Windows 用户的 `HTTP_PROXY` 和 `HTTPS_PROXY` 环境变量。

功能：

- 显示两个变量在 `HKEY_CURRENT_USER\Environment` 中的当前值。
- 一键将两项设置为 `http://127.0.0.1:15236`。
- 一键删除两项。
- 修改后广播 Windows 环境变量变更通知。
- 任一步写入失败时恢复修改前的两个值。

已经运行的程序通常需要重新启动后才会读取新值。

## Run from this project

```powershell
.\gradlew.bat run
```

## Publish the portable directory

From the repository root, run:

```powershell
.\scripts\Build-Tool.ps1 -Id "dev.towertools.proxyenvmanager"
```

The published application is written to:

```text
outputs/tools/dev.towertools.proxyenvmanager/
```

For a project-local Compose application image that is not published to `outputs/`, run:

```powershell
.\gradlew.bat createDistributable
```
