package dev.towertools.imageprocessor

import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals

class ImageTransformsTest {
    private fun sample(): BufferedImage = BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB).also { image ->
        var value = 1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                image.setRGB(x, y, value++)
            }
        }
    }

    @Test
    fun `clockwise rotation maps corner pixels exactly`() {
        val result = ImageTransforms.rotate(sample(), Rotation.CLOCKWISE_90)
        assertEquals(3, result.width)
        assertEquals(2, result.height)
        assertEquals(5, result.getRGB(0, 0) and 0xFFFFFF)
        assertEquals(1, result.getRGB(2, 0) and 0xFFFFFF)
        assertEquals(6, result.getRGB(0, 1) and 0xFFFFFF)
        assertEquals(2, result.getRGB(2, 1) and 0xFFFFFF)
    }

    @Test
    fun `counterclockwise rotation maps corner pixels exactly`() {
        val result = ImageTransforms.rotate(sample(), Rotation.COUNTERCLOCKWISE_90)
        assertEquals(2, result.getRGB(0, 0) and 0xFFFFFF)
        assertEquals(6, result.getRGB(2, 0) and 0xFFFFFF)
        assertEquals(1, result.getRGB(0, 1) and 0xFFFFFF)
        assertEquals(5, result.getRGB(2, 1) and 0xFFFFFF)
    }

    @Test
    fun `all exif orientations have expected dimensions`() {
        val source = sample()
        for (orientation in 1..8) {
            val result = ImageTransforms.applyExifOrientation(source, orientation)
            if (orientation in setOf(5, 6, 7, 8)) {
                assertEquals(3 to 2, result.width to result.height)
            } else {
                assertEquals(2 to 3, result.width to result.height)
            }
        }
    }

    @Test
    fun `all exif orientations map pixels exactly`() {
        val expected = mapOf(
            1 to listOf(listOf(1, 2), listOf(3, 4), listOf(5, 6)),
            2 to listOf(listOf(2, 1), listOf(4, 3), listOf(6, 5)),
            3 to listOf(listOf(6, 5), listOf(4, 3), listOf(2, 1)),
            4 to listOf(listOf(5, 6), listOf(3, 4), listOf(1, 2)),
            5 to listOf(listOf(1, 3, 5), listOf(2, 4, 6)),
            6 to listOf(listOf(5, 3, 1), listOf(6, 4, 2)),
            7 to listOf(listOf(6, 4, 2), listOf(5, 3, 1)),
            8 to listOf(listOf(2, 4, 6), listOf(1, 3, 5)),
        )
        for (orientation in 1..8) {
            assertEquals(expected.getValue(orientation), values(ImageTransforms.applyExifOrientation(sample(), orientation)))
        }
    }

    private fun values(image: BufferedImage): List<List<Int>> =
        (0 until image.height).map { y ->
            (0 until image.width).map { x -> image.getRGB(x, y) and 0xFFFFFF }
        }
}
