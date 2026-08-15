package dev.towertools.imageprocessor

import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.FileImageOutputStream

object OutputNaming {
    fun paths(
        descriptor: SourceDescriptor,
        outputDirectory: Path,
        rotation: Rotation,
        cropMode: CropMode,
        count: Int,
    ): List<Path> = (1..count).map { index ->
        outputDirectory.resolve(
            "${descriptor.baseName}_${rotation.token}_${cropMode.token}_${index}.${descriptor.extension}",
        )
    }
}

object OutputExporter {
    fun export(
        loadedImage: LoadedImage,
        rotation: Rotation,
        cropMode: CropMode,
        splitMode: SplitMode,
        outputDirectory: Path,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): ExportSummary {
        val descriptor = loadedImage.descriptor
        ImageIOService.verifyUnchanged(descriptor)
        ensureMemoryAvailable(descriptor, rotation, cropMode)
        val count = splitMode.columns * splitMode.rows
        val outputPaths = OutputNaming.paths(descriptor, outputDirectory, rotation, cropMode, count)
        val collisions = outputPaths.filter(Files::exists)
        if (collisions.isNotEmpty()) throw OutputCollisionException(collisions)

        val temporaryFiles = mutableListOf<Path>()
        try {
            val source = ImageIOService.readFull(descriptor)
            val processed = ImageTransforms.process(source, rotation, cropMode)
            val pieces = ImageGeometry.splitRects(processed.width, processed.height, splitMode)
            pieces.forEachIndexed { index, rect ->
                onProgress(index + 1, pieces.size)
                val temporary = Files.createTempFile(outputDirectory, ".imageprocessor-", ".tmp")
                temporaryFiles.add(temporary)
                val piece = processed.getSubimage(rect.x, rect.y, rect.width, rect.height)
                writeImage(piece, descriptor, temporary)
            }

            temporaryFiles.zip(outputPaths).forEach { (temporary, output) ->
                try {
                    Files.move(
                        temporary,
                        output,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            return ExportSummary(outputPaths)
        } catch (error: OutputCollisionException) {
            throw error
        } catch (error: UserFacingException) {
            throw error
        } catch (error: OutOfMemoryError) {
            throw UserFacingException("输出图片时内存不足，请关闭其他程序或使用尺寸较小的图片。")
        } catch (error: Exception) {
            throw UserFacingException("输出失败：${error.message ?: error.javaClass.simpleName}")
        } finally {
            temporaryFiles.forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun ensureMemoryAvailable(descriptor: SourceDescriptor, rotation: Rotation, cropMode: CropMode) {
        val (width, height) = ImageGeometry.outputSize(
            descriptor.orientedWidth,
            descriptor.orientedHeight,
            rotation,
            cropMode,
        )
        val sourceBytes = descriptor.orientedWidth.toLong() * descriptor.orientedHeight * descriptor.estimatedBytesPerPixel
        val outputBytes = width.toLong() * height * descriptor.estimatedBytesPerPixel
        val estimated = sourceBytes + outputBytes * 2
        val limit = (Runtime.getRuntime().maxMemory() * 0.72).toLong()
        if (estimated > limit) {
            throw UserFacingException(
                "图片尺寸过大，预计需要约 ${estimated / 1024 / 1024} MB 内存，当前安全上限约 ${limit / 1024 / 1024} MB。",
            )
        }
    }

    private fun writeImage(source: BufferedImage, descriptor: SourceDescriptor, path: Path) {
        val formatName = when (descriptor.format) {
            "JPEG" -> "jpeg"
            "TIFF" -> "tiff"
            else -> descriptor.format.lowercase()
        }
        val writers = ImageIO.getImageWritersByFormatName(formatName)
        if (!writers.hasNext()) throw UserFacingException("当前便携版缺少 ${descriptor.format} 编码器。")
        val writer = writers.next()
        val image = if (descriptor.format == "JPEG" && source.colorModel.hasAlpha()) flattenOnWhite(source) else source
        try {
            FileImageOutputStream(path.toFile()).use { output ->
                writer.output = output
                val param = writer.defaultWriteParam
                configureCompression(param, descriptor)
                writer.write(null, IIOImage(image, null, null), param)
            }
        } finally {
            writer.dispose()
        }
    }

    private fun configureCompression(param: ImageWriteParam, descriptor: SourceDescriptor) {
        if (!param.canWriteCompressed()) return
        val types = param.compressionTypes?.toList().orEmpty()
        when (descriptor.format) {
            "JPEG" -> {
                param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                param.compressionQuality = 0.95f
            }
            "WEBP" -> {
                param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                val desired = if (descriptor.webpLossless) "lossless" else "lossy"
                types.firstOrNull { it.equals(desired, ignoreCase = true) }?.let { param.compressionType = it }
                if (!descriptor.webpLossless) param.compressionQuality = 0.95f
            }
            "TIFF" -> {
                val preferred = listOf("Deflate", "ZLib", "LZW")
                    .firstNotNullOfOrNull { name -> types.firstOrNull { it.equals(name, ignoreCase = true) } }
                if (preferred != null) {
                    param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                    param.compressionType = preferred
                }
            }
        }
    }

    private fun flattenOnWhite(source: BufferedImage): BufferedImage {
        val destination = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        val graphics = destination.createGraphics()
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, destination.width, destination.height)
        graphics.drawImage(source, 0, 0, null)
        graphics.dispose()
        return destination
    }
}
