package dev.towertools.imageprocessor

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import java.awt.image.BufferedImage
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import kotlin.math.ceil
import kotlin.math.max

object ImageIOService {
    private const val PREVIEW_LONG_EDGE = 1800
    private val supportedExtensions = mapOf(
        "JPEG" to setOf("jpg", "jpeg"),
        "PNG" to setOf("png"),
        "BMP" to setOf("bmp"),
        "GIF" to setOf("gif"),
        "WEBP" to setOf("webp"),
        "TIFF" to setOf("tif", "tiff"),
    )

    init {
        ImageIO.scanForPlugins()
    }

    fun load(path: Path): LoadedImage {
        if (!Files.isRegularFile(path)) throw UserFacingException("请拖入一个有效的图片文件。")
        val fileName = path.fileName.toString()
        val extensionIndex = fileName.lastIndexOf('.')
        if (extensionIndex <= 0 || extensionIndex == fileName.lastIndex) {
            throw UserFacingException("图片文件缺少受支持的扩展名。")
        }
        val extension = fileName.substring(extensionIndex + 1)
        val extensionLower = extension.lowercase()
        val baseName = fileName.substring(0, extensionIndex)
        val webpInfo = if (extensionLower == "webp") inspectWebp(path) else WebpInfo(false, false)
        if (webpInfo.animated) throw UserFacingException("暂不支持动态 WebP，请使用静态 WebP 图片。")

        val orientation = readExifOrientation(path)
        return withReader(path) { reader ->
            val format = normalizeFormat(reader.formatName)
            val allowed = supportedExtensions[format]
                ?: throw UserFacingException("不支持该图片格式。支持 JPG/JPEG、PNG、BMP、GIF、WebP、TIFF/TIF。")
            if (extensionLower !in allowed) {
                throw UserFacingException("文件内容是 $format，但扩展名是 .$extension；请使用格式与扩展名一致的图片。")
            }

            val imageCount = runCatching { reader.getNumImages(true) }.getOrDefault(1)
            if (format == "GIF" && imageCount > 1) {
                throw UserFacingException("暂不支持动态 GIF，请使用单帧 GIF 图片。")
            }
            if (format == "TIFF" && imageCount > 1) {
                throw UserFacingException("暂不支持多页 TIFF，请使用单页 TIFF 图片。")
            }

            val rawWidth = reader.getWidth(0)
            val rawHeight = reader.getHeight(0)
            if (rawWidth <= 0 || rawHeight <= 0) throw UserFacingException("图片尺寸无效。")
            val sample = ceil(max(rawWidth, rawHeight).toDouble() / PREVIEW_LONG_EDGE).toInt().coerceAtLeast(1)
            val param = reader.defaultReadParam
            if (sample > 1) param.setSourceSubsampling(sample, sample, 0, 0)
            val decoded = reader.read(0, param) ?: throw UserFacingException("无法解码该图片。")
            val preview = ImageTransforms.applyExifOrientation(decoded, orientation)
            val swapsDimensions = orientation in setOf(5, 6, 7, 8)
            val orientedWidth = if (swapsDimensions) rawHeight else rawWidth
            val orientedHeight = if (swapsDimensions) rawWidth else rawHeight
            val bits = decoded.colorModel.componentSize.maxOrNull() ?: 8
            val descriptor = SourceDescriptor(
                path = path.toAbsolutePath().normalize(),
                fileName = fileName,
                baseName = baseName,
                extension = extension,
                format = format,
                rawWidth = rawWidth,
                rawHeight = rawHeight,
                orientedWidth = orientedWidth,
                orientedHeight = orientedHeight,
                fileSize = Files.size(path),
                lastModifiedMillis = Files.getLastModifiedTime(path).toMillis(),
                exifOrientation = orientation,
                webpLossless = webpInfo.lossless,
                estimatedBytesPerPixel = if (bits > 8) 8 else 4,
            )
            LoadedImage(descriptor, preview)
        }
    }

    fun readFull(descriptor: SourceDescriptor): BufferedImage {
        verifyUnchanged(descriptor)
        return withReader(descriptor.path) { reader ->
            val image = reader.read(0) ?: throw UserFacingException("无法重新读取原图片。")
            ImageTransforms.applyExifOrientation(image, descriptor.exifOrientation)
        }
    }

    fun verifyUnchanged(descriptor: SourceDescriptor) {
        if (!Files.isRegularFile(descriptor.path) ||
            Files.size(descriptor.path) != descriptor.fileSize ||
            Files.getLastModifiedTime(descriptor.path).toMillis() != descriptor.lastModifiedMillis
        ) {
            throw UserFacingException("原图片在加载后发生了变化，请重新拖入后再输出。")
        }
    }

    private fun <T> withReader(path: Path, block: (ImageReader) -> T): T {
        val input = ImageIO.createImageInputStream(path.toFile())
            ?: throw UserFacingException("无法打开图片文件。")
        input.use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) {
                throw UserFacingException("无法识别图片格式。支持 JPG/JPEG、PNG、BMP、GIF、WebP、TIFF/TIF。")
            }
            val reader = readers.next()
            try {
                reader.setInput(stream, false, false)
                return block(reader)
            } catch (error: UserFacingException) {
                throw error
            } catch (error: OutOfMemoryError) {
                throw UserFacingException("图片过大，当前可用内存不足。")
            } catch (error: Exception) {
                throw UserFacingException("图片读取失败：${error.message ?: error.javaClass.simpleName}")
            } finally {
                reader.dispose()
            }
        }
    }

    private fun normalizeFormat(name: String): String = when (name.uppercase()) {
        "JPG", "JPEG" -> "JPEG"
        "TIF", "TIFF", "BIGTIFF" -> "TIFF"
        "WEBP" -> "WEBP"
        else -> name.uppercase()
    }

    private fun readExifOrientation(path: Path): Int = runCatching {
        ImageMetadataReader.readMetadata(path.toFile())
            .getFirstDirectoryOfType(ExifIFD0Directory::class.java)
            ?.getInteger(ExifIFD0Directory.TAG_ORIENTATION)
            ?.takeIf { it in 1..8 }
            ?: 1
    }.getOrDefault(1)

    private data class WebpInfo(val animated: Boolean, val lossless: Boolean)

    private fun inspectWebp(path: Path): WebpInfo = runCatching {
        RandomAccessFile(path.toFile(), "r").use { file ->
            if (file.length() < 12 || readFourCc(file) != "RIFF") return@use WebpInfo(false, false)
            file.skipBytes(4)
            if (readFourCc(file) != "WEBP") return@use WebpInfo(false, false)
            var animated = false
            var lossless = false
            while (file.filePointer + 8 <= file.length()) {
                val chunk = readFourCc(file)
                val size = Integer.toUnsignedLong(Integer.reverseBytes(file.readInt()))
                val dataStart = file.filePointer
                when (chunk) {
                    "ANIM", "ANMF" -> animated = true
                    "VP8L" -> lossless = true
                }
                val next = dataStart + size + (size and 1L)
                if (next <= dataStart || next > file.length()) break
                file.seek(next)
            }
            WebpInfo(animated, lossless)
        }
    }.getOrDefault(WebpInfo(false, false))

    private fun readFourCc(file: RandomAccessFile): String {
        val bytes = ByteArray(4)
        file.readFully(bytes)
        return bytes.toString(Charsets.US_ASCII)
    }
}
