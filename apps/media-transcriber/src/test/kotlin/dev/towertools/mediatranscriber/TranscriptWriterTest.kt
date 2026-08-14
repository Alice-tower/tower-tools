package dev.towertools.mediatranscriber

import kotlin.test.*
import java.nio.file.Files

class TranscriptWriterTest {
    @Test fun formatsSegmentsCrLfAndUtf8() {
        val segments = listOf(TranscriptSegment(0, " 中文 "), TranscriptSegment(1, "English 日本語 中英 mix"), TranscriptSegment(2, "  "))
        assertEquals("中文\r\nEnglish 日本語 中英 mix\r\n", TranscriptWriter.format(segments, false))
        val file = Files.createTempFile("transcript", ".txt")
        TranscriptWriter.write(file, segments, false)
        assertContentEquals("中文\r\nEnglish 日本語 中英 mix\r\n".toByteArray(Charsets.UTF_8), Files.readAllBytes(file))
        Files.delete(file)
    }

    @Test fun formatsCumulativeMinuteTimestamps() {
        assertEquals("[00:00]", TranscriptWriter.formatTimestamp(0))
        assertEquals("[59:59]", TranscriptWriter.formatTimestamp(3_599_000))
        assertEquals("[60:00]", TranscriptWriter.formatTimestamp(3_600_000))
        assertEquals("[125:08]", TranscriptWriter.formatTimestamp((125*60+8)*1000L))
        assertEquals("[00:00] hello\r\n", TranscriptWriter.format(listOf(TranscriptSegment(0, "hello")), true))
    }
}
