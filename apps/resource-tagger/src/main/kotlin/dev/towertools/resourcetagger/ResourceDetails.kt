package dev.towertools.resourcetagger

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable fun ResourceDetails(target: PreviewTarget?, data: Snapshot, tagsById: Map<String, Tag>, loading: Boolean, actions: ResourceActions, plugin: PreviewPlugin, host: PreviewHost, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("资源详情", fontWeight = FontWeight.Bold)
        if (target == null) {
            Text(if (loading) "正在读取详情…" else "单击资源查看详情；勾选可批量编辑标签。")
        } else key(plugin.id, target) {
            val tabs = remember(plugin, target, host) {
                try { checkedContributions(plugin.tabs(target)) { it.id }.also { require(it.none { tab -> tab.id == "information" }) } }
                catch (e: Exception) { host.report(e); emptyList() }
            }
            var requestedTab by remember { mutableStateOf("information") }
            val selectedTab = requestedTab.takeIf { id -> id == "information" || tabs.any { it.id == id } } ?: "information"
            ScrollableTabRow(selectedTabIndex = if (selectedTab == "information") 0 else tabs.indexOfFirst { it.id == selectedTab } + 1, edgePadding = 0.dp) {
                Tab(selectedTab == "information", onClick = { requestedTab = "information" }, text = { Text("资源信息") })
                tabs.forEach { tab -> Tab(selectedTab == tab.id, onClick = { requestedTab = tab.id }, text = { Text(tab.title) }) }
            }
            if (selectedTab == "information") {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ResourceInformation(target.resource, target.root, data, tagsById, actions)
                }
            } else Box(Modifier.weight(1f).fillMaxWidth()) {
                key(selectedTab, host) { tabs.single { it.id == selectedTab }.content(target, host) }
            }
        }
    }
}

@Composable private fun ResourceInformation(detail: Resource, root: Root, detailData: Snapshot, tagsById: Map<String, Tag>, actions: ResourceActions) {
    Text(detail.name, style = MaterialTheme.typography.h6)
    Text("${detail.kind.title} · ${detail.status.title}")
    Text("Root：${root.name}")
    SelectionContainer { Text(PathsPolicy.actual(root, detail).toString(), style = MaterialTheme.typography.body2) }
    if (root.error != null) Text("Root 最近扫描失败：${root.error}", color = MaterialTheme.colors.error)
    Text("创建：${detail.created}", style = MaterialTheme.typography.caption)
    Text("最近发现：${detail.lastSeen ?: "—"}", style = MaterialTheme.typography.caption)
    Divider()
    Text("标签", fontWeight = FontWeight.Bold)
    Text(detailData.links[detail.id].orEmpty().mapNotNull { tagsById[it]?.name }.sorted().joinToString(" · ").ifEmpty { "尚无标签" })
    OutlinedButton(enabled = !actions.busy, onClick = { actions.editTags(detail.id) }) { Text("编辑标签") }
    if (detail.status == Status.Active) {
        if (detail.kind == Kind.Directory) Button(enabled = !actions.busy, onClick = { actions.navigate(detail, true) }) { Text("打开目录") }
        OutlinedButton(enabled = !actions.busy, onClick = { actions.navigate(detail, false) }) { Text("在所在目录中显示") }
    }
    detailData.reviews.forEach { pending ->
        Text("待处理：${pending.reason.title}", color = MaterialTheme.colors.primary)
        if (pending.reason == Reason.TypeChanged) {
            Text("当前对象是${pending.observedKind?.title}。确认后，原有标签将关联到当前对象。")
            Button(enabled = !actions.busy, onClick = { actions.confirmType(detail, pending) }) { Text("确认关联当前对象") }
        } else Button(enabled = !actions.busy, onClick = { actions.acknowledge(detail, pending) }) { Text(if (pending.reason == Reason.New) "确认收录" else "保留记录并确认提醒") }
    }
    if (detail.status != Status.Ignored) OutlinedButton(enabled = !actions.busy, onClick = { actions.relocate(detail) }) { Text("重新定位") }
    OutlinedButton(enabled = !actions.busy, onClick = { actions.ignore(detail) }) { Text(if (detail.status == Status.Ignored) "取消忽略" else "忽略") }
    TextButton(enabled = !actions.busy, onClick = { actions.remove(detail) }) { Text("从数据库移除") }
}
