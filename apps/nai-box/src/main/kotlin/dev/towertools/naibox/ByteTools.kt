package dev.towertools.naibox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.InflaterInputStream

internal fun ByteArray.u(at: Int): Int = this[at].toInt() and 255
internal fun ByteArray.be16(at: Int): Int = (u(at) shl 8) or u(at + 1)
internal fun ByteArray.be32(at: Int): Long = ((u(at).toLong() shl 24) or (u(at + 1).toLong() shl 16) or (u(at + 2).toLong() shl 8) or u(at + 3).toLong())
internal fun ByteArray.le16(at: Int): Int = u(at) or (u(at + 1) shl 8)
internal fun ByteArray.le32(at: Int): Long = u(at).toLong() or (u(at + 1).toLong() shl 8) or (u(at + 2).toLong() shl 16) or (u(at + 3).toLong() shl 24)
internal fun ByteArray.has(at: Int, vararg signature: Int): Boolean = at >= 0 && at + signature.size <= size && signature.indices.all { u(at + it) == signature[it] }
internal fun ByteArray.ascii(from: Int = 0, to: Int = size): String = String(this, from, to - from, StandardCharsets.ISO_8859_1).trimEnd('\u0000', ' ')
internal fun ByteArray.hex(from: Int = 0, to: Int = size): String = (from until minOf(to, size)).joinToString(" ") { "%02x".format(u(it)) }
internal fun ByteArray.range(from: Int, to: Int): ByteArray = copyOfRange(from.coerceIn(0, size), to.coerceIn(from.coerceIn(0, size), size))
internal fun ByteArray.findByte(value: Int, from: Int): Int = (from until size).firstOrNull { u(it) == value } ?: -1
internal fun ByteArray.inflate(maxBytes: Int = 32 * 1024 * 1024): ByteArray =
    InflaterInputStream(ByteArrayInputStream(this)).use { input ->
        val output = ByteArrayOutputStream(minOf(maxBytes, maxOf(8192, size.coerceAtMost(maxBytes / 2) * 2)))
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            if (count > maxBytes - total) throw IllegalArgumentException("解压后的文本超过 ${formatBytes(maxBytes)} 上限")
            output.write(chunk, 0, count)
            total += count
        }
        output.toByteArray()
    }

internal fun sniffText(bytes: ByteArray): Pair<String, String> {
    val raw = bytes.dropLastWhile { it == 0.toByte() }.toByteArray()
    if (raw.isEmpty()) return "" to "ascii"
    if (raw.has(0, 0xef, 0xbb, 0xbf)) return String(raw, 3, raw.size - 3, StandardCharsets.UTF_8) to "utf-8"
    if (raw.all { (it.toInt() and 255) < 128 }) return String(raw, StandardCharsets.US_ASCII) to "ascii"
    try {
        val decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(raw)).toString() to "utf-8"
    } catch (_: CharacterCodingException) { }
    fun score(ranges: List<IntRange>): Int {
        var good = 0; var bad = 0; var i = 0
        while (i < raw.size) {
            val b = raw.u(i)
            if (b >= 128) {
                if (i + 1 < raw.size && ranges.any { b in it } && raw.u(i + 1) in 0x40..0xfc && raw.u(i + 1) != 0x7f) { good++; i++ }
                else bad++
            }
            i++
        }
        return good - bad * 2
    }
    val sjis = score(listOf(0x81..0x9f, 0xe0..0xef))
    val gbk = score(listOf(0x81..0xfe))
    val charset = when {
        sjis > gbk && sjis > 0 -> "Shift_JIS"
        gbk > 0 -> "GBK"
        else -> "windows-1252"
    }
    return String(raw, Charset.forName(charset)).trimEnd('\u0000') to charset.lowercase()
}

internal fun parseJson(text: String): JsonElement? = runCatching { Json.parseToJsonElement(text) }.getOrNull()
@OptIn(ExperimentalSerializationApi::class)
internal val prettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }
internal fun JsonElement.pretty(): String = prettyJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), this)

internal fun looksLikeBase64(text: String): Boolean {
    val s = text.filterNot(Char::isWhitespace)
    if (s.length < 96 || s.length % 4 != 0 || !s.matches(Regex("[A-Za-z0-9+/]+={0,2}"))) return false
    return listOf("eyJ", "eyI", "W3si", "WyI", "eNq", "eJx", "eNp", "H4sI", "iVBOR", "/9j/", "UEsDB", "PD94", "PHht").any(s::startsWith)
}

internal fun decodeBase64(text: String): Pair<DecodedPayload?, String?> {
    val s = text.filterNot(Char::isWhitespace).replace(Regex("^data:[\\w.+-]+/[\\w.+-]+;base64,"), "")
    if (s.length.toLong() * 3 / 4 > 32L * 1024 * 1024) return null to "解码后超过 32 MB 上限"
    val bytes = runCatching { Base64.getDecoder().decode(s) }.getOrElse { return null to "不是合法的 base64" }
    var kind = when {
        bytes.has(0, 0x89, 0x50, 0x4e, 0x47) -> "png"
        bytes.has(0, 0xff, 0xd8, 0xff) -> "jpeg"
        bytes.has(0, 0x52, 0x49, 0x46, 0x46) && bytes.has(8, 0x57, 0x45, 0x42, 0x50) -> "webp"
        bytes.has(0, 0x50, 0x4b) -> "zip"
        bytes.has(0, 0x1f, 0x8b) || bytes.has(0, 0x78, 0x9c) || bytes.has(0, 0x78, 0xda) -> "compressed"
        else -> "binary"
    }
    val previewText = String(bytes.range(0, minOf(bytes.size, 4 * 1024 * 1024)), StandardCharsets.UTF_8)
    val json = if (previewText.trimStart().startsWith('{') || previewText.trimStart().startsWith('[')) parseJson(previewText) else null
    if (json != null) kind = "json"
    else if (kind == "binary" && runCatching { StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.range(0, 65536))) }.isSuccess) kind = "text"
    return DecodedPayload(bytes, kind, json, if (kind == "text") previewText.take(4000) else bytes.hex(0, 32)) to null
}

internal fun formatBytes(n: Int): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
    else -> "%.2f MB".format(n / 1048576.0)
}
