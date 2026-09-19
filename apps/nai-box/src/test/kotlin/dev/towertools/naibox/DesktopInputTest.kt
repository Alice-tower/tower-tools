package dev.towertools.naibox

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopInputTest {
    @TempDir lateinit var directory: Path

    @Test fun multipleDroppedFilesChooseFirstImage() {
        val text = Files.writeString(directory.resolve("notes.txt"), "not an image").toFile()
        val firstImage = Files.write(directory.resolve("first.PNG"), byteArrayOf(1)).toFile()
        val secondImage = Files.write(directory.resolve("second.jpg"), byteArrayOf(1)).toFile()
        assertEquals(firstImage, ImageInput.firstImage(listOf(text, firstImage, secondImage)))
        assertNull(ImageInput.firstImage(listOf(text)))
        assertEquals(firstImage, ImageInput.firstImage(ImageInput.droppedFiles(listOf(text.toURI().toString(), firstImage.toURI().toString()))))
    }

    @Test fun previewIsBounded() {
        val source = requireNotNull(javaClass.getResourceAsStream("/samples/novelai-v45.png")).use { it.readAllBytes() }
        val preview = PreviewDecoder.decode(source, maxEdge = 2)
        assertNotNull(preview)
        assertTrue(preview.width <= 2 && preview.height <= 2)
        assertNull(PreviewDecoder.decode(byteArrayOf(1, 2, 3)))
    }
}
