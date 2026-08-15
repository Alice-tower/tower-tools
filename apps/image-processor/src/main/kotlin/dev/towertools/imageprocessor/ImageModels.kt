package dev.towertools.imageprocessor

import java.awt.image.BufferedImage
import java.nio.file.Path

enum class Rotation(val label: String, val token: String) {
    ORIGINAL("原始", "r0"),
    CLOCKWISE_90("顺时针 90°", "r90"),
    DEGREES_180("180°", "r180"),
    COUNTERCLOCKWISE_90("逆时针 90°", "r270"),
}

sealed interface CropMode {
    val label: String
    val token: String

    data object Original : CropMode {
        override val label = "原始"
        override val token = "coriginal"
    }

    data class Ratio(
        override val label: String,
        val widthRatio: Double,
        val heightRatio: Double,
        override val token: String,
    ) : CropMode

    data class Fine(val rect: ImageRect) : CropMode {
        override val label = "精细裁剪"
        override val token = "cfine_${rect.x}x${rect.y}_${rect.width}x${rect.height}"
    }
}

val cropPresets = listOf(
    CropMode.Ratio("1:1", 1.0, 1.0, "c1x1"),
    CropMode.Ratio("16:9", 16.0, 9.0, "c16x9"),
    CropMode.Ratio("9:16", 9.0, 16.0, "c9x16"),
    CropMode.Ratio("4:3", 4.0, 3.0, "c4x3"),
    CropMode.Ratio("3:4", 3.0, 4.0, "c3x4"),
    CropMode.Ratio("3:2", 3.0, 2.0, "c3x2"),
    CropMode.Ratio("2:3", 2.0, 3.0, "c2x3"),
    CropMode.Ratio("18:9", 18.0, 9.0, "c18x9"),
    CropMode.Ratio("9:18", 9.0, 18.0, "c9x18"),
)

enum class SplitMode(val label: String, val columns: Int, val rows: Int) {
    ORIGINAL("原始", 1, 1),
    LEFT_RIGHT("左右各一半", 2, 1),
    TOP_BOTTOM("上下各一半", 1, 2),
    GRID_2_2("2×2", 2, 2),
    GRID_2_3("2×3", 2, 3),
    GRID_3_2("3×2", 3, 2),
    GRID_3_3("3×3", 3, 3),
}

data class SourceDescriptor(
    val path: Path,
    val fileName: String,
    val baseName: String,
    val extension: String,
    val format: String,
    val rawWidth: Int,
    val rawHeight: Int,
    val orientedWidth: Int,
    val orientedHeight: Int,
    val fileSize: Long,
    val lastModifiedMillis: Long,
    val exifOrientation: Int,
    val webpLossless: Boolean,
    val estimatedBytesPerPixel: Int,
)

data class LoadedImage(
    val descriptor: SourceDescriptor,
    val originalPreview: BufferedImage,
)

data class ImageRect(val x: Int, val y: Int, val width: Int, val height: Int)

data class ExportSummary(val files: List<Path>)

class UserFacingException(message: String) : Exception(message)

class OutputCollisionException(val collisions: List<Path>) : Exception("输出目录中已存在同名文件")
