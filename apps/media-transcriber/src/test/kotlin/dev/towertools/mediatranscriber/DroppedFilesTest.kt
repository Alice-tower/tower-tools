package dev.towertools.mediatranscriber

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

class DroppedFilesTest {
    @Test fun parsesWindowsFileUriAsOneFile() {
        val parsed = DroppedFiles.parse(listOf("file:///C:/Media/%E4%B8%AD%E6%96%87%20video.mp4"))
        assertEquals(1, parsed.size)
        assertEquals("中文 video.mp4", parsed.single().fileName.toString())
    }

    @Test fun removesBlankAndDuplicateRepresentations() {
        val path = Paths.get("C:/Media/video.mp4").toAbsolutePath().normalize()
        val parsed = DroppedFiles.parse(listOf("", path.toString(), path.toString().uppercase()))
        assertEquals(listOf(path), parsed)
    }
}
