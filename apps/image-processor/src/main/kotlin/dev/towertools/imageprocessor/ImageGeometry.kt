package dev.towertools.imageprocessor

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import kotlin.math.floor
import kotlin.math.min

object ImageGeometry {
    fun rotatedSize(width: Int, height: Int, rotation: Rotation): Pair<Int, Int> =
        if (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90) {
            height to width
        } else {
            width to height
        }

    fun cropRect(width: Int, height: Int, cropMode: CropMode): ImageRect {
        if (cropMode is CropMode.Original) return ImageRect(0, 0, width, height)
        cropMode as CropMode.Ratio
        require(cropMode.widthRatio.isFinite() && cropMode.widthRatio > 0)
        require(cropMode.heightRatio.isFinite() && cropMode.heightRatio > 0)

        val target = cropMode.widthRatio / cropMode.heightRatio
        val current = width.toDouble() / height.toDouble()
        val cropWidth: Int
        val cropHeight: Int
        if (current > target) {
            cropHeight = height
            cropWidth = floor(height * target).toInt().coerceIn(1, width)
        } else {
            cropWidth = width
            cropHeight = floor(width / target).toInt().coerceIn(1, height)
        }
        return ImageRect((width - cropWidth) / 2, (height - cropHeight) / 2, cropWidth, cropHeight)
    }

    fun splitRects(width: Int, height: Int, splitMode: SplitMode): List<ImageRect> = buildList {
        for (row in 0 until splitMode.rows) {
            val top = row * height / splitMode.rows
            val bottom = (row + 1) * height / splitMode.rows
            for (column in 0 until splitMode.columns) {
                val left = column * width / splitMode.columns
                val right = (column + 1) * width / splitMode.columns
                add(ImageRect(left, top, right - left, bottom - top))
            }
        }
    }

    fun outputSize(width: Int, height: Int, rotation: Rotation, cropMode: CropMode): Pair<Int, Int> {
        val (rotatedWidth, rotatedHeight) = rotatedSize(width, height, rotation)
        val crop = cropRect(rotatedWidth, rotatedHeight, cropMode)
        return crop.width to crop.height
    }
}

object ImageTransforms {
    fun applyExifOrientation(source: BufferedImage, orientation: Int): BufferedImage = when (orientation) {
        2 -> draw(source, source.width, source.height, AffineTransform(-1.0, 0.0, 0.0, 1.0, source.width.toDouble(), 0.0))
        3 -> rotate(source, Rotation.DEGREES_180)
        4 -> draw(source, source.width, source.height, AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, source.height.toDouble()))
        5 -> draw(source, source.height, source.width, AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0))
        6 -> rotate(source, Rotation.CLOCKWISE_90)
        7 -> draw(
            source,
            source.height,
            source.width,
            AffineTransform(0.0, -1.0, -1.0, 0.0, source.height.toDouble(), source.width.toDouble()),
        )
        8 -> rotate(source, Rotation.COUNTERCLOCKWISE_90)
        else -> source
    }

    fun rotate(source: BufferedImage, rotation: Rotation): BufferedImage = when (rotation) {
        Rotation.ORIGINAL -> source
        Rotation.CLOCKWISE_90 -> draw(
            source,
            source.height,
            source.width,
            AffineTransform(0.0, 1.0, -1.0, 0.0, source.height.toDouble(), 0.0),
        )
        Rotation.DEGREES_180 -> draw(
            source,
            source.width,
            source.height,
            AffineTransform(-1.0, 0.0, 0.0, -1.0, source.width.toDouble(), source.height.toDouble()),
        )
        Rotation.COUNTERCLOCKWISE_90 -> draw(
            source,
            source.height,
            source.width,
            AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, source.width.toDouble()),
        )
    }

    fun crop(source: BufferedImage, cropMode: CropMode): BufferedImage {
        val rect = ImageGeometry.cropRect(source.width, source.height, cropMode)
        return if (rect.x == 0 && rect.y == 0 && rect.width == source.width && rect.height == source.height) {
            source
        } else {
            source.getSubimage(rect.x, rect.y, rect.width, rect.height)
        }
    }

    fun process(source: BufferedImage, rotation: Rotation, cropMode: CropMode): BufferedImage =
        crop(rotate(source, rotation), cropMode)

    fun splitPreview(source: BufferedImage, splitMode: SplitMode): BufferedImage {
        if (splitMode == SplitMode.ORIGINAL) return source
        val maxGap = min(source.width / (splitMode.columns * 5), source.height / (splitMode.rows * 5))
        val gap = min(10, maxGap.coerceAtLeast(1))
        val availableWidth = (source.width - gap * (splitMode.columns - 1)).coerceAtLeast(splitMode.columns)
        val availableHeight = (source.height - gap * (splitMode.rows - 1)).coerceAtLeast(splitMode.rows)
        val destination = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = destination.createGraphics()
        graphics.composite = AlphaComposite.Src
        graphics.color = Color(48, 52, 60, 255)
        graphics.fillRect(0, 0, destination.width, destination.height)
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)

        val sourceRects = ImageGeometry.splitRects(source.width, source.height, splitMode)
        sourceRects.forEachIndexed { index, rect ->
            val row = index / splitMode.columns
            val column = index % splitMode.columns
            val dx1 = column * availableWidth / splitMode.columns + column * gap
            val dx2 = (column + 1) * availableWidth / splitMode.columns + column * gap
            val dy1 = row * availableHeight / splitMode.rows + row * gap
            val dy2 = (row + 1) * availableHeight / splitMode.rows + row * gap
            graphics.drawImage(
                source,
                dx1,
                dy1,
                dx2,
                dy2,
                rect.x,
                rect.y,
                rect.x + rect.width,
                rect.y + rect.height,
                null,
            )
        }
        graphics.dispose()
        return destination
    }

    private fun draw(source: BufferedImage, width: Int, height: Int, transform: AffineTransform): BufferedImage {
        val destination = compatibleImage(source, width, height)
        val graphics = destination.createGraphics()
        graphics.composite = AlphaComposite.Src
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        graphics.drawImage(source, transform, null)
        graphics.dispose()
        return destination
    }

    private fun compatibleImage(source: BufferedImage, width: Int, height: Int): BufferedImage = runCatching {
        val colorModel = source.colorModel
        BufferedImage(
            colorModel,
            colorModel.createCompatibleWritableRaster(width, height),
            colorModel.isAlphaPremultiplied,
            null,
        )
    }.getOrElse {
        BufferedImage(width, height, if (source.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
    }
}
