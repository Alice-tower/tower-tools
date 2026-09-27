package dev.towertools.launcher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

@Composable
internal fun TabFeedbackBar(message: String, isError: Boolean = false) {
    var isTruncated by remember(message) { mutableStateOf(false) }
    var detailsOpen by remember(message) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().height(24.dp)) {
        Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.12f))
        Box(Modifier.fillMaxWidth().weight(1f).padding(top = 2.dp), contentAlignment = Alignment.CenterStart) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.caption,
                    color = if (isError) MaterialTheme.colors.error else Color(0xFF616161),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { isTruncated = it.hasVisualOverflow },
                )
                if (isTruncated) {
                    Text(
                        " 查看详情",
                        modifier = Modifier.clickable { detailsOpen = true },
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.primary,
                        fontWeight = FontWeight.Bold,
                        textDecoration = TextDecoration.Underline,
                    )
                }
            }
        }
    }

    if (detailsOpen) {
        AlertDialog(
            onDismissRequest = { detailsOpen = false },
            title = { Text(if (isError) "错误详情" else "详细信息") },
            text = {
                SelectionContainer {
                    Box(Modifier.width(400.dp).heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                        Text(message)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { detailsOpen = false }) { Text("关闭") } },
        )
    }
}
