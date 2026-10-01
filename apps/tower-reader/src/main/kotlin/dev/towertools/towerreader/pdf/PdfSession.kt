package dev.towertools.towerreader.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.min
import kotlin.math.sqrt

data class PdfOutlineEntry(val title: String, val pageIndex: Int)
class PdfException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Synchronous: open/render/close on a background thread. Images are shared read-only snapshots.
 * Limits cover output rasters, not document parsing or embedded-image allocations. */
class PdfSession(path: Path) : AutoCloseable {
    private var document: PDDocument? = openDocument(path)
    private val images = LinkedHashMap<RenderKey, BufferedImage>(16, 0.75f, true)
    private var imageBytes = 0L
    val pageCount: Int
    val title: String?
    val author: String?
    val outline: List<PdfOutlineEntry>

    init {
        val opened = document!!
        try {
            pageCount = opened.numberOfPages
            if (pageCount == 0) throw PdfException("PDF 文件没有可阅读的页面。")
            title = opened.documentInformation.title?.trim()?.takeIf { it.isNotEmpty() }
            author = opened.documentInformation.author?.trim()?.takeIf { it.isNotEmpty() }
            outline = readOutline(opened)
        } catch (error: Exception) {
            document = null
            try { opened.close() } catch (closeError: Exception) { error.addSuppressed(closeError) }
            throw if (error is PdfException) error else PdfException("无法读取 PDF 信息，文件可能已损坏。", error)
        }
    }

    internal val cachedImageBytes: Long get() = synchronized(this) { imageBytes }
    internal val cachedImageCount: Int get() = synchronized(this) { images.size }

    /** Fits the rotated crop box to the pixel viewport, then applies zoom (0 < zoom <= 3).
     * Huge requests are proportionally reduced to the output pixel/edge limits. */
    @Synchronized
    fun render(pageIndex: Int, maxWidth: Int, maxHeight: Int, zoom: Double = 1.0): BufferedImage {
        val opened = checkNotNull(document) { "PDF 已关闭。" }
        require(pageIndex in 0 until pageCount) { "页码超出 PDF 范围。" }
        require(maxWidth > 0 && maxHeight > 0) { "页面显示尺寸必须大于零。" }
        require(zoom.isFinite() && zoom > 0 && zoom <= 3) { "缩放比例必须大于零且不超过 3。" }
        try {
            val page = opened.getPage(pageIndex)
            val width = page.cropBox.width.toDouble()
            val height = page.cropBox.height.toDouble()
            if (!width.isFinite() || !height.isFinite() || width <= 0 || height <= 0) {
                throw PdfException("此 PDF 页面的尺寸无效。")
            }
            val rotated = page.rotation == 90 || page.rotation == 270
            val displayWidth = if (rotated) height else width
            val displayHeight = if (rotated) width else height
            var scale = min(maxWidth / displayWidth, maxHeight / displayHeight) * zoom
            scale = min(scale, sqrt(MAX_IMAGE_PIXELS.toDouble() / width / height))
            scale = min(scale, MAX_IMAGE_EDGE / maxOf(width, height))
            // Round downward so float conversion cannot violate the output bound.
            val floatScale = scale.toFloat()
            val renderScale = if (floatScale.toDouble() > scale) Math.nextDown(floatScale) else floatScale
            if (!renderScale.isFinite() || renderScale <= 0f) throw PdfException("此 PDF 页面的尺寸无法显示。")
            val key = RenderKey(pageIndex, renderScale.toBits())
            images[key]?.let { return it }
            val expectedWidth = maxOf(1L, (width * renderScale).toLong())
            val expectedHeight = maxOf(1L, (height * renderScale).toLong())
            evictFor(expectedWidth * expectedHeight * 4)
            // PDFRenderer retains its last raster, so keep each renderer short-lived.
            val image = PDFRenderer(opened).apply { isSubsamplingAllowed = true }
                .renderImage(pageIndex, renderScale, ImageType.RGB)
            val bytes = image.width.toLong() * image.height * 4
            evictFor(bytes)
            images[key] = image
            imageBytes += bytes
            return image
        } catch (error: PdfException) {
            throw error
        } catch (error: Exception) {
            throw PdfException("无法显示第 ${pageIndex + 1} 页，文件可能已损坏或包含不支持的内容。", error)
        }
    }

    private fun evictFor(bytes: Long) {
        val iterator = images.entries.iterator()
        while (imageBytes + bytes > CACHE_BYTES && iterator.hasNext()) {
            val image = iterator.next().value
            imageBytes -= image.width.toLong() * image.height * 4
            iterator.remove()
            // Never flush a shared image: the UI may still hold it.
        }
    }

    @Synchronized
    override fun close() {
        val opened = document ?: return
        document = null
        images.clear()
        imageBytes = 0
        try { opened.close() } catch (error: IOException) { throw PdfException("关闭 PDF 文件失败。", error) }
    }

    private data class RenderKey(val pageIndex: Int, val scaleBits: Int)
    companion object {
        internal const val CACHE_BYTES = 96L * 1024 * 1024
        internal const val MAX_IMAGE_PIXELS = 16L * 1024 * 1024
        private const val MAX_IMAGE_EDGE = 32768.0
        private fun openDocument(path: Path): PDDocument {
            if (!Files.isRegularFile(path)) throw PdfException("找不到 PDF 文件，或路径不是文件。")
            return try { Loader.loadPDF(path.toFile()) }
            catch (error: InvalidPasswordException) {
                throw PdfException("此 PDF 需要密码，暂不支持打开受密码保护的文件。", error)
            } catch (error: Exception) {
                throw PdfException("无法打开 PDF，请检查文件是否完整以及是否有读取权限。", error)
            }
        }
        private fun readOutline(document: PDDocument): List<PdfOutlineEntry> {
            val result = mutableListOf<PdfOutlineEntry>()
            val visited = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
            val pending = ArrayDeque<PDOutlineItem>()
            try { document.documentCatalog.documentOutline?.firstChild?.let(pending::addLast) }
            catch (_: Exception) { return emptyList() }
            while (pending.isNotEmpty() && visited.size < 10000) {
                val item = pending.removeLast()
                if (!visited.add(item.cosObject)) continue
                try {
                    item.nextSibling?.let(pending::addLast)
                    item.firstChild?.let(pending::addLast)
                } catch (_: Exception) { /* Ignore damaged branches. */ }
                try {
                    val label = item.title?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    val page = item.findDestinationPage(document) ?: continue
                    val index = document.pages.indexOf(page)
                    if (index in 0 until document.numberOfPages) result.add(PdfOutlineEntry(label, index))
                } catch (_: Exception) { /* Skip invalid destinations and external actions. */ }
            }
            return result.toList()
        }
    }
}

/** Opens an independent document and releases its file handle before returning. */
fun renderPdfCover(path: Path, maxWidth: Int = 240, maxHeight: Int = 340): BufferedImage =
    PdfSession(path).use { it.render(0, maxWidth, maxHeight) }
