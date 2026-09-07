# 代理环境变量

查看并管理当前 Windows 用户的 `HTTP_PROXY`、`HTTPS_PROXY`、`ALL_PROXY` 和 `NO_PROXY` 环境变量。

功能：

- 显示四个变量在 `HKEY_CURRENT_USER\Environment` 中的当前值，并提示与预设不一致的项目。
- 一键将 `HTTP_PROXY` 和 `HTTPS_PROXY` 设置为 `http://127.0.0.1:15236`，将 `ALL_PROXY` 设置为 `socks5://127.0.0.1:15235`，并将 `NO_PROXY` 设置为 `localhost,127.0.0.1,192.168.31.0/24`。
- 一键删除四项。
- 修改后提示重启需要使用新配置的程序。
- 任一步写入失败时恢复修改前的四个值。

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
