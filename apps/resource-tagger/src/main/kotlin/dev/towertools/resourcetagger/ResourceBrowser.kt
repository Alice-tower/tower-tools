package dev.towertools.resourcetagger

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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

object StandardPreviewPlugin : PreviewPlugin {
    override val id = "standard"
    override val title = "标准列表"
    override val browser = PreviewBrowser { context, _, modifier ->
        LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(context.items, key = { it.resource.id }) { item ->
                ResourceItemFrame(item, context) { StandardResourceContent(item) }
            }
        }
    }
}

@Composable fun ResourceBrowser(plugin: PreviewPlugin, context: BrowserContext, host: PreviewHost, modifier: Modifier = Modifier) {
    key(plugin.id) { (plugin.browser ?: StandardPreviewPlugin.browser).Content(context, host, modifier) }
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
        OutlinedButton(onClick = { expanded = true }) { Text("浏览方式：${registry.find(selectedId).title}") }
        DropdownMenu(expanded, { expanded = false }) {
            registry.plugins.forEach { plugin -> DropdownMenuItem(onClick = { expanded = false; select(plugin.id) }) { Text(plugin.title) } }
        }
    }
}
