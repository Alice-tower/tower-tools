package dev.towertools.imageprocessor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageGeometryTest {
    @Test
    fun `all preset crops stay centered and inside the image`() {
        val sizes = listOf(1920 to 1080, 1080 to 1920, 1001 to 777, 777 to 1001)
        for ((width, height) in sizes) {
            for (preset in cropPresets) {
                val rect = ImageGeometry.cropRect(width, height, preset)
                assertTrue(rect.width in 1..width)
                assertTrue(rect.height in 1..height)
                assertEquals((width - rect.width) / 2, rect.x)
                assertEquals((height - rect.height) / 2, rect.y)
                val expectedRatio = preset.widthRatio / preset.heightRatio
                val widthError = kotlin.math.abs(rect.width - rect.height * expectedRatio)
                val heightError = kotlin.math.abs(rect.height - rect.width / expectedRatio)
                assertTrue(widthError < 1.000001 || heightError < 1.000001)
            }
        }
    }

    @Test
    fun `split rectangles cover every pixel exactly once`() {
        val dimensions = listOf(10 to 11, 101 to 79, 1920 to 1080)
        for ((width, height) in dimensions) {
            for (mode in SplitMode.entries) {
                val rects = ImageGeometry.splitRects(width, height, mode)
                assertEquals(mode.columns * mode.rows, rects.size)
                assertEquals(width.toLong() * height, rects.sumOf { it.width.toLong() * it.height })
                rects.forEach { rect ->
                    assertTrue(rect.x >= 0 && rect.y >= 0)
                    assertTrue(rect.x + rect.width <= width)
                    assertTrue(rect.y + rect.height <= height)
                }
                rects.forEachIndexed { index, first ->
                    rects.drop(index + 1).forEach { second ->
                        val overlapWidth = minOf(first.x + first.width, second.x + second.width) - maxOf(first.x, second.x)
                        val overlapHeight = minOf(first.y + first.height, second.y + second.height) - maxOf(first.y, second.y)
                        assertTrue(overlapWidth <= 0 || overlapHeight <= 0)
                    }
                }
            }
        }
    }

    @Test
    fun `right and bottom pieces receive remainder pixels`() {
        val rects = ImageGeometry.splitRects(10, 10, SplitMode.GRID_3_3)
        assertEquals(listOf(3, 3, 4), rects.take(3).map { it.width })
        assertEquals(listOf(3, 3, 4), rects.filterIndexed { index, _ -> index % 3 == 0 }.map { it.height })
    }

    @Test
    fun `rotation swaps dimensions only for quarter turns`() {
        assertEquals(300 to 200, ImageGeometry.rotatedSize(300, 200, Rotation.ORIGINAL))
        assertEquals(200 to 300, ImageGeometry.rotatedSize(300, 200, Rotation.CLOCKWISE_90))
        assertEquals(300 to 200, ImageGeometry.rotatedSize(300, 200, Rotation.DEGREES_180))
        assertEquals(200 to 300, ImageGeometry.rotatedSize(300, 200, Rotation.COUNTERCLOCKWISE_90))
    }

    @Test
    fun `fine crop keeps its exact pixel rectangle`() {
        val expected = ImageRect(123, 45, 800, 600)
        assertEquals(expected, ImageGeometry.cropRect(1920, 1080, CropMode.Fine(expected)))
    }

    @Test
    fun `fine crop is mapped safely onto a sampled preview`() {
        val mapped = ImageGeometry.previewCropMode(
            CropMode.Fine(ImageRect(1000, 500, 1, 1)),
            fullWidth = 8000,
            fullHeight = 4000,
            previewWidth = 1800,
            previewHeight = 900,
        ) as CropMode.Fine
        assertTrue(mapped.rect.width >= 1)
        assertTrue(mapped.rect.height >= 1)
        assertTrue(mapped.rect.x + mapped.rect.width <= 1800)
        assertTrue(mapped.rect.y + mapped.rect.height <= 900)
    }

    @Test
    fun `fine crop dragging stays inside the source and keeps at least one pixel`() {
        val start = ImageRect(100, 80, 800, 600)
        assertEquals(
            ImageRect(200, 200, 800, 600),
            FineCropGeometry.adjust(start, CropDragHandle.MOVE, 5000, 5000, 1000, 800),
        )
        assertEquals(
            ImageRect(899, 80, 1, 600),
            FineCropGeometry.adjust(start, CropDragHandle.LEFT, 5000, 0, 1000, 800),
        )
        assertEquals(
            ImageRect(100, 80, 900, 720),
            FineCropGeometry.adjust(start, CropDragHandle.BOTTOM_RIGHT, 5000, 5000, 1000, 800),
        )
    }
}
