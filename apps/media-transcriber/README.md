# 影音转写

从视频提取 MP3，并使用本地 Whisper CUDA 模型将音频转写为 TXT。

- 项目：`MediaTranscriber`
- Application ID：`dev.towertools.mediatranscriber`
- 版本：`1.0.2`
- 作者：`Alice-tower`

## 使用

1. 进入主界面后可在“设置”中选择输出目录；右上角“打开目录”用于打开已经设置的输出目录。未设置时仍可查看界面和配置依赖，但输出操作不可用。
2. 在“设置”中选择 FFmpeg 所在目录（也可留空使用 `PATH`）、CUDA 版 `whisper-cli.exe` 和本地模型文件。
3. 拖入或选择一个媒体文件。视频会显示并固定使用默认音轨；没有默认标记时使用第一音轨。
4. 视频可导出高质量 VBR MP3；视频默认音轨和直接加载的音频均可转写为 UTF-8 TXT。

“每行添加累计分钟 [mm:ss] 时间”会实时保存。转写完成后双击文字卡片中的文档图标可用 Windows 默认程序打开 TXT。媒体探测、MP3 导出、音频准备和转写任务支持取消，不支持暂停或断点续转。

## 外部依赖

本工具不下载、不捆绑也不复制 FFmpeg、Whisper、CUDA、模型或测试媒体。媒体功能要求 `ffmpeg.exe` 和 `ffprobe.exe` 均能运行；转写功能还要求用户选择的 `whisper-cli.exe` 在快速检查中明确发现 CUDA/NVIDIA 设备，并通过模型实际加载验证。未确认 CUDA 时不会回退到 CPU。

设置、临时文件和滚动日志位于 `%LOCALAPPDATA%\Alice-tower\TowerTools\dev.towertools.mediatranscriber\`，不会写入便携程序目录。依赖、自检、进度、完成和用户可理解的错误显示在窗口底部；完整异常保存在 `logs\` 下的滚动日志文件中。

常见错误包括：输出目录不可写、FFmpeg/ffprobe 缺失、视频无音轨、`libmp3lame` 不可用、CUDA/NVIDIA 设备未识别、模型加载失败、显存或磁盘空间不足、Whisper JSON 无效，以及 Windows 没有 TXT 默认打开程序。

## 开发运行

```powershell
.\gradlew.bat run
```

## 构建便携目录

```powershell
.\gradlew.bat createDistributable
```

仓库生命周期脚本会将生成的 Windows application image 复制到共享的 `outputs/` 目录。
