# 构建环境

## 必备软件

- Windows 10 或更新版本，x64
- Azul Zulu JDK 21 x64
- PowerShell 5.1 或更新版本
- 首次 Gradle dependency resolution 所需的 Internet 访问
- 建议使用 IntelliJ IDEA，但脚本不依赖它

脚本会从当前进程、用户环境或机器环境读取 `JAVA_HOME`。如果找不到 Java 21 安装，会显示配置指引并失败。

## 仓库构建缓存

生命周期脚本会将 `GRADLE_USER_HOME` 设置为仓库根目录中被 Git 忽略的 `.gradle-user-home/`。因此，启动器与每个独立工具会共用一份 Gradle distribution、dependency cache、Compose packaging tool 和本地 build cache，而不依赖 Java 与机器相关的 `user.home` 值。

Gradle Wrapper 下载的网络超时为 120 秒。填充冷缓存仍需要 Internet 访问，之后的构建会复用已下载 artifact。

构建脚本默认采用增量模式。仅在诊断或验证时确实需要干净重建，才向 `Build-Tool.ps1`、`Build-Launcher.ps1` 或 `Build-All.ps1` 传入 `-Clean`。

若机器阻止未签名的 PowerShell script，请使用 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File` 调用生命周期命令。这只会修改该 child process 的策略。受管 automation sandbox 还可能需要构建权限，因为 Gradle 会运行 Java child process 并写入项目的 build 目录。

## 构建入口

独立工具使用 `scripts/Build-Tool.ps1 -Id <application-id>` 构建；新建或删除工具还须走 [工具生命周期](tool-lifecycle.md) 中的脚本。内置 Tab 属于启动器项目，改动后使用 `scripts/Build-Launcher.ps1` 运行测试并重建 `outputs/launcher/`，不单独生成便携目录。完整发布可使用 `scripts/Build-All.ps1`。Tab 的开发边界见 [Tab 开发规范](launcher-tabs.md)。

在仓库根目录运行构建命令，例如：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Build-All.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Build-Launcher.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Build-Tool.ps1 -Id "dev.towertools.proxyenvmanager"
```

## 便携打包

Compose Desktop 的 `createDistributable` task 会创建 Windows application image，其中包含 `.exe`、应用 library 和精简 Java runtime。它是一个目录而非单文件可执行程序，必须整体复制。
仓库的构建脚本会在发布到 `outputs/` 时，将根目录 `LICENSE`、`THIRD_PARTY_NOTICES.md` 和 `third-party/licenses/` 一并复制到工具和启动器目录。直接运行项目内的 `createDistributable` 只生成 Compose application image。

新增或升级依赖后，发布前检查便携目录实际包含的 JAR、原生库和 Java runtime。对新增组件核对官方许可与必须保留的版权、NOTICE 文件，在 `THIRD_PARTY_NOTICES.md` 中记录组件、版本、许可和来源，并将需要随包分发的许可或 NOTICE 文本加入 `third-party/licenses/`。已有组件版本或许可发生变化时同步更新记录，然后重新运行仓库构建脚本，确认新声明和文本已进入便携目录。根目录 `LICENSE` 是仓库原创代码的 MIT 许可，无需因普通依赖变更而改写，也不能代替第三方组件的许可。

未配置 MSI、shortcut、signing 或自动更新机制。

所有项目的 `nativeDistributions.windows.iconFile` 指向项目内的 `icons/app-icon.ico`；运行时主窗口使用 `src/main/resources/app-icon.png`。因此在 IDEA 运行和构建便携目录时会分别显示窗口图标和 EXE 图标。
仓库构建脚本还会把 `app-icon.ico` 放到每个便携目录根部，供 Windows 快捷方式在图标缓存未更新时明确指定图标来源。
各应用使用仓库已记录许可的 JNA 5.19.1，在创建窗口前向 Windows 设置稳定 AppUserModelID；这使任务栏使用应用自身的图标。
