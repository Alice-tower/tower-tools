# 研究资料库

启动并访问本地研究资料库。

- 项目：`ResearchLibraryLauncher`
- Application ID：`dev.towertools.researchlibrarylauncher`
- 版本：`1.0.0`
- 作者：`Alice-tower`

默认启动脚本为 `C:\All\Dev\Repo\research-reference-collection\start.cmd`。如果脚本移动，可以在应用界面中重新选择，新位置保存在用户的本地应用数据目录中。

## 运行

```powershell
.\gradlew.bat run
```

## 构建便携目录

```powershell
.\gradlew.bat createDistributable
```

仓库生命周期脚本会将生成的 Windows 应用镜像复制到共享的 `outputs/` 目录。
