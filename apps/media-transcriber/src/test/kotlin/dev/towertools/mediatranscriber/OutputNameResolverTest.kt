package dev.towertools.mediatranscriber

import kotlin.test.Test
import kotlin.test.assertEquals
import java.nio.file.Files

class OutputNameResolverTest {
    @Test fun pureCollisionResolverHandlesCaseInsensitiveWindowsNames() {
        assertEquals("video (3).mp3", OutputNameResolver.resolveName("video.mp4", "mp3", setOf("VIDEO.MP3", "video (2).mp3")))
        assertEquals("video.txt", OutputNameResolver.resolveName("video.mp4", "txt", setOf("video.mp3")))
    }
    @Test fun resolvesIndependentCollisionsAndUnicodeNames() {
        val dir = Files.createTempDirectory("output names 中文")
        try {
            val input = dir.resolve("采访.final.v2.mp4")
            assertEquals("采访.final.v2.mp3", OutputNameResolver.resolve(dir, input, "mp3").fileName.toString())
            Files.createFile(dir.resolve("采访.final.v2.mp3")); Files.createFile(dir.resolve("采访.final.v2 (2).mp3"))
            assertEquals("采访.final.v2 (3).mp3", OutputNameResolver.resolve(dir, input, ".mp3").fileName.toString())
            assertEquals("采访.final.v2.txt", OutputNameResolver.resolve(dir, input, "txt").fileName.toString())
        } finally { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
