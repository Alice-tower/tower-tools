package dev.towertools.naibox

import java.nio.charset.StandardCharsets

internal object ExifParser {
    private val names = mapOf(
        0x0100 to "ImageWidth", 0x0101 to "ImageLength", 0x010e to "ImageDescription", 0x010f to "Make",
        0x0110 to "Model", 0x0112 to "Orientation", 0x011a to "XResolution", 0x011b to "YResolution",
        0x0131 to "Software", 0x0132 to "DateTime", 0x013b to "Artist", 0x8298 to "Copyright",
        0x829a to "ExposureTime", 0x829d to "FNumber", 0x8827 to "ISOSpeedRatings",
        0x9003 to "DateTimeOriginal", 0x9004 to "DateTimeDigitized", 0x9201 to "ShutterSpeedValue",
        0x9202 to "ApertureValue", 0x9209 to "Flash", 0x9286 to "UserComment",
        0xa002 to "PixelXDimension", 0xa003 to "PixelYDimension", 0xa430 to "CameraOwnerName",
        0xa431 to "BodySerialNumber", 0xa432 to "LensSpecification", 0xa433 to "LensMake",
        0xa434 to "LensModel", 0x011c to "PlanarConfiguration", 0x0213 to "YCbCrPositioning",
    )
    private val sizes = mapOf(1 to 1, 2 to 1, 3 to 2, 4 to 4, 5 to 8, 6 to 1, 7 to 1, 8 to 2, 9 to 4, 10 to 8, 11 to 4, 12 to 8)

    fun parse(source: ByteArray): List<Pair<String, String>> {
        val b = if (source.has(0, 0x45, 0x78, 0x69, 0x66, 0, 0)) source.range(6, source.size) else source
        if (b.size < 8) return emptyList()
        val little = b.has(0, 0x49, 0x49)
        if (!little && !b.has(0, 0x4d, 0x4d)) return emptyList()
        fun u16(at: Int): Int = if (little) b.le16(at) else b.be16(at)
        fun u32(at: Int): Long = if (little) b.le32(at) else b.be32(at)
        if (u16(2) != 42) return emptyList()
        val result = mutableListOf<Pair<String, String>>()
        val visited = mutableSetOf<Long>()
        var ifd = u32(4)
        repeat(8) {
            if (ifd == 0L || ifd !in 0 until (b.size - 1).toLong() || !visited.add(ifd)) return result
            val at = ifd.toInt()
            val count = u16(at).coerceAtMost(4096)
            repeat(count) { index ->
                val entry = at + 2 + index * 12
                if (entry + 12 > b.size) return@repeat
                val tag = u16(entry)
                val type = u16(entry + 2)
                val n = u32(entry + 4)
                val size = (sizes[type] ?: 1).toLong() * n
                if (size > b.size || size < 0) return@repeat
                val valueAt = if (size <= 4) entry else u32(entry + 8).toInt()
                if (valueAt < 0 || valueAt.toLong() + size > b.size) return@repeat
                val slice = b.range(valueAt, valueAt + size.toInt())
                val display = when (type) {
                    2 -> String(slice, StandardCharsets.ISO_8859_1).trimEnd('\u0000').trim()
                    7 -> {
                        val prefix = slice.range(0, 8).ascii().trimEnd('\u0000')
                        if (prefix == "ASCII") slice.range(8, slice.size).ascii()
                        else if (prefix == "UNICODE") String(slice.range(8, slice.size), StandardCharsets.UTF_8).trimEnd('\u0000')
                        else slice.hex(0, 24)
                    }
                    1, 6 -> slice.take(16).joinToString(", ") { (it.toInt() and 255).toString() }
                    3, 8 -> (0 until minOf(n.toInt(), 16)).joinToString(", ") { u16(valueAt + it * 2).toString() }
                    4, 9 -> (0 until minOf(n.toInt(), 16)).joinToString(", ") { u32(valueAt + it * 4).toString() }
                    5, 10 -> (0 until minOf(n.toInt(), 16)).joinToString(", ") {
                        val num = u32(valueAt + it * 8); val den = u32(valueAt + it * 8 + 4)
                        if (den == 0L) "$num/0" else (num.toDouble() / den).toString()
                    }
                    else -> "($n × type $type) ${slice.hex(0, 24)}"
                }
                result += (names[tag] ?: "未知标签 $tag") to display
            }
            val next = at.toLong() + 2 + count.toLong() * 12
            if (next + 4 > b.size) return result
            ifd = u32(next.toInt())
        }
        return result
    }
}
