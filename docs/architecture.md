# 架构

## 目标

Tower Tools 是由相互独立的 Windows x64 桌面应用组成的 monorepo。每个工具均可在 IntelliJ IDEA 中单独打开、独立构建，并作为便携目录复制到未安装 Java 的目标机器运行。

## 项目

每个项目都是单模块 Kotlin/JVM 应用，使用 Compose Desktop。为保持范围明确，刻意不采用 Multiplatform source set，也不支持非 Windows 打包。保留 Google Maven 的唯一原因是当前 Compose Desktop artifact 依赖 AndroidX foundation component；项目未配置 Android target。

源项目使用彼此独立的 Gradle build，以便工具数量增加时仓库仍保持响应迅速。每个生成项目都复制 Gradle Wrapper 文件，以保留可复现且可独立执行的构建。

## 运行时数据

程序二进制文件可以替换；可变数据不得存放在可执行文件旁。

- 工具数据：`%LOCALAPPDATA%\Alice-tower\TowerTools\<application-id>\`
- 启动器数据：`%LOCALAPPDATA%\Alice-tower\TowerLauncher\`
- 日志位于各应用数据目录下的 `logs/`。

## 便携输出

`outputs/` 是唯一的发布位置，且已被 Git 忽略：

```text
outputs/
  launcher/               TowerLauncher.exe 和 runtime 文件
  tools/<application-id>/ Tool.exe 和 runtime 文件
  catalog/tools.json      生成的运行时目录
```

目录中的可执行文件路径相对于 `outputs/`，因此无需修改路径即可整体移动该目录。

## 单实例行为

每个应用都会使用由稳定 Application ID 派生的按用户 lock file 与 loopback activation channel。第二个进程会请求第一个进程恢复并聚焦其窗口，随后退出。
