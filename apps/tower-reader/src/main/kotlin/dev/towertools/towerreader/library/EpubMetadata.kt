package dev.towertools.towerreader.library

import java.nio.file.Path
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.imageio.ImageIO
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.w3c.dom.Element

internal object EpubMetadata {
    private const val XML_LIMIT=2*1024*1024
    private const val COVER_LIMIT=10*1024*1024
    private fun safeName(name:String):String {
        require(!name.startsWith('/') && !name.contains('\\') && !name.contains(':')) { "EPUB 含不安全路径" }
        require(name.split('/').none { it==".." }) { "EPUB 路径越界" }
        return name
    }
    private fun xml(bytes:ByteArray):org.w3c.dom.Document {
        val factory=DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware=true
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false)
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"")
        factory.isXIncludeAware=false; factory.isExpandEntityReferences=false
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }
    fun read(path:Path):Metadata = ZipFile(path.toFile()).use { zip ->
        require(zip.size()<=10000) { "EPUB 文件条目过多" }
        var total=0L
        zip.entries().asSequence().forEach { entry ->
            safeName(entry.name); require(entry.size<=128L*1024*1024) { "EPUB 条目过大" }
            if(entry.size>0) total+=entry.size
            require(total<=512L*1024*1024) { "EPUB 解压体积过大" }
        }
        fun readEntry(name:String,limit:Int):ByteArray {
            val entry=zip.getEntry(safeName(name)) ?: error("EPUB 缺少 $name")
            require(entry.size<=limit) { "EPUB 条目过大：$name" }
            return zip.getInputStream(entry).use { input -> input.readNBytes(limit+1).also { require(it.size<=limit) { "EPUB 条目过大：$name" } } }
        }
        val container=xml(readEntry("META-INF/container.xml",XML_LIMIT))
        val nodes=container.getElementsByTagNameNS("*","rootfile")
        require(nodes.length>0) { "EPUB 缺少内容目录" }
        val opfPath=safeName((nodes.item(0) as Element).getAttribute("full-path"))
        val opf=xml(readEntry(opfPath,XML_LIMIT))
        fun text(name:String):String=opf.getElementsByTagNameNS("*",name).item(0)?.textContent?.trim().orEmpty()
        val items=opf.getElementsByTagNameNS("*","item")
        val metas=opf.getElementsByTagNameNS("*","meta")
        var coverId=""
        for(i in 0 until metas.length) { val element=metas.item(i) as Element; if(element.getAttribute("name")=="cover") coverId=element.getAttribute("content") }
        var coverHref=""
        for(i in 0 until items.length) {
            val item=items.item(i) as Element
            if(item.getAttribute("properties").split(' ').contains("cover-image") || (coverId.isNotEmpty() && item.getAttribute("id")==coverId)) coverHref=item.getAttribute("href")
        }
        val cover=if(coverHref.isBlank()) null else try {
            val entryName=resolveEpubResource(opfPath,coverHref) ?: error("EPUB 封面路径无效")
            val bytes=readEntry(entryName,COVER_LIMIT)
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
                val readers=ImageIO.getImageReaders(stream)
                require(readers.hasNext()) { "EPUB 封面格式无法识别" }
                val reader=readers.next()
                try {
                    reader.input=stream
                    val width=reader.getWidth(0); val height=reader.getHeight(0)
                    require(width>0 && height>0 && width.toLong()*height<=25_000_000) { "EPUB 封面尺寸过大" }
                    val image=reader.read(0)
                    val scale=minOf(1.0,320.0/width,480.0/height)
                    val thumb=java.awt.image.BufferedImage((width*scale).toInt().coerceAtLeast(1),(height*scale).toInt().coerceAtLeast(1),java.awt.image.BufferedImage.TYPE_INT_ARGB)
                    thumb.createGraphics().let { g -> try { g.drawImage(image,0,0,thumb.width,thumb.height,null) } finally { g.dispose() } }
                    ByteArrayOutputStream().also { ImageIO.write(thumb,"png",it) }.toByteArray()
                } finally { reader.dispose() }
            }
        } catch (error: Exception) {
            // Cover art is optional; malformed mandatory metadata still fails above.
            dev.towertools.towerreader.AppLog.logger.warning("忽略无法读取的 EPUB 封面：${error.message}")
            null
        }
        Metadata(text("title"),text("creator"),cover)
    }
}
