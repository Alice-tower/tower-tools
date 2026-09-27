package dev.towertools.launcher

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

enum class TabId(val title: String) {
    TOOLS("工具"),
    CMD("CMD"),
}

data class LauncherTab(val id: TabId, val content: @Composable () -> Unit)

@Composable
fun LauncherApp(tabs: List<LauncherTab>) {
    require(tabs.isNotEmpty()) { "启动器至少需要一个 Tab" }
    require(tabs.map(LauncherTab::id).distinct().size == tabs.size) { "Tab 标识不能重复" }

    var selectedId by remember { mutableStateOf(tabs.first().id) }
    val selectedIndex = tabs.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: 0

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedIndex) {
            tabs.forEach { tab ->
                Tab(
                    selected = selectedId == tab.id,
                    onClick = { selectedId = tab.id },
                    text = { Text(tab.id.title) },
                )
            }
        }
        Column(Modifier.weight(1f)) {
            tabs[selectedIndex].content()
        }
    }
}
