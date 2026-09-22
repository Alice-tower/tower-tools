package dev.towertools.resourcetagger

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize

/** Internal, source-level contract. No binary compatibility or untrusted-code sandbox. */
interface PreviewPlugin {
    val id: String
    val title: String
    val thumbnail: ThumbnailProvider? get() = null
    val largePreview: LargePreviewProvider? get() = null
    fun tabs(target: PreviewTarget): List<PreviewTab> = emptyList()
    fun actions(target: PreviewTarget): List<PreviewAction> = emptyList()
}

enum class BrowserLayout(val title: String) { List("列表"), Grid("网格"), Gallery("画廊") }
/** Physical pixels. Large fallback requests use the actual large area, not a small cached image. */
data class PreviewRequest(val target: PreviewTarget, val size: IntSize)
fun interface ThumbnailProvider {
    fun load(request: PreviewRequest, host: PreviewHost): ImageBitmap?
}
fun interface LargePreviewProvider {
    @Composable fun present(request: PreviewRequest, host: PreviewHost): PreviewPresentation
}
sealed interface PreviewPresentation {
    data object Loading : PreviewPresentation
    data object Unavailable : PreviewPresentation
    data class Failed(val message: String) : PreviewPresentation
    data class Ready(val content: @Composable (Modifier) -> Unit) : PreviewPresentation
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
    val focusedItem: BrowserItem? = null,
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
