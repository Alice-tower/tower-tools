package dev.towertools.towerreader.flow

import dev.towertools.towerreader.library.resolveEpubResource
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

object FlowLoader {
    private const val BLOCK = 49152
    private const val MAX_ENTRY = 16 * 1024 * 1024
    private val chapterHeading = Regex("(?:第[零〇一二三四五六七八九十百千万两0-9０-９]{1,16}[章节卷部篇](?:\\s*[^。！？!?；;]*)?|(?i:chapter)\\s+[0-9ivxlcdmIVXLCDM]{1,16}(?:\\s+[^。！？!?；;]*)?)")
    fun open(path: Path, cacheDirectory: Path, encodingOverride: String? = null): FlowDocument {
        val source = path.toRealPath()
        val identity = "flow-v8:${source}:${Files.size(source)}:${Files.getLastModifiedTime(source).toMillis()}:${encodingOverride ?: "auto"}"
        val dir = cacheDirectory.resolve(digest(identity.toByteArray(Charsets.UTF_8))); Files.createDirectories(dir)
        runCatching {
            val p = Properties(); Files.newInputStream(dir.resolve("index.properties")).use { p.load(it) }
            require(p.getProperty("source") == identity); val imageCount = p.getProperty("images").toInt(); require(imageCount in 0..20000); repeat(imageCount) { i -> val imageName = p.getProperty("image.$i.name"); require(imageName.matches(Regex("image-[0-9]+\\.(png|jpg|jpeg|gif|webp)"))); require(Files.size(dir.resolve(imageName)) == p.getProperty("image.$i.size").toLong()) }; val count = p.getProperty("count").toInt(); require(count in 1..100000)
            val chapters = (0 until count).map { i -> val f = dir.resolve("chapter-$i.html"); require(Files.isRegularFile(f)); require(Files.size(f) == p.getProperty("$i.size").toLong()); FlowChapter("$i", p.getProperty("$i.title"), f, p.getProperty("$i.length").toInt()) }
            FlowDocument(chapters, p.getProperty("title"), p.getProperty("author"), p.getProperty("encoding"), p.getProperty("warnings", "").split('\n').filter { it.isNotBlank() })
        }.getOrNull()?.let { return it }
        val doc = when(path.fileName.toString().substringAfterLast('.').lowercase()) { "txt" -> txt(path, dir, encodingOverride); "epub" -> epub(path, dir); else -> error("仅支持 TXT 和 EPUB 文字书籍") }
        val p = Properties(); p["source"] = identity; p["count"] = doc.chapters.size.toString(); doc.title?.let { p["title"] = it }; doc.author?.let { p["author"] = it }; doc.encoding?.let { p["encoding"] = it }; p["warnings"] = doc.warnings.joinToString("\n")
        doc.chapters.forEachIndexed { i,c -> p["$i.title"] = c.title; p["$i.length"] = c.textLength.toString(); p["$i.size"] = Files.size(c.htmlPath).toString() }
        val images = Files.list(dir).use { stream -> stream.filter { it.fileName.toString().matches(Regex("image-[0-9]+\\.(png|jpg|jpeg|gif|webp)")) }.sorted().toList() }; p["images"] = images.size.toString(); images.forEachIndexed { i, image -> p["image.$i.name"] = image.fileName.toString(); p["image.$i.size"] = Files.size(image).toString() }
        Files.newOutputStream(dir.resolve("index.properties")).use { p.store(it, "Derived cache") }; return doc
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun textLength(node: Node): Int { val pending = java.util.ArrayDeque<Node>(); pending.add(node); var length = 0; while (pending.isNotEmpty()) { val current = pending.removeLast(); if (current is TextNode) length += current.wholeText.length else { if(current is Element && current.tagName() == "img") length++; pending.addAll(current.childNodes()) } }; return length }
    private fun textBlocks(text: String, size: Int = BLOCK): Sequence<String> = sequence { var start = 0; while (start < text.length) { var end = minOf(start + maxOf(2,size), text.length); if (end < text.length && text[end - 1].isHighSurrogate()) end--; yield(text.substring(start, end)); start = end } }
    private fun safeImage(bytes: ByteArray): Boolean = runCatching {
        javax.imageio.stream.MemoryCacheImageInputStream(bytes.inputStream()).use { input ->
            val readers = javax.imageio.ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return@use false
            val reader = readers.next()
            try { reader.input = input; val width = reader.getWidth(0); val height = reader.getHeight(0)
                width in 1..8192 && height in 1..8192 && width.toLong() * height <= 16_000_000 &&
                    (!reader.formatName.equals("gif", true) || reader.getNumImages(true) <= 1)
            } finally { reader.dispose() }
        }
    }.getOrDefault(false)
    private fun fragments(node: Node, limit: Int = BLOCK * 2, depth: Int = 0): List<String> {
        require(depth <= 64) { "EPUB 章节嵌套过深，无法安全阅读" }
        val complete = node.outerHtml()
        if (complete.length <= limit) return listOf(complete)
        if (node is TextNode) return textBlocks(node.wholeText, maxOf(2, limit / 6)).map(::escaped).toList()
        if (node !is Element || node.childNodeSize() == 0) return listOf(complete)
        val shell = node.clone().empty()
        val outer = shell.outerHtml(); val closing = outer.lastIndexOf("</")
        if (closing < 0) return listOf(complete)
        var prefix = outer.substring(0, closing); val suffix = outer.substring(closing)
        val result = mutableListOf<String>(); val content = StringBuilder()
        fun flush() { if (content.isNotEmpty()) { result += prefix + content + suffix; content.setLength(0)
            shell.removeAttr("id"); prefix = shell.outerHtml().substringBeforeLast("</")
        } }
        node.childNodes().forEach { child -> fragments(child, limit - prefix.length - suffix.length, depth + 1).forEach { fragment ->
            if (content.length + fragment.length + prefix.length + suffix.length > limit) flush()
            content.append(fragment)
        } }
        flush(); return result
    }
    private fun escaped(s: String) = org.jsoup.nodes.Entities.escape(s)
    private fun write(dir: Path, chapters: MutableList<FlowChapter>, title: String, html: String) {
        val id = chapters.size.toString(); val file = dir.resolve("chapter-$id.html"); Files.writeString(file, html)
        chapters += FlowChapter(id, title, file, textLength(Jsoup.parseBodyFragment(html).body()))
    }
    private fun txt(path: Path, dir: Path, override: String?): FlowDocument {
        val prefix = Files.newInputStream(path).use { it.readNBytes(4) }
        val bom = when { prefix.size >= 3 && prefix[0] == 0xef.toByte() && prefix[1] == 0xbb.toByte() && prefix[2] == 0xbf.toByte() -> "UTF-8" to 3
            prefix.size >= 2 && prefix[0] == 0xff.toByte() && prefix[1] == 0xfe.toByte() -> "UTF-16LE" to 2
            prefix.size >= 2 && prefix[0] == 0xfe.toByte() && prefix[1] == 0xff.toByte() -> "UTF-16BE" to 2; else -> null }
        val encoding = override ?: bom?.first ?: if (runCatching { Files.newBufferedReader(path, Charsets.UTF_8).use { r -> val b = CharArray(65536); while(r.read(b) >= 0) {} } }.isSuccess) "UTF-8" else "GB18030"
        val charset = runCatching { Charset.forName(encoding) }.getOrElse { error("不支持的文字编码：$encoding") }
        val chapters = mutableListOf<FlowChapter>(); val block = StringBuilder(); var title = "正文"
        fun flush() { if(block.isNotEmpty()) { write(dir, chapters, title, block.toString()); block.setLength(0) } }
        Files.newInputStream(path).use { input -> if(bom != null && (override == null || charset.name().equals(bom.first,true))) input.skipNBytes(bom.second.toLong())
            input.reader(charset).buffered().use { reader ->
                val line = StringBuilder()
                fun emit() { val value = line.toString(); line.setLength(0); val heading = value.trim(); if(heading.length in 2..80 && chapterHeading.matches(heading)) { flush(); title = heading }; block.append("<p>").append(escaped(value)).append("</p>"); if(block.length >= BLOCK) flush() }
                while(true) { val c = reader.read(); if(c < 0) break; if(c == 10) emit() else if(c != 13) { line.append(c.toChar()); if(line.length >= BLOCK && !line.last().isHighSurrogate()) emit() } }; if(line.isNotEmpty()) emit(); flush()
            }
        }
        if(chapters.isEmpty()) write(dir,chapters,"正文","<p></p>")
        return FlowDocument(chapters, path.fileName.toString().substringBeforeLast('.'), encoding = charset.name())
    }
    private fun epub(path: Path, dir: Path): FlowDocument = ZipFile(path.toFile()).use { zip ->
        val entries = zip.entries().asSequence().toList(); require(entries.size <= 20000) { "EPUB 文件条目过多" }; var total = 0L
        entries.forEach { e -> require(!e.name.contains('\\') && !e.name.startsWith('/') && !e.name.split('/').contains("..") && !e.name.contains(':')) { "EPUB 含有不安全的文件路径" }; require(e.size in 0..MAX_ENTRY.toLong()) { "EPUB 文件过大或大小未知" }; total += e.size; require(total <= 256L*1024*1024) { "EPUB 解压后过大" } }
        fun bytes(name: String): ByteArray { val e = zip.getEntry(name) ?: error("EPUB 缺少文件：$name"); return zip.getInputStream(e).use { it.readNBytes(MAX_ENTRY+1).also { b -> require(b.size <= MAX_ENTRY) { "EPUB 文件过大" } } } }
        fun xml(name: String) = Jsoup.parse(String(bytes(name),Charsets.UTF_8),"",Parser.xmlParser())
        fun resolve(base: String, href: String) = resolveEpubResource(base, href)
        val opfName = xml("META-INF/container.xml").selectFirst("rootfile")?.attr("full-path") ?: error("EPUB 容器信息无效")
        val opf = xml(opfName); require(opf.select("meta").none { it.attr("property") == "rendition:layout" && it.text() == "pre-paginated" }) { "暂不支持固定版式 EPUB，请使用 PDF 或流式 EPUB" }
        val warnings = mutableListOf<String>()
        zip.getEntry("META-INF/encryption.xml")?.let { e -> val methods = xml(e.name).select("EncryptionMethod").map { it.attr("Algorithm") }; require(methods.isNotEmpty() && methods.all { it == "http://www.idpf.org/2008/embedding" || it == "http://ns.adobe.com/pdf/enc#RC" }) { "此 EPUB 受 DRM 加密保护，无法离线阅读" }; warnings += "书内混淆字体已忽略，使用阅读器字体" }
        val manifest = opf.select("manifest > item").associateBy { it.attr("id") }; val titles = mutableMapOf<String,String>()
        manifest.values.filter { it.attr("properties").split(' ').contains("nav") || it.attr("media-type").contains("ncx") }.forEach { item -> resolve(opfName,item.attr("href"))?.let { name -> val nav = xml(name); nav.select("a[href]").forEach { a -> resolve(name,a.attr("href"))?.let { titles.putIfAbsent(it,a.text()) } }; nav.select("navPoint").forEach { n -> n.selectFirst("content[src]")?.let { c -> resolve(name,c.attr("src"))?.let { titles.putIfAbsent(it,n.selectFirst("navLabel")?.text().orEmpty()) } } } } }
        val chapters = mutableListOf<FlowChapter>(); val images = mutableMapOf<String,String>()
        val guideCovers = opf.select("guide > reference").filter { it.attr("type").split(' ').contains("cover") }.mapNotNull { resolve(opfName,it.attr("href")) }.toSet()
        val coverImageId = opf.select("meta[name=cover]").firstOrNull()?.attr("content")
        val coverImageItem = manifest.values.firstOrNull { it.attr("properties").split(' ').contains("cover-image") } ?: coverImageId?.let(manifest::get)
        val coverResource = coverImageItem?.let { resolve(opfName,it.attr("href")) }
        fun imageUri(resource: String): String {
            val suffix = resource.substringAfterLast('.').lowercase()
            if(suffix !in setOf("png","jpg","jpeg","gif","webp") || zip.getEntry(resource) == null) return ""
            return images.getOrPut(resource) {
                val out = dir.resolve("image-${images.size}.$suffix"); val imageBytes = bytes(resource)
                if(safeImage(imageBytes)) { Files.write(out,imageBytes); out.toUri().toASCIIString() }
                else { warnings += "已忽略损坏、动画、超大或不支持的图片：$resource"; "" }
            }
        }
        val allowed = setOf("p","div","span","h1","h2","h3","h4","h5","h6","br","hr","img","strong","em","b","i","u","s","blockquote","ul","ol","li","table","thead","tbody","tfoot","tr","td","th","sup","sub","pre","code","a")
        opf.select("spine > itemref").forEach { ref ->
            val item = manifest[ref.attr("idref")] ?: error("EPUB 阅读顺序无效"); require(!item.attr("properties").contains("rendition:layout-pre-paginated") && !ref.attr("properties").contains("rendition:layout-pre-paginated")) { "暂不支持固定版式 EPUB" }
            val name = resolve(opfName,item.attr("href")) ?: error("EPUB 章节路径无效"); val raw = bytes(name); require(raw.size <= 4*1024*1024) { "EPUB 单章超过 4 MB，请先拆分章节" }
            val body = Jsoup.parse(raw.inputStream(),null,"").body()
            val coverChapter = name in guideCovers || item.attr("properties").split(' ').contains("cover") ||
                name.substringAfterLast('/').substringBeforeLast('.').equals("cover",true) ||
                body.getAllElements().any { it.attr("epub:type").split(' ').contains("cover") }
            // SVG is never rendered or executed. Only its simplest local raster image wrapper is translated.
            body.select("svg").toList().forEach { svg ->
                val descendants = svg.getAllElements()
                val raster = svg.select("image").singleOrNull()
                val simple = raster != null && descendants.all { e -> e.tagName().lowercase() in setOf("svg","g","image","title","desc") &&
                    e.attributes().none { it.key.lowercase().startsWith("on") } }
                val href = raster?.attr("href")?.takeIf { it.isNotBlank() } ?: raster?.attr("xlink:href").orEmpty()
                val resource = if(simple) resolve(name,href) else null
                if(resource != null && imageUri(resource).isNotEmpty()) {
                    val image = Element("img").attr("src",href).attr("alt",svg.selectFirst("title")?.text().orEmpty())
                    svg.attr("id").takeIf { it.isNotBlank() }?.let { image.attr("id",it) }
                    svg.replaceWith(image)
                } else svg.remove()
            }
            body.select("script,style,iframe,object,embed,link,meta,svg,math,form,input,button,audio,video").remove()
            body.getAllElements().toList().drop(1).forEach element@ { e ->
                if(e.tagName() !in allowed) { e.unwrap(); return@element }; val src = e.attr("src"); val alt = e.attr("alt"); val style = e.attr("style"); val elementId = e.attr("id"); val href = e.attr("href"); val hidden = e.hasAttr("hidden"); e.clearAttributes(); if(hidden) e.attr("hidden",""); if(elementId.isNotBlank()) e.attr("id",elementId.take(512)); if(e.tagName() == "a" && href.isNotBlank()) { val target = resolve(name,href); if(target != null) { e.attr("data-epub-target",target); if(href.contains('#')) { val fragment = href.substringAfter('#').take(512); e.attr("data-epub-fragment",fragment); if(target == name) e.attr("href","#$fragment") } } }
                if(e.tagName() == "img") { val resource = resolve(name,src); val uri = resource?.let(::imageUri).orEmpty(); if(uri.isEmpty()) e.remove() else e.attr("src",uri).attr("alt",alt) }
                val safe = style.split(';').mapNotNull { d -> val pair = d.split(':',limit=2); if(pair.size != 2) null else { val k = pair[0].trim().lowercase(); val v = pair[1].trim().lowercase(); if((k == "text-align" && v in setOf("left","right","center","justify")) || (k == "font-style" && v in setOf("normal","italic")) || (k == "font-weight" && v in setOf("normal","bold","400","700"))) "$k:$v" else null } }.joinToString(";"); if(safe.isNotEmpty()) e.attr("style",safe)
            }
            if(coverChapter && body.text().isBlank()) {
                // Normalize the existing cover chapter, never insert a new spine entry or replace a title page.
                if(body.select("img").isEmpty() && coverResource != null) {
                    val uri = imageUri(coverResource)
                    if(uri.isNotEmpty()) body.appendChild(Element("img").attr("src",uri).attr("alt","封面"))
                }
                val cover = body.select("img").singleOrNull()
                if(cover != null) { val image = cover.clone(); body.empty().appendChild(image) }
            }
            body.ownerDocument()!!.outputSettings().prettyPrint(false); val title = titles[name]?.takeIf { it.isNotBlank() } ?: body.selectFirst("h1,h2,h3")?.text()?.take(120) ?: "第 ${chapters.size+1} 章"
            var html = StringBuilder(); fun flush() { if(html.isNotEmpty()) { write(dir,chapters,title,html.toString()); html = StringBuilder() } }
            body.childNodes().forEach { node -> fragments(node).forEach { fragment -> if(html.length+fragment.length > BLOCK*2) flush(); html.append(fragment) } }; flush()
        }
        require(chapters.isNotEmpty()) { "EPUB 没有可阅读的章节" }; FlowDocument(chapters,opf.getElementsByTag("dc:title").first()?.text(),opf.getElementsByTag("dc:creator").first()?.text(),warnings=warnings.distinct())
    }
}
