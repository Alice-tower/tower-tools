package dev.towertools.mediatranscriber

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

object TranscriptWriter {
    fun format(segments: List<TranscriptSegment>, includeTimestamps: Boolean): String = segments
        .mapNotNull { segment ->
            val text = segment.text.trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
            if (includeTimestamps) "${formatTimestamp(segment.startMilliseconds)} $text" else text
        }
        .joinToString(separator = "\r\n", postfix = if (segments.any { it.text.isNotBlank() }) "\r\n" else "")

    fun write(path: Path, segments: List<TranscriptSegment>, includeTimestamps: Boolean) {
        Files.write(path, format(segments, includeTimestamps).toByteArray(StandardCharsets.UTF_8))
    }

    fun formatTimestamp(milliseconds: Long): String {
        val totalSeconds = milliseconds.coerceAtLeast(0) / 1000
        return "[%02d:%02d]".format(totalSeconds / 60, totalSeconds % 60)
    }
}
