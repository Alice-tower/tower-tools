package dev.towertools.resourcetagger

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.Image
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag

object StandardPreviewPlugin : PreviewPlugin {
    override val id = "standard"
    override val title = "标准"
}

@Composable fun ResourceBrowser(plugin: PreviewPlugin, context: BrowserContext, host: PreviewHost, modifier: Modifier = Modifier, layout: BrowserLayout = BrowserLayout.List) {
    key(plugin.id, layout) {
        when (layout) {
            BrowserLayout.List -> LazyColumn(modifier.testTag("resource-list"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(context.items, key = { it.resource.id }) { item ->
                    ResourceItemFrame(item, context) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PreviewArea(plugin, item.target, host, false, Modifier.size(48.dp))
                            Column { StandardResourceContent(item) }
                        }
                    }
                }
            }
            BrowserLayout.Grid -> LazyVerticalGrid(GridCells.Adaptive(190.dp), modifier.testTag("resource-grid"), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(context.items, key = { it.resource.id }) { item ->
                    ResourceItemFrame(item, context) {
                        PreviewArea(plugin, item.target, host, false, Modifier.fillMaxWidth().height(150.dp))
                        StandardResourceContent(item)
                    }
                }
            }
            BrowserLayout.Gallery -> Gallery(plugin, context, host, modifier)
        }
    }
}

@Composable private fun Gallery(plugin: PreviewPlugin, context: BrowserContext, host: PreviewHost, modifier: Modifier) {
    val current = context.items.find { it.resource.id == context.focused } ?: context.focusedItem
    val strip = rememberLazyListState()
    LaunchedEffect(context.focused, context.items.map { it.resource.id }) {
        val index = context.items.indexOfFirst { it.resource.id == context.focused }
        if (index >= 0) strip.scrollToItem(index)
    }
    Column(modifier.testTag("resource-gallery"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (current == null || context.items.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("选择资源以显示大预览") }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ContextMenuArea(items = { context.menu(current.resource) }) {
                    PreviewArea(plugin, current.target, host, true, Modifier.fillMaxSize())
                }
            }
            Text(current.resource.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LazyRow(Modifier.fillMaxWidth().height(185.dp), state = strip, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(context.items, key = { it.resource.id }) { item ->
                ResourceItemFrame(item, context, Modifier.width(180.dp)) {
                    PreviewArea(plugin, item.target, host, false, Modifier.fillMaxWidth().height(90.dp))
                    StandardResourceContent(item)
                }
            }
        }
        HorizontalScrollbar(rememberScrollbarAdapter(strip), Modifier.fillMaxWidth())
    }
}

/** Each displayed slot owns a cancellable session. Leaving it disposes plugin effects and pending callbacks. */
@Composable fun PreviewArea(plugin: PreviewPlugin, target: PreviewTarget, parent: PreviewHost, large: Boolean, modifier: Modifier = Modifier) {
    key(plugin.id, target, parent, large) {
        val host = remember { parent.child() }
        DisposableEffect(host) { onDispose { host.close() } }
        BoxWithConstraints(modifier.background(MaterialTheme.colors.onSurface.copy(alpha = .04f)), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val size = with(density) { IntSize(maxWidth.roundToPx().coerceAtLeast(1), maxHeight.roundToPx().coerceAtLeast(1)) }
            val request = PreviewRequest(target, size)
            val provider = plugin.largePreview
            val presentation = if (target.resource.status != Status.Active) PreviewPresentation.Unavailable
                else if (large && provider != null) provider.present(request, host)
                else ThumbnailPresentation(plugin, request, host)
            when (presentation) {
                PreviewPresentation.Loading -> CircularProgressIndicator(Modifier.size(20.dp))
                PreviewPresentation.Unavailable -> Text(if (large) "暂无预览" else if (target.resource.kind == Kind.Directory) "▣" else "▤")
                is PreviewPresentation.Failed -> Text(if (large) "预览失败：${presentation.message}" else "预览失败", maxLines = if (large) 4 else 2, style = MaterialTheme.typography.caption, color = MaterialTheme.colors.error)
                is PreviewPresentation.Ready -> presentation.content(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable private fun ThumbnailPresentation(plugin: PreviewPlugin, request: PreviewRequest, host: PreviewHost): PreviewPresentation {
    val provider = plugin.thumbnail ?: return PreviewPresentation.Unavailable
    return when (val result = rememberPreview(host, request) { host.files.resolve(request.target); provider.load(request, host) }) {
        PreviewLoad.Loading -> PreviewPresentation.Loading
        is PreviewLoad.Failed -> PreviewPresentation.Failed(result.message)
        is PreviewLoad.Ready -> result.value?.let { bitmap -> PreviewPresentation.Ready { modifier -> Image(bitmap, "预览 ${request.target.resource.name}", modifier, contentScale = ContentScale.Fit) } } ?: PreviewPresentation.Unavailable
    }
}

/** Reuse this shell in plugin grids so selection, focus and all menus keep identical semantics. */
@Composable fun ResourceItemFrame(item: BrowserItem, context: BrowserContext, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val resource = item.resource
    ContextMenuArea(items = { context.menu(resource) }) {
        Row(modifier.fillMaxWidth().background(if (resource.id == context.focused) MaterialTheme.colors.primary.copy(alpha = .12f) else MaterialTheme.colors.surface)
            .clickable { context.focus(resource.id) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(resource.id in context.selected, { context.select(resource.id, it) }, enabled = context.selectionEnabled,
                modifier = Modifier.semantics { contentDescription = "选择资源 ${resource.name}" })
            Column(Modifier.weight(1f), content = content)
        }
    }
}

@Composable fun StandardResourceContent(item: BrowserItem) {
    val resource = item.resource
    Text("${if (resource.kind == Kind.Directory) "▣" else "▤"} ${resource.name}", maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
    Text("${resource.kind.title} · ${item.target.root.name} · ${resource.status.title}", style = MaterialTheme.typography.caption)
    Text(item.tags.joinToString(" · ").ifEmpty { "无标签" }, style = MaterialTheme.typography.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (item.reviews.isNotEmpty()) Text(item.reviews.joinToString { it.reason.title }, color = MaterialTheme.colors.primary, style = MaterialTheme.typography.caption)
}

@Composable fun PreviewChooser(registry: PreviewRegistry, selectedId: String, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("预览插件：${registry.find(selectedId).title}") }
        DropdownMenu(expanded, { expanded = false }) {
            registry.plugins.forEach { plugin -> DropdownMenuItem(onClick = { expanded = false; select(plugin.id) }) { Text(plugin.title) } }
        }
    }
}
