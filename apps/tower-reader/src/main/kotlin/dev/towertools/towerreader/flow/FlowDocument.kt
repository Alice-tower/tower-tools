package dev.towertools.towerreader.flow
import java.nio.file.Path
data class FlowChapter(val id: String, val title: String, val htmlPath: Path, val textLength: Int)
data class FlowDocument(val chapters: List<FlowChapter>, val title: String? = null, val author: String? = null, val encoding: String? = null, val warnings: List<String> = emptyList()) { val totalLength: Long get() = chapters.sumOf { it.textLength.toLong() } }
