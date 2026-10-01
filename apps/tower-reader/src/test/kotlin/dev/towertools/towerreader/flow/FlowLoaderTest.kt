package dev.towertools.towerreader.flow

import java.nio.file.Files
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class FlowLoaderTest {
    @TempDir lateinit var dir: Path
    @Test fun chineseEncodingAndStableCache() {
        val file = dir.resolve("book.txt"); Files.write(file,"第一章 开始\n中文段落\n第二章 继续\n终".toByteArray(Charset.forName("GB18030")))
        val first = FlowLoader.open(file,dir.resolve("cache")); assertEquals("GB18030",first.encoding); assertEquals(2,first.chapters.size)
        assertTrue(Files.readString(first.chapters[0].htmlPath).contains("中文段落")); assertEquals(first,FlowLoader.open(file,dir.resolve("cache")))
    }
    @Test fun hugeLineIsBoundedAndBomRemoved() {
        val file = dir.resolve("long.txt"); Files.writeString(file,"\uFEFF"+"中文".repeat(100000)); val book = FlowLoader.open(file,dir.resolve("cache"))
        assertEquals("UTF-8",book.encoding); assertTrue(book.chapters.size > 3); assertTrue(book.chapters.all { it.textLength <= 49152 }); assertFalse(Files.readString(book.chapters.first().htmlPath).contains('\uFEFF'))
    }
    private fun epub(extra: Map<String,String> = emptyMap(), fixed: Boolean = false): Path {
        val file = dir.resolve("book.epub")
        val files = linkedMapOf("META-INF/container.xml" to "<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>",
            "OPS/book.opf" to "<package><metadata><dc:title>测试书</dc:title>${if(fixed) "<meta property='rendition:layout'>pre-paginated</meta>" else ""}</metadata><manifest><item id='a' href='a.xhtml'/><item id='b' href='b.xhtml'/><item id='nav' href='nav.xhtml' properties='nav'/></manifest><spine><itemref idref='b'/><itemref idref='a'/></spine></package>",
            "OPS/nav.xhtml" to "<nav><a href='b.xhtml'>第二章</a><a href='a.xhtml'>第一章</a></nav>",
            "OPS/a.xhtml" to "<html><body><p>甲</p></body></html>",
            "OPS/b.xhtml" to "<html><body><p onclick='evil()'>乙<strong>强调</strong><a href='javascript:evil()'>链接</a></p><script>evil()</script><img src='pic.png'/><img src='https://evil/x.png'/><p style='background:url(https://evil)'>结尾</p></body></html>")
        files.putAll(extra)
        ZipOutputStream(Files.newOutputStream(file)).use { zip -> files.forEach { (name,text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }; zip.putNextEntry(ZipEntry("OPS/pic.png")); javax.imageio.ImageIO.write(java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",zip); zip.closeEntry() }
        return file
    }
    @Test fun spineNavigationAndSanitization() {
        val book = FlowLoader.open(epub(),dir.resolve("cache")); assertEquals(listOf("第二章","第一章"),book.chapters.map { it.title }); assertEquals("测试书",book.title); assertEquals(9L,book.totalLength, "Each retained image contributes one stable position unit")
        val html = Files.readString(book.chapters.first().htmlPath); assertTrue(html.contains("<strong>强调</strong>")); assertTrue(html.contains("file:")); assertFalse(html.contains("evil")); assertFalse(html.contains("onclick")); assertEquals(book,FlowLoader.open(dir.resolve("book.epub"),dir.resolve("cache")))
    }
    @Test fun rejectTraversalAndFixedLayout() {
        assertFailsWith<IllegalArgumentException> { FlowLoader.open(epub(mapOf("../escape" to "bad")),dir.resolve("cache")) }
        assertFailsWith<IllegalArgumentException> { FlowLoader.open(epub(fixed=true),dir.resolve("cache")) }
    }
    @Test fun fontObfuscationIsNotDrm() {
        val encryption = "<encryption><EncryptedData><EncryptionMethod Algorithm='http://www.idpf.org/2008/embedding'/></EncryptedData></encryption>"
        assertTrue(FlowLoader.open(epub(mapOf("META-INF/encryption.xml" to encryption)),dir.resolve("cache")).warnings.isNotEmpty())
    }
    @Test fun damagedCacheAndChangedSourceAreRebuilt() {
        val source = dir.resolve("mutable.txt"); Files.writeString(source,"初始内容")
        val first = FlowLoader.open(source,dir.resolve("cache")); Files.writeString(first.chapters[0].htmlPath,"broken")
        val rebuilt = FlowLoader.open(source,dir.resolve("cache")); assertTrue(Files.readString(rebuilt.chapters[0].htmlPath).contains("初始内容"))
        Files.writeString(source,"新的内容已变化"); val changed = FlowLoader.open(source,dir.resolve("cache"))
        assertNotEquals(first.chapters[0].htmlPath,changed.chapters[0].htmlPath); assertTrue(Files.readString(changed.chapters[0].htmlPath).contains("新的内容"))
    }
    @Test fun largeContainerPreservesParagraphsAndImages() {
        val body = "<html><body><div id='book'><h1 id='top'>标题</h1><a href='#top'>回到标题</a>" +
            (0 until 2500).joinToString("") { "<p>段落$it"+"文字".repeat(20)+"</p>" } + "<img src='pic.png'/></div></body></html>"
        val book = FlowLoader.open(epub(mapOf("OPS/b.xhtml" to body)),dir.resolve("cache"))
        val html = book.chapters.joinToString("") { Files.readString(it.htmlPath) }
        assertTrue(book.chapters.size > 2); assertEquals(2501,org.jsoup.Jsoup.parseBodyFragment(html).select("p").size)
        assertTrue(html.contains("file:")); assertTrue(html.contains("href=\"#top\"")); assertTrue(html.contains("id=\"top\""))
        assertEquals(1,org.jsoup.Jsoup.parseBodyFragment(html).select("#book").size)
    }
    @Test fun oversizedImageIsIgnoredBeforeDecodingPixels() {
        // A PNG IHDR announces absurd dimensions while the compressed payload remains tiny.
        val bytes = java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes)
        val image = bytes.toByteArray(); java.nio.ByteBuffer.wrap(image,16,8).putInt(100000).putInt(100000)
        val file = epub(); val replacement = dir.resolve("image.epub")
        java.util.zip.ZipFile(file.toFile()).use { original -> ZipOutputStream(Files.newOutputStream(replacement)).use { zip ->
            original.entries().asSequence().forEach { entry -> zip.putNextEntry(ZipEntry(entry.name)); if(entry.name == "OPS/pic.png") zip.write(image) else original.getInputStream(entry).use { it.copyTo(zip) }; zip.closeEntry() }
        } }
        val book = FlowLoader.open(replacement,dir.resolve("cache")); assertTrue(book.warnings.any { it.contains("图片") }); assertFalse(book.chapters.any { Files.readString(it.htmlPath).contains("<img") })
    }
    @Test fun largeChineseTxtBuildAndCacheReopenMetrics() {
        val source = dir.resolve("large.txt")
        val line = "中文文字用于大文件性能验证。".repeat(32)
        val lines = 24000
        Files.newBufferedWriter(source,Charsets.UTF_8).use { output -> repeat(lines) { output.write(line); output.newLine() } }
        val cache = dir.resolve("large-cache")
        val coldStart = System.nanoTime(); val cold = FlowLoader.open(source,cache); val coldMs = (System.nanoTime()-coldStart)/1_000_000
        assertTrue(Files.size(source)>=30_000_000)
        assertEquals(line.length.toLong()*lines,cold.totalLength)
        assertTrue(cold.chapters.all { it.textLength in 1..65536 }, "Every generated chapter must stay bounded")
        System.gc(); Thread.sleep(150)
        val heapCold = Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()
        val hotStart = System.nanoTime(); val hot = FlowLoader.open(source,cache); val hotMs = (System.nanoTime()-hotStart)/1_000_000
        assertEquals(cold,hot)
        System.gc(); Thread.sleep(150)
        val heapHot = Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()
        val report = """
            source_bytes=${Files.size(source)}
            text_utf16_units=${cold.totalLength}
            chapter_count=${cold.chapters.size}
            max_chapter_utf16_units=${cold.chapters.maxOf { it.textLength }}
            cold_open_ms=$coldMs
            cache_reopen_ms=$hotMs
            jvm_used_heap_after_cold_gc_bytes=$heapCold
            jvm_used_heap_after_reopen_gc_bytes=$heapHot
            jvm_max_heap_bytes=${Runtime.getRuntime().maxMemory()}
            cache_reopen_method=index_and_file_size_metadata_only
            note=Single automated run; timings depend on the machine and are not hours-long endurance evidence.
        """.trimIndent()
        val output = Path.of("build","verification","large-txt.txt"); Files.createDirectories(output.parent); Files.writeString(output,report)
        assertTrue(hotMs < 5000, "Metadata-only cache reopen took ${hotMs}ms; see $output")
    }
    @Test fun longChapterLookingProseDoesNotCreateExtraChapters() {
        val source = dir.resolve("headings.txt")
        val prose = (1..3).joinToString("\n") { chapter -> "第${listOf("一","二","三")[chapter-1]}章 标题\n"+"第${listOf("一","二","三")[chapter-1]}章正文。".repeat(80) }
        Files.writeString(source,prose)
        val book = FlowLoader.open(source,dir.resolve("cache"))
        assertEquals(listOf("第一章 标题","第二章 标题","第三章 标题"),book.chapters.map { it.title })
        assertTrue(Files.readString(book.chapters.last().htmlPath).contains("第三章正文。"))
    }
    @Test fun numberedEnglishAndChineseTitlesAreRecognizedButOrdinaryProseIsNot() {
        val source = dir.resolve("heading-variety.txt")
        Files.writeString(source,"第一百零二章 新的开始\n第一章正文。\nChapter IV The Journey\nChapter discussion is part of this ordinary sentence.\n第１２３卷 附录\n结束")
        val book = FlowLoader.open(source,dir.resolve("cache"))
        assertEquals(listOf("第一百零二章 新的开始","Chapter IV The Journey","第１２３卷 附录"),book.chapters.map { it.title })
    }
    private fun coverOpf() = "<package><metadata><meta name='cover' content='cover-raster'/></metadata><manifest><item id='a' href='a.xhtml'/><item id='b' href='b.xhtml'/><item id='cover-raster' href='pic.png' media-type='image/png' properties='cover-image'/></manifest><spine><itemref idref='b'/><itemref idref='a'/></spine><guide><reference type='cover' href='b.xhtml'/></guide></package>"
    @Test fun hiddenHeadingAndNestedImageCoverBecomeASingleSafeImageInExistingChapter() {
        val body = "<html><body>\n<h2 hidden='hidden' title='封面'></h2>\n<div id='warp1'><div id='center1'><div id='main1'><img src='pic.png' alt='cover'/></div></div></div>\n</body></html>"
        val book = FlowLoader.open(epub(mapOf("OPS/book.opf" to coverOpf(),"OPS/b.xhtml" to body)),dir.resolve("cache"))
        assertEquals(2,book.chapters.size); val cover = org.jsoup.Jsoup.parseBodyFragment(Files.readString(book.chapters.first().htmlPath)).body()
        assertEquals(1,cover.childrenSize()); assertEquals("img",cover.child(0).tagName()); assertTrue(cover.child(0).attr("src").startsWith("file:"))
        assertEquals(1,book.chapters.first().textLength); assertEquals("<p>甲</p>",Files.readString(book.chapters[1].htmlPath))
    }
    @Test fun simpleSvgRasterWrapperBecomesSafeLocalImageForBothHrefForms() {
        listOf("href","xlink:href").forEach { attribute ->
            val svg = "<html><body><svg xmlns='http://www.w3.org/2000/svg' xmlns:xlink='http://www.w3.org/1999/xlink' viewBox='0 0 400 600'><title>封面</title><g><image width='400' height='600' $attribute='pic.png'/></g></svg></body></html>"
            val book = FlowLoader.open(epub(mapOf("OPS/b.xhtml" to svg)),dir.resolve("cache")); val html = Files.readString(book.chapters.first().htmlPath)
            assertTrue(html.contains("<img")); assertTrue(html.contains("file:")); assertFalse(html.contains("<svg")); assertEquals(1,book.chapters.first().textLength)
        }
    }
    @Test fun complexOrExternalSvgDoesNotExecuteOrLoadAnySvgResource() {
        listOf("<svg><image href='https://evil/cover.png'/></svg>","<svg><script>evil()</script><image href='pic.png'/></svg>","<svg><foreignObject><img src='pic.png'/></foreignObject><image href='pic.png'/></svg>").forEach { svg ->
            val book = FlowLoader.open(epub(mapOf("OPS/b.xhtml" to "<html><body><p>普通章节</p>$svg</body></html>")),dir.resolve("cache"))
            val html = Files.readString(book.chapters.first().htmlPath); assertFalse(html.contains("<img")); assertFalse(html.contains("<svg")); assertFalse(html.contains("evil"))
        }
    }
    @Test fun explicitEmptyCoverFallbackNeverReplacesAnOrdinaryEmptyTitlePage() {
        val cover = FlowLoader.open(epub(mapOf("OPS/book.opf" to coverOpf(),"OPS/b.xhtml" to "<html><body><div></div></body></html>")),dir.resolve("cache"))
        assertTrue(Files.readString(cover.chapters.first().htmlPath).contains("<img")); assertEquals(2,cover.chapters.size)
        val ordinaryOpf = coverOpf().replace("<guide><reference type='cover' href='b.xhtml'/></guide>","")
        val ordinary = FlowLoader.open(epub(mapOf("OPS/book.opf" to ordinaryOpf,"OPS/b.xhtml" to "<html><body><div></div></body></html>")),dir.resolve("cache"))
        assertFalse(Files.readString(ordinary.chapters.first().htmlPath).contains("<img"))
    }
}