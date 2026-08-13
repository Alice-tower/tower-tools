package dev.towertools.imageprocessor

import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals

class DroppedFilesTest {
    @Test
    fun `file uri from compose desktop becomes a path`() {
        val file = createTempFile("image-drop-", ".png").toAbsolutePath().normalize()
        assertEquals(listOf(file), DroppedFiles.parse(listOf(file.toUri().toString())))
    }

    @Test
    fun `plain windows path is also accepted`() {
        val file = createTempFile("image-drop-", ".jpg").toAbsolutePath().normalize()
        assertEquals(listOf(file), DroppedFiles.parse(listOf(file.toString())))
    }

    @Test
    fun `invalid values are ignored`() {
        assertEquals(emptyList(), DroppedFiles.parse(listOf("file://[invalid")))
    }
}
