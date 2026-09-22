package dev.towertools.resourcetagger

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Internal, source-level contract. No binary compatibility or untrusted-code sandbox. */
interface PreviewPlugin {
    val id: String
    val title: String
    val browser: PreviewBrowser? get() = null
    fun tabs(target: PreviewTarget): List<PreviewTab> = emptyList()
    fun actions(target: PreviewTarget): List<PreviewAction> = emptyList()
}

fun interface PreviewBrowser {
    @Composable fun Content(context: BrowserContext, host: PreviewHost, modifier: Modifier)
}

data class PreviewTarget(val root: Root, val resource: Resource)
data class PreviewTab(val id: String, val title: String, val content: @Composable (PreviewTarget, PreviewHost) -> Unit)
/** Runs on the preview worker. UI work must be explicitly dispatched to the UI thread. */
data class PreviewAction(val id: String, val title: String, val execute: (PreviewTarget, PreviewHost) -> Unit)

data class BrowserItem(val target: PreviewTarget, val tags: List<String>, val reviews: List<Review>) {
    val resource get() = target.resource
}

data class BrowserContext(
    val items: List<BrowserItem>,
    val selected: Set<String>,
    val focused: String?,
    val selectionEnabled: Boolean,
    val focus: (String) -> Unit,
    val select: (String, Boolean) -> Unit,
    val menu: (Resource) -> List<androidx.compose.foundation.ContextMenuItem>,
)

class PreviewRegistry(plugins: List<PreviewPlugin> = listOf(StandardPreviewPlugin)) {
    val plugins = plugins.toList()
    init {
        require(this.plugins.any { it.id == StandardPreviewPlugin.id }) { "必须保留标准浏览方式。" }
        require(this.plugins.map { it.id }.distinct().size == this.plugins.size) { "预览插件 ID 重复。" }
        this.plugins.forEach { validatePreviewId(it.id); require(it.title.isNotBlank()) }
    }
    fun find(id: String): PreviewPlugin = plugins.find { it.id == id } ?: StandardPreviewPlugin
}

internal fun validatePreviewId(id: String) {
    require(id.matches(Regex("[a-z][a-z0-9-]{0,63}"))) { "无效的预览扩展 ID：$id" }
}

internal fun <T> checkedContributions(items: List<T>, id: (T) -> String): List<T> {
    val ids = items.map(id)
    ids.forEach(::validatePreviewId)
    require(ids.distinct().size == ids.size) { "预览扩展项目 ID 重复。" }
    return items
}
