# Tower Tools

Tower Tools 是使用 Kotlin/JVM 和 Compose Desktop 编写的 Windows x64 便携桌面工具箱。**工具塔**把独立 GUI 工具、本机 `.cmd` 项目和 Git 仓库的入口集中到同一个窗口，分别由“工具”“CMD”“仓库”三个 Tab 管理。

## 快速开始

仓库不提交构建产物。首次从源码使用时，请在 Windows 10 或更新版本（x64）上准备 JDK 21（设置 `JAVA_HOME`）和 PowerShell 5.1 或更新版本，然后在仓库根目录运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Build-All.ps1
```

首次构建需要联网下载依赖。构建完成后，运行 `outputs\launcher\TowerLauncher.exe`。`outputs/` 内含启动器、独立工具及各自的 Java 运行时；复制到另一台电脑时请保留整个目录结构，目标电脑无需另装 Java。完整构建要求见 [构建环境](docs/build-environment.md)。

## 工具塔的三个 Tab

### 工具：启动便携应用

- 显示本仓库已构建的独立工具。双击条目启动工具；右键可打开所在目录，或编辑分类与排序。
- 左侧可按“收藏”“全部”和自定义分类浏览；点爱心收藏，拖动分类手柄调整分组顺序。这里管理的是已打包工具，不负责安装或更新。

### CMD：登记常用脚本

- 在“设置”中登记本机 `.cmd` 项目；双击条目运行脚本。可按分类、排序和收藏整理，右键可编辑或删除登记。
- 可选填本地 Web 端口，以查看端口是否可连接，并用“打开浏览器”访问项目页面。**端口可连接不等于该项目已运行。**

### 仓库：浏览本地 Git 仓库

- 在“设置”中添加扫描路径，再点击“刷新”。每条路径只扫描其**直接子目录**中含 `.git` 目录的仓库；重新打开启动器会显示上次结果，需要手动刷新才能更新列表。
- 双击仓库条目打开**仓库目录**；右键“打开所在目录”打开其**父目录**。分类、排序、收藏和展示名称都可单独管理。
- 条目右侧可打开根目录的 README、AGENTS、已识别的 GitHub 远端网页，或用本机 VS Code 打开仓库。对应文件或 GitHub 远端不存在时按钮置灰；“使用极简按钮”可在设置中开启。

三个 Tab 的分类、收藏和排序互不影响。更多操作细节见 [工具塔说明](launcher/TowerLauncher/README.md)。

## 仓库中的独立工具

| 工具 | 用途 |
| --- | --- |
| [代理环境变量](apps/proxy-env-manager/README.md) | 查看和管理当前用户的 HTTP 代理环境变量。 |
| [图片裁剪与分割](apps/image-processor/README.md) | 旋转、裁剪和分割单张图片，预览后输出，不修改原图。 |
| [图片元数据解析器](apps/nai-box/README.md) | 本地查看图片元数据、AI 生成参数和角色卡内容。 |
| [影音转写](apps/media-transcriber/README.md) | 从媒体提取 MP3，使用自备的 FFmpeg、CUDA 版 Whisper 和模型转写音频。 |
| [本地资源语义管理器](apps/resource-tagger/README.md) | 扫描指定目录，以标签和别名整理资源并定位文件，不修改资源本身。 |

## 数据、文档与许可

分类、项目登记等用户设置保存在 `%LOCALAPPDATA%\Alice-tower\` 下；重新构建或替换便携程序不会覆盖这些数据。`outputs/` 已被 Git 忽略。

开发者可从 [独立工具生命周期](docs/tool-lifecycle.md)、[Tab 开发规范](docs/launcher-tabs.md)和[项目架构](docs/architecture.md)开始。仓库原创代码使用 [MIT 许可证](LICENSE)；打包组件的许可和来源见[第三方声明](THIRD_PARTY_NOTICES.md)。
