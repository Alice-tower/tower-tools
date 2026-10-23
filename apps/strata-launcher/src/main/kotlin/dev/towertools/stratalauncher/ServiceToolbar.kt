package dev.towertools.stratalauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@Composable
internal fun ServiceToolbar(status: PortStatus, onCopyApi: (String) -> Unit, onOpenWeb: (String) -> Unit) {
    Row(Modifier.testTag("service-toolbar"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.testTag("service-port-status").semantics(mergeDescendants = true) {
            contentDescription = "7271 端口状态"
            stateDescription = status.label
        }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(9.dp).background(
                if (status == PortStatus.CONNECTED) Color(0xFF2EAF62) else MaterialTheme.colors.onSurface.copy(alpha = .35f),
                CircleShape))
            Text("7271 ${status.label}", style = MaterialTheme.typography.caption)
        }
        OutlinedButton({ onCopyApi(LocalService.API_URL) }, modifier = Modifier.testTag("copy-service-api")) { Text("复制 API") }
        OutlinedButton({ onOpenWeb(LocalService.WEB_URL) }, modifier = Modifier.testTag("open-service-web")) { Text("打开网页") }
    }
}
