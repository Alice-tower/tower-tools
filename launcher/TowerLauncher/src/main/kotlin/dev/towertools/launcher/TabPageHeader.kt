package dev.towertools.launcher

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun TabPageHeader(
    title: String,
    subtitle: String,
    onRefresh: () -> Unit,
    onSettings: (() -> Unit)? = null,
    refreshEnabled: Boolean = true,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.h5, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.body2,
                color = Color(0xFF616161),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedButton(onClick = onRefresh, enabled = refreshEnabled, modifier = Modifier.width(80.dp)) {
            Text("刷新")
        }
        if (onSettings != null) {
            Spacer(Modifier.width(8.dp))
            Button(onClick = onSettings, modifier = Modifier.width(80.dp)) {
                Text("设置")
            }
        }
    }
}
