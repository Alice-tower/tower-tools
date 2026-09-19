# 图片元数据解析器

`nai-box` 的 Windows x64 桌面版。使用 Kotlin/JVM 与 Compose Desktop，直接读取本地文件；解析过程不上传图片，也不需要 Node.js 或浏览器。

## 使用

运行 `outputs/tools/dev.towertools.naibox/NaiBox.exe`，或从工具塔启动。可以点击“打开图片”、拖入文件，或按 `Ctrl+V` 粘贴图片/图片文件。每次处理一张图片。

## 能力

- PNG：IHDR、tEXt、zTXt、iTXt、eXIf、iCCP、CRC 校验、IEND 后数据与内嵌 PNG 扫描。
- JPEG：COM、APP1 EXIF/XMP、APP2 ICC 与 SOF 尺寸。
- WebP：VP8X/VP8/VP8L 尺寸、EXIF、XMP、ICCP 与标志位警告。
- GIF/BMP：显示尺寸；HEIF/AVIF：提示暂未支持元数据解析。
- NovelAI：识别 `Software`/`Source`、模型与 hash、提示词、生成参数、V4/V4.5 角色及坐标，并还原 `/ai/generate-image` 请求体。
- A1111 / Forge：解析 `parameters` 中的正反向提示词和生成参数。
- SillyTavern / TavernAI：从 base64 文本块识别 v2/v3 角色卡，显示概要、世界书条目数、备选开场白、正文与原始 JSON。
- 原始元数据：展示文本和块结构；识别文本块里的 base64 JSON、文本、图片、ZIP 与压缩数据，支持复制、图片预览和保存解码结果。

请求体中的参考图、img2img 原图和 inpaint 遮罩无法从元数据恢复，需要使用者补齐。GIF Comment Extension、WebP 动画帧和非 PNG 尾部私有数据目前不做语义解析。容器扫描最多检查文件前 32 MiB，与原 `nai-box` 的默认扫描范围一致。
预览会在后台缩放至最长边 1280 像素，不修改原文件；压缩文本块解压后超过 32 MiB 时会显示解析警告。

## 开发与验证

源项目的 11 个合成样例位于 `src/test/resources/samples/`，用于跨格式回归测试。仓库生命周期脚本会执行测试并在 `outputs/` 生成便携目录：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Build-Tool.ps1 -Id dev.towertools.naibox
```

项目：`NaiBox` · Application ID：`dev.towertools.naibox` · 版本：`1.0.0`。
