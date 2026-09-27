# Tower Tools 图标

- `tower-tools-original.png`：用户提供的 1254 × 1254 透明原图。
- `app-icon.png`：保留透明度、缩小为 512 × 512 的窗口图标。
- `app-icon.ico`：包含 16、24、32、48、64、128、256 像素的 Windows 可执行文件图标。

模板、启动器和每个工具各自保存窗口 PNG 与 Windows ICO，以便独立构建。仓库构建脚本也把 ICO 复制到便携目录根部，供快捷方式直接使用。统一更换图标时，应从原图重新生成这两个文件，将它们同步到各项目的 `src/main/resources/` 与 `icons/`，然后运行 `scripts/Build-All.ps1`。
