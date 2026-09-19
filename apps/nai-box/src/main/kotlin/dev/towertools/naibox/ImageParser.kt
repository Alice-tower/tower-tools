package dev.towertools.naibox

import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

object ImageParser {
    private val png = intArrayOf(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    private val sofMarkers = setOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf)

    fun parse(bytes: ByteArray, name: String = "(未命名)", deep: Boolean = true): ImageInfo {
        require(bytes.isNotEmpty()) { "文件为空" }
        val format = when {
            isPng(bytes, 0) -> "png"
            bytes.has(0, 0xff, 0xd8, 0xff) -> "jpeg"
            bytes.has(0, 0x52, 0x49, 0x46, 0x46) && bytes.has(8, 0x57, 0x45, 0x42, 0x50) -> "webp"
            bytes.has(0, 0x47, 0x49, 0x46) -> "gif"
            bytes.has(0, 0x42, 0x4d) -> "bmp"
            bytes.has(4, 0x66, 0x74, 0x79, 0x70) -> "isobmff"
            else -> "unknown"
        }
        val out = ImageInfo(name, bytes.size, format, bytes.hex(0, 16))
        when (format) {
            "png" -> parsePng(bytes, out)
            "jpeg" -> parseJpeg(bytes, out)
            "webp" -> parseWebp(bytes, out)
            "gif" -> if (bytes.size >= 10) { out.width = bytes.le16(6).toLong(); out.height = bytes.le16(8).toLong(); out.warnings += "GIF 注释扩展块暂未解析" }
            "bmp" -> if (bytes.size >= 26) { out.width = bytes.le32(18); out.height = bytes.le32(22); out.warnings += "BMP 规范中没有通用文本元数据块" }
            "isobmff" -> out.warnings += "HEIF/AVIF 容器暂不支持元数据解析"
            else -> out.warnings += "无法识别的图片格式"
        }
        if (deep) out.texts.forEach { t ->
            if (looksLikeBase64(t.text)) {
                val (value, error) = decodeBase64(t.text)
                t.decoded = value; t.decodeError = error
            }
        }
        out.novelAi = SemanticParser.novelAi(out.texts)
        out.a1111 = SemanticParser.a1111(out.texts)
        out.card = SemanticParser.card(out.texts)
        if (format == "png" && deep) out.container = scanContainer(bytes, out)
        if (format in setOf("png", "jpeg", "webp") && out.texts.isEmpty() && out.container?.pngs?.none { it.offset > 0 } != false) {
            out.warnings += "未发现文本元数据；可能生成时未写入，或在转存时被移除"
        }
        if (out.novelAi.isNai) {
            val seed = out.novelAi.params?.get("seed")?.toString()?.toLongOrNull()
            if (seed == 0L) out.warnings += "seed 为 0，表示生成时使用随机种子"
            if (seed != null && seed > 4294967295L) out.warnings += "seed 超出 4294967295，可能无法直接复现"
            if (out.novelAi.params == null) out.warnings += "检测到 NovelAI 来源，但没有可解析的 Comment JSON"
        }
        return out
    }

    private fun isPng(b: ByteArray, at: Int): Boolean = png.indices.all { i -> at + i < b.size && b.u(at + i) == png[i] }

    private fun parsePng(b: ByteArray, out: ImageInfo) {
        var at = 8
        while (at + 12 <= b.size) {
            val n = b.be32(at)
            val type = b.ascii(at + 4, at + 8)
            if (n > Int.MAX_VALUE || n + at + 12 > b.size) { out.warnings += "块 $type 声明长度 $n 超出文件末尾"; break }
            val from = at + 8; val end = from + n.toInt()
            val data = if (type in setOf("IHDR", "tEXt", "zTXt", "iTXt", "eXIf", "iCCP")) b.range(from, end) else byteArrayOf()
            val crc = CRC32().apply { update(b, at + 4, n.toInt() + 4) }.value
            val c = ChunkInfo(type, n, out.chunks.size, at, crcOk = crc == b.be32(end))
            if (c.crcOk == false) out.warnings += "块 $type 的 CRC 校验不一致"
            try {
                when (type) {
                    "IHDR" -> if (data.size >= 13) {
                        out.width = data.be32(0); out.height = data.be32(4)
                        out.header["位深"] = data.u(8).toString()
                        out.header["颜色类型"] = (mapOf(0 to "灰度", 2 to "真彩 RGB", 3 to "索引色", 4 to "灰度+Alpha", 6 to "RGBA")[data.u(9)] ?: "未知")
                        out.header["隔行"] = if (data.u(12) == 1) "Adam7" else "无"
                        c.kind = "header"
                    }
                    "tEXt", "zTXt", "iTXt" -> {
                        val nul = data.indexOf(0)
                        require(nul >= 0) { "缺少关键字分隔符" }
                        val key = data.ascii(0, nul)
                        val (value, enc) = when (type) {
                            "tEXt" -> sniffText(data.range(nul + 1, data.size))
                            "zTXt" -> {
                                require(data.size > nul + 2 && data.u(nul + 1) == 0) { "不支持的压缩方法" }
                                sniffText(data.range(nul + 2, data.size).inflate())
                            }
                            else -> {
                                require(data.size > nul + 3) { "iTXt 头部不完整" }
                                val langEnd = data.findByte(0, nul + 3)
                                val translatedEnd = if (langEnd >= 0) data.findByte(0, langEnd + 1) else -1
                                require(translatedEnd >= 0) { "iTXt 字段不完整" }
                                var raw = data.range(translatedEnd + 1, data.size)
                                if (data.u(nul + 1) == 1) raw = raw.inflate()
                                if (raw.has(0, 0xef, 0xbb, 0xbf)) raw = raw.range(3, raw.size)
                                String(raw, StandardCharsets.UTF_8) to "utf-8"
                            }
                        }
                        c.kind = "text"; c.note = "$key ($enc)"
                        out.texts += TextInfo(key, value, enc, c.index, type)
                    }
                    "eXIf" -> { addExif(data, out, c, "eXIf"); c.kind = "header" }
                    "iCCP" -> c.note = "ICC 色彩配置：${data.ascii(0, data.indexOf(0).coerceAtLeast(0))}"
                    "IDAT" -> { c.kind = "image"; c.note = "像素数据" }
                    "IEND" -> { c.kind = "header"; c.note = "文件结尾" }
                }
            } catch (e: Exception) { c.error = e.message; out.warnings += "块 $type 解析出错：${e.message}" }
            out.chunks += c
            at = end + 4
            if (type == "IEND") break
        }
    }

    private fun addExif(data: ByteArray, out: ImageInfo, chunk: ChunkInfo, type: String) {
        val entries = ExifParser.parse(data)
        chunk.note = "EXIF（${entries.size} 条）"
        out.texts += TextInfo(type, entries.joinToString("\n") { "${it.first}: ${it.second}" }, "exif", chunk.index, type, entries)
    }

    private fun parseJpeg(b: ByteArray, out: ImageInfo) {
        var at = 2
        while (at + 2 <= b.size) {
            if (b.u(at) != 0xff) { at++; continue }
            while (at < b.size && b.u(at) == 0xff) at++
            if (at >= b.size) break
            val marker = b.u(at++)
            if (marker == 0xd8 || marker in 0xd0..0xd7 || marker == 1) continue
            if (marker == 0xd9) { out.chunks += ChunkInfo("EOI", 0, out.chunks.size, at - 2, "header"); break }
            if (at + 2 > b.size) break
            val n = b.be16(at)
            if (n < 2 || at + n > b.size) { out.warnings += "JPEG 段长度超出文件末尾"; break }
            val data = when {
                marker == 0xfe || marker == 0xe1 -> b.range(at + 2, at + n)
                marker in sofMarkers || marker == 0xe0 || marker == 0xe2 -> b.range(at + 2, minOf(at + n, at + 18))
                else -> byteArrayOf()
            }
            val type = when (marker) {
                0xfe -> "COM"
                0xda -> "SOS"
                0xc4 -> "DHT"
                0xdb -> "DQT"
                0xdd -> "DRI"
                0xcc -> "DAC"
                0xdc -> "DNL"
                in 0xe0..0xef -> "APP${marker - 0xe0}"
                in sofMarkers -> "SOF${marker - 0xc0}"
                else -> "0x${marker.toString(16).padStart(2, '0')}"
            }
            val c = ChunkInfo(type, (n - 2).toLong(), out.chunks.size, at - 2)
            try {
                when {
                    marker in sofMarkers && data.size >= 6 -> {
                        out.height = data.be16(1).toLong(); out.width = data.be16(3).toLong()
                        out.header["位深"] = data.u(0).toString(); out.header["通道"] = data.u(5).toString()
                        c.kind = "header"; c.note = "${out.width} × ${out.height}"
                    }
                    marker == 0xfe -> { val (value, enc) = sniffText(data); c.kind = "text"; c.note = "COM 注释 ($enc)"; out.texts += TextInfo("COM", value, enc, c.index, "COM") }
                    marker == 0xe1 && data.has(0, 0x45, 0x78, 0x69, 0x66) -> { addExif(data, out, c, "APP1/EXIF"); c.kind = "header" }
                    marker == 0xe1 && data.has(0, 0x68, 0x74, 0x74, 0x70) -> { c.kind = "text"; c.note = "XMP"; val start = data.indexOf(0); out.texts += TextInfo("APP1/XMP", String(data.range(start + 1, data.size), StandardCharsets.UTF_8), "utf-8", c.index, "APP1") }
                    marker == 0xe2 && data.has(0, 0x49, 0x43, 0x43) -> c.note = "ICC 色彩配置"
                    marker == 0xe0 && data.has(0, 0x4a, 0x46, 0x49, 0x46) -> c.note = "JFIF 头"
                    marker == 0xda -> c.note = "压缩像素数据开始"
                }
            } catch (e: Exception) { c.error = e.message; out.warnings += "段 $type 解析出错：${e.message}" }
            out.chunks += c
            at += n
            if (marker == 0xda) break
        }
        if (out.width == null) out.warnings += "未找到 SOF 段，可能不是完整 JPEG"
    }

    private fun parseWebp(b: ByteArray, out: ImageInfo) {
        val declared = b.le32(4) + 8
        if (declared != b.size.toLong()) out.warnings += "RIFF 声明长度 $declared 与实际文件大小 ${b.size} 不一致"
        var at = 12; var flags: Int? = null
        while (at + 8 <= b.size) {
            val type = b.ascii(at, at + 4)
            val n = b.le32(at + 4)
            if (n > Int.MAX_VALUE || n + at + 8 > b.size) { out.warnings += "WebP 块 $type 长度超出文件末尾"; break }
            val data = when (type) {
                "EXIF", "XMP" -> b.range(at + 8, at + 8 + n.toInt())
                else -> b.range(at + 8, at + 8 + minOf(n.toInt(), 10))
            }
            val c = ChunkInfo(type, n, out.chunks.size, at)
            try {
                when (type) {
                    "VP8X" -> if (data.size >= 10) {
                        flags = data.u(0)
                        out.width = 1L + data.u(4) + (data.u(5) shl 8) + (data.u(6) shl 16)
                        out.height = 1L + data.u(7) + (data.u(8) shl 8) + (data.u(9) shl 16)
                        val rawFlags = data.u(0)
                        out.header["VP8X 标志"] = listOf(0x10 to "ICCP", 0x08 to "Alpha", 0x04 to "EXIF", 0x02 to "XMP", 0x01 to "动画").filter { rawFlags and it.first != 0 }.joinToString("、") { it.second }.ifEmpty { "无" }
                        c.kind = "header"
                    }
                    "VP8" -> { if (data.size >= 10 && data.has(3, 0x9d, 1, 0x2a)) { if (out.width == null) out.width = (data.le16(6) and 0x3fff).toLong(); if (out.height == null) out.height = (data.le16(8) and 0x3fff).toLong() }; c.kind = "image" }
                    "VP8L" -> { if (data.size >= 5 && data.u(0) == 0x2f) { val bits = data.le32(1); if (out.width == null) out.width = (bits and 0x3fff) + 1; if (out.height == null) out.height = ((bits shr 14) and 0x3fff) + 1 }; c.kind = "image" }
                    "EXIF" -> { addExif(data, out, c, "EXIF"); c.kind = "header" }
                    "XMP" -> { c.kind = "text"; c.note = "XMP"; out.texts += TextInfo("XMP", String(data, StandardCharsets.UTF_8), "utf-8", c.index, "XMP") }
                    "ICCP" -> c.note = "ICC 色彩配置"
                    "ALPH", "ANIM", "ANMF" -> c.kind = "image"
                    else -> c.note = "未知扩展块（${data.hex(0, 8)}）"
                }
            } catch (e: Exception) { c.error = e.message; out.warnings += "WebP 块 $type 解析出错：${e.message}" }
            out.chunks += c
            at += 8 + n.toInt() + (n.toInt() and 1)
        }
        val declaredFlags = flags ?: 0
        if (out.chunks.any { it.type == "EXIF" } && declaredFlags and 0x04 == 0) out.warnings += "存在 EXIF 块但缺少 VP8X EXIF 标志位，部分解析器会忽略"
        if (out.chunks.any { it.type == "XMP" } && declaredFlags and 0x02 == 0) out.warnings += "存在 XMP 块但缺少 VP8X XMP 标志位，部分解析器会忽略"
        if (out.width == null) out.warnings += "未能解析出尺寸信息"
    }

    private fun scanContainer(b: ByteArray, out: ImageInfo): ContainerInfo? {
        val iend = out.chunks.lastOrNull { it.type == "IEND" } ?: return null
        val end = iend.head + iend.length.toInt() + 12
        val found = mutableListOf<EmbeddedPng>()
        var at = 0
        val limit = minOf(b.size, 32 * 1024 * 1024)
        while (at + 21 <= limit) {
            if (!isPng(b, at) || b.be32(at + 8) != 13L || b.ascii(at + 12, at + 16) != "IHDR") { at++; continue }
            var walk = at + 8; var count = 0; var declaredEnd = 0
            while (walk + 12 <= b.size) {
                val n = b.be32(walk)
                if (n > Int.MAX_VALUE || n + walk + 12 > b.size) break
                val type = b.ascii(walk + 4, walk + 8)
                count++; walk += n.toInt() + 12
                if (type == "IEND") { declaredEnd = walk; break }
            }
            val embedded = if (at > 0 && declaredEnd > at) runCatching { parse(b.range(at, declaredEnd), "内嵌 @$at", false) } else null
            found += EmbeddedPng(at, declaredEnd, count, embedded?.getOrNull(), embedded?.exceptionOrNull()?.message)
            at = if (declaredEnd > at) declaredEnd else at + 1
        }
        if (end >= b.size && found.none { it.offset > 0 }) return null
        val gaps = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until found.size - 1) if (found[i + 1].offset > found[i].end) gaps += found[i].end to (found[i + 1].offset - found[i].end)
        if (found.isNotEmpty() && b.size > found.last().end) gaps += found.last().end to (b.size - found.last().end)
        val trailing = if (end in 0 until b.size) b.range(end, b.size) else byteArrayOf()
        val signature = when {
            trailing.has(0, *png) -> "PNG"
            trailing.has(0, 0xff, 0xd8, 0xff) -> "JPEG"
            trailing.has(0, 0x52, 0x49, 0x46, 0x46) -> "WebP/RIFF"
            trailing.has(0, 0x47, 0x49, 0x46) -> "GIF"
            trailing.has(0, 0x50, 0x4b) -> "ZIP"
            else -> null
        }
        if (trailing.isNotEmpty()) out.warnings += "PNG 在 IEND 之后还有 ${formatBytes(trailing.size)} 数据，含 ${found.count { it.offset > 0 }} 张内嵌 PNG"
        return ContainerInfo(end, trailing.size, trailing.hex(0, 48), trailing.range(0, 48).ascii(), signature, found, gaps)
    }
}
