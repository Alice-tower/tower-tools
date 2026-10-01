package dev.towertools.towerreader.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.test.*

class PdfSessionTest {
    @Test fun readsMetadataOutlineAndRotatedCropBox() = withPdf { path ->
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(200f, 400f))
            doc.addPage(page)
            val rotated = PDPage(PDRectangle(200f, 400f)).apply { rotation = 90 }
            doc.addPage(rotated)
            doc.documentInformation.title = "Sample"
            doc.documentInformation.author = "Reader"
            val outline = PDDocumentOutline()
            doc.documentCatalog.documentOutline = outline
            val chapter = PDOutlineItem().apply { title = "Chapter"; setDestination(page) }
            outline.addLast(chapter)
            chapter.addLast(PDOutlineItem().apply { title = "Rotated"; setDestination(rotated) })
            outline.addLast(PDOutlineItem().apply {
                title = "External"
                action = PDActionURI().apply { uri = "https://example.com" }
            })
            doc.save(path.toFile())
        }
        PdfSession(path).use { session ->
            assertEquals(2, session.pageCount)
            assertEquals("Sample", session.title)
            assertEquals("Reader", session.author)
            assertEquals(listOf(PdfOutlineEntry("Chapter", 0), PdfOutlineEntry("Rotated", 1)), session.outline)
            val portrait = session.render(0, 300, 300)
            assertEquals(150, portrait.width)
            assertEquals(300, portrait.height)
            val landscape = session.render(1, 300, 300)
            assertEquals(300, landscape.width)
            assertEquals(150, landscape.height)
            val zoomed = session.render(0, 300, 300, 3.0)
            assertEquals(450, zoomed.width)
            assertEquals(900, zoomed.height)
            assertSame(portrait, session.render(0, 300, 300))
        }
    }

    @Test fun rendersScannedImageAndCoverReleasesFile() = withPdf { path ->
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(100f, 100f))
            doc.addPage(page)
            val scan = BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)
            val graphics = scan.createGraphics()
            try { graphics.color = Color.RED; graphics.fillRect(0, 0, 10, 10) } finally { graphics.dispose() }
            PDPageContentStream(doc, page).use { stream ->
                stream.drawImage(LosslessFactory.createFromImage(doc, scan), 0f, 0f, 100f, 100f)
            }
            doc.save(path.toFile())
        }
        val cover = renderPdfCover(path, 100, 100)
        assertEquals(Color.RED.rgb, cover.getRGB(50, 50))
        Files.delete(path) // Windows refuses this if PDFBox's handle remains open.
        assertEquals(Color.RED.rgb, cover.getRGB(50, 50))
    }

    @Test fun rejectsBadMissingAndPasswordProtectedFiles() = withPdf { path ->
        Files.writeString(path, "this is not a PDF")
        assertFailsWith<PdfException> { PdfSession(path) }
        Files.delete(path)
        assertFailsWith<PdfException> { PdfSession(path) }
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            doc.protect(StandardProtectionPolicy("owner", "secret", AccessPermission()).apply { encryptionKeyLength = 128 })
            doc.save(path.toFile())
        }
        assertContains(assertFailsWith<PdfException> { PdfSession(path) }.message.orEmpty(), "密码")
    }

    @Test fun boundsCacheAndKeepsDeliveredImagesReadableAfterEvictionAndClose() = withPdf { path ->
        PDDocument().use { doc -> repeat(4) { doc.addPage(PDPage(PDRectangle(100f, 100f))) }; doc.save(path.toFile()) }
        val session = PdfSession(path)
        val delivered = session.render(0, 3000, 3000)
        repeat(4) { page ->
            session.render(page, 3000, 3000)
            assertTrue(session.cachedImageBytes <= PdfSession.CACHE_BYTES)
        }
        assertTrue(session.cachedImageCount < 4)
        assertEquals(Color.WHITE.rgb, delivered.getRGB(100, 100))
        val bounded = session.render(0, Int.MAX_VALUE, Int.MAX_VALUE, 3.0)
        assertTrue(bounded.width.toLong() * bounded.height <= PdfSession.MAX_IMAGE_PIXELS)
        session.close()
        session.close()
        assertEquals(0, session.cachedImageCount)
        assertEquals(0L, session.cachedImageBytes)
        assertEquals(Color.WHITE.rgb, delivered.getRGB(100, 100))
        assertFailsWith<IllegalStateException> { session.render(0, 100, 100) }
    }

    @Test fun validatesArgumentsAndSerializesConcurrentRendering() = withPdf { path ->
        PDDocument().use { doc -> doc.addPage(PDPage(PDRectangle(100f, 100f))); doc.save(path.toFile()) }
        PdfSession(path).use { session ->
            assertFailsWith<IllegalArgumentException> { session.render(-1, 100, 100) }
            assertFailsWith<IllegalArgumentException> { session.render(0, 0, 100) }
            assertFailsWith<IllegalArgumentException> { session.render(0, 100, 100, Double.NaN) }
            val pool = Executors.newFixedThreadPool(4)
            try {
                val futures = (1..8).map { pool.submit<BufferedImage> { session.render(0, 100, 100) } }
                val first = futures.first().get()
                futures.forEach { assertSame(first, it.get()) }
            } finally { pool.shutdownNow() }
        }
    }

    @Test fun opensAndRendersThousandPageSyntheticScanWithinCacheBudget() = withPdf { path ->
        PDDocument().use { doc ->
            val scan = BufferedImage(128, 192, BufferedImage.TYPE_INT_RGB)
            val graphics = scan.createGraphics()
            try {
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, scan.width, scan.height)
                graphics.color = Color.DARK_GRAY
                repeat(16) { graphics.fillRect(12, 14 + it * 10, 100, 2) }
            } finally { graphics.dispose() }
            val sharedScan = LosslessFactory.createFromImage(doc, scan)
            repeat(1000) {
                val page = PDPage(PDRectangle(400f, 600f))
                doc.addPage(page)
                PDPageContentStream(doc, page).use { stream ->
                    stream.drawImage(sharedScan, 0f, 0f, 400f, 600f)
                }
            }
            doc.save(path.toFile())
        }
        val fileBytes = Files.size(path)
        val started = System.nanoTime()
        val session = PdfSession(path)
        val openNanos = System.nanoTime() - started
        var firstRenderNanos = 0L
        var lastRenderNanos = 0L
        var cacheHitNanos = 0L
        var peakBytes = 0L
        var finalBytes = 0L
        try {
            assertEquals(1000, session.pageCount)
            var timed = System.nanoTime()
            val first = session.render(0, 800, 1200)
            firstRenderNanos = System.nanoTime() - timed
            assertEquals(800, first.width)
            assertEquals(1200, first.height)
            timed = System.nanoTime()
            val last = session.render(999, 800, 1200)
            lastRenderNanos = System.nanoTime() - timed
            assertEquals(first.getRGB(100, 100), last.getRGB(100, 100))
            timed = System.nanoTime()
            assertSame(first, session.render(0, 800, 1200))
            cacheHitNanos = System.nanoTime() - timed
            // Jump between the beginning and end, visiting 100 distinct pages.
            // This fills and evicts the LRU rather than exercising just two entries.
            repeat(100) { visit ->
                val page = if (visit % 2 == 0) visit / 2 else 999 - visit / 2
                session.render(page, 800, 1200)
                peakBytes = maxOf(peakBytes, session.cachedImageBytes)
                assertTrue(session.cachedImageBytes <= PdfSession.CACHE_BYTES)
            }
            finalBytes = session.cachedImageBytes
            assertTrue(session.cachedImageCount < 100)
        } finally { session.close() }
        assertEquals(0L, session.cachedImageBytes)
        assertEquals(0, session.cachedImageCount)
        val totalNanos = System.nanoTime() - started
        val report = Path.of("build", "verification", "large-pdf.txt")
        Files.createDirectories(report.parent)
        Files.writeString(report, """
            Fixture: synthetic 1000-page PDF sharing one 128x192 scanned image.
            This fixture is not equivalent to a real complex scan with unique high-resolution pages.
            These measurements cover this test run only; no hour-long reading session was measured.
            pageCount=1000
            fileBytes=$fileBytes
            openDocumentMs=${openNanos / 1_000_000.0}
            coldFirstPageRenderMs=${firstRenderNanos / 1_000_000.0}
            coldLastPageRenderMs=${lastRenderNanos / 1_000_000.0}
            cachedFirstPageRenderMs=${cacheHitNanos / 1_000_000.0}
            alternatingPageAccesses=100
            renderViewport=800x1200
            totalOpenRenderCloseMs=${totalNanos / 1_000_000.0}
            peakCachedImageBytes=$peakBytes
            finalCachedImageBytes=$finalBytes
            cacheLimitBytes=${PdfSession.CACHE_BYTES}
            cachedImageBytesAfterClose=${session.cachedImageBytes}
        """.trimIndent() + System.lineSeparator())
    }
    private fun withPdf(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("tower-reader-pdf-test")
        val path = directory.resolve("book.pdf")
        try { block(path) } finally { Files.deleteIfExists(path); Files.deleteIfExists(directory) }
    }
}
