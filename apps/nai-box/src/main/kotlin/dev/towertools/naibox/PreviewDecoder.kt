package dev.towertools.naibox

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

internal object PreviewDecoder {
    private const val maxPixels = 250_000_000L

    fun decode(bytes: ByteArray, maxEdge: Int = 1280): BufferedImage? {
        require(maxEdge > 0)
        MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            try {
                reader.setInput(input, true, true)
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width <= 0 || height <= 0 || width.toLong() * height > maxPixels) return null
                val sample = ((maxOf(width, height).toLong() + maxEdge - 1) / maxEdge).toInt().coerceAtLeast(1)
                val params = reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }
                val decoded = reader.read(0, params) ?: return null
                if (maxOf(decoded.width, decoded.height) <= maxEdge) return decoded

                val scale = maxEdge.toDouble() / maxOf(decoded.width, decoded.height)
                val scaled = BufferedImage((decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
                val graphics = scaled.createGraphics()
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    graphics.drawImage(decoded, 0, 0, scaled.width, scaled.height, null)
                } finally { graphics.dispose() }
                return scaled
            } finally { reader.dispose() }
        }
    }
}
