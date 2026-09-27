# __APP_DISPLAY_NAME__

__APP_DESCRIPTION__

- 项目：`__APP_PROJECT_NAME__`
- Application ID：`__APP_ID__`
- 版本：`__APP_VERSION__`
- 作者：`Alice-tower`

## 运行

```powershell
.\gradlew.bat run
```

## 构建便携目录

```powershell
.\gradlew.bat createDistributable
```

仓库生命周期脚本会将生成的 Windows application image 复制到共享的 `outputs/` 目录。

项目模板自带 Tower Tools 统一图标，用于窗口和 Windows 可执行文件。
启动时以 Application ID 登记 Windows 任务栏身份，让任务栏使用该图标。
