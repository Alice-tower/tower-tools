# 图片裁剪与分割

对单张图片进行旋转、比例裁剪和网格分割，在左右预览确认后按原格式输出。工具不会修改原图片。

- Project: `ImageProcessor`
- Application ID: `dev.towertools.imageprocessor`
- Version: `1.1.0`
- Author: `Alice-tower`

## 功能

- 拖入或选择一张 JPG/JPEG、PNG、BMP、GIF、WebP、TIFF/TIF 图片。
- 按原始、顺时针 90°、180°、逆时针 90°旋转。
- 按预设比例居中裁剪，或在精细裁剪窗口中以实际像素调整裁剪框，不拉伸、不补黑边。
- 精细裁剪支持输入像素宽高、查看相对原图百分比，以及拖动和缩放裁剪框。
- 按左右、上下、2×2、2×3、3×2、3×3 网格分割。
- 预览旋转、裁剪、分割共同作用的结果。
- 将输出目录保存在应用专属的本地配置中。
- 输出前阻止任何同名文件冲突。

动态 GIF、动态 WebP 和多页 TIFF 会被明确拒绝，避免静默丢失帧或页面。

## Run

```powershell
.\gradlew.bat run
```

## Build portable directory

```powershell
.\gradlew.bat createDistributable
```

仓库生命周期脚本会将最终 Windows 便携目录发布到共享的 `outputs/` 目录。
