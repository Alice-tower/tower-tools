package dev.towertools.naibox

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

class ImageParserSamplesTest {
    private fun sampleBytes(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/samples/$name")) { name }.use { it.readAllBytes() }
    private fun sample(name: String): ImageInfo {
        return ImageParser.parse(sampleBytes(name), name)
    }

    @Test fun novelAiV45AndStealth() {
        val full = sample("novelai-v45.png")
        assertEquals("png", full.format)
        assertEquals(4, full.width)
        assertTrue(full.chunks.all { it.crcOk != false })
        assertTrue(full.novelAi.isNai)
        assertEquals("NovelAI Diffusion V4.5", full.novelAi.model)
        assertEquals("4BDE2A96", full.novelAi.hash)
        assertEquals("1234567890", full.novelAi.params?.get("seed").value())
        assertEquals(2, full.novelAi.characters.size)
        assertTrue(full.novelAi.requestBody.orEmpty().contains("v4_negative_prompt"))
        assertEquals(null, full.container)

        val stealth = sample("novelai-stealth.png")
        assertTrue(stealth.novelAi.isNai)
        assertEquals(null, stealth.novelAi.params)
        assertEquals(null, stealth.novelAi.requestBody)
    }

    @Test fun a1111AndPlain() {
        val a = sample("a1111-parameters.png")
        assertFalse(a.novelAi.isNai)
        assertEquals("28", a.a1111?.fields?.get("Steps"))
        assertTrue(a.texts.any { it.chunkType == "iTXt" && it.text.contains("中文注释") })
        val plain = sample("plain.png")
        assertTrue(plain.texts.isEmpty())
        assertTrue(plain.warnings.any { it.contains("未发现文本元数据") })
    }

    @Test fun jpegAndWebp() {
        val jpeg = sample("novelai-comment.jpg")
        assertEquals("jpeg", jpeg.format)
        assertTrue(jpeg.texts.any { it.keyword == "COM" })
        assertTrue(jpeg.texts.any { it.keyword == "APP1/EXIF" && it.entries.any { e -> e.first == "Software" } })
        assertTrue(jpeg.chunks.any { it.type == "DHT" })
        assertFalse(jpeg.chunks.any { it.type == "SOF4" })
        val webp = sample("novelai-exif.webp")
        assertEquals("webp", webp.format)
        assertEquals(832, webp.width)
        assertEquals(1216, webp.height)
        assertTrue(webp.texts.any { it.keyword == "EXIF" })
        assertTrue(webp.warnings.any { it.contains("XMP 标志位") })
        assertFalse(webp.warnings.any { it.contains("EXIF 标志位") })
        val missing = sample("webp-missing-flag.webp")
        assertTrue(missing.warnings.any { it.contains("EXIF 标志位") })
        assertTrue(missing.warnings.any { it.contains("XMP 标志位") })
    }

    @Test fun characterCardsAndContainer() {
        val card = sample("chara-card-v3.png").card
        assertNotNull(card)
        assertEquals("chara_card_v3", card.spec)
        assertEquals(3, card.worldBookEntries)
        val both = sample("card-plus-nai.png")
        assertNotNull(both.card)
        assertTrue(both.novelAi.isNai)
        val container = sample("container-embedded.png").container
        assertNotNull(container)
        assertTrue(container.trailingSize > 0)
        assertEquals(2, container.pngs.count { it.offset > 0 })
        val nested = sample("container-nai-inside.png").container
        assertNotNull(nested)
        assertTrue(nested.pngs.any { it.offset > 0 && it.parsed?.novelAi?.isNai == true })
    }

    @Test fun compressedPngTextAndCrcWarning() {
        val original = sampleBytes("plain.png")
        val text = "压缩的中文元数据"
        val zipped = ByteArrayOutputStream().also { target ->
            DeflaterOutputStream(target).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val payload = "Comment".toByteArray() + byteArrayOf(0, 0) + zipped
        val body = "zTXt".toByteArray() + payload
        val crc = CRC32().apply { update(body) }.value
        val chunk = byteArrayOf(
            (payload.size shr 24).toByte(), (payload.size shr 16).toByte(),
            (payload.size shr 8).toByte(), payload.size.toByte(),
        ) + body + byteArrayOf((crc shr 24).toByte(), (crc shr 16).toByte(), (crc shr 8).toByte(), crc.toByte())
        val iend = original.size - 12
        val modified = original.copyOfRange(0, iend) + chunk + original.copyOfRange(iend, original.size)
        val parsed = ImageParser.parse(modified)
        assertEquals(text, parsed.texts.single().text)
        assertEquals("zTXt", parsed.texts.single().chunkType)
        assertTrue(parsed.chunks.all { it.crcOk != false })
        modified[iend + chunk.size - 1] = (modified[iend + chunk.size - 1].toInt() xor 1).toByte()
        assertTrue(ImageParser.parse(modified).warnings.any { it.contains("CRC") })
        assertFailsWith<IllegalArgumentException> {
            ByteArrayOutputStream().also { target ->
                DeflaterOutputStream(target).use { it.write(ByteArray(2048)) }
            }.toByteArray().inflate(maxBytes = 1024)
        }
    }
}
