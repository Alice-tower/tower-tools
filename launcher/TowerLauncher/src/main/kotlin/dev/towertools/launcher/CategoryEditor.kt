package dev.towertools.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun CategoryEditMenu(onEdit: (() -> Unit)?, content: @Composable () -> Unit) {
    if (onEdit == null) {
        content()
    } else {
        var expanded by remember { mutableStateOf(false) }
        var position by remember { mutableStateOf(IntOffset.Zero) }
        Box(
            Modifier.onPointerEvent(PointerEventType.Press) { event ->
                if (event.buttons.isSecondaryPressed) {
                    event.changes.firstOrNull()?.position?.let {
                        position = IntOffset(it.x.roundToInt(), it.y.roundToInt())
                    }
                    expanded = true
                }
            },
        ) {
            content()
            Box(Modifier.offset { position }.size(1.dp)) {
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(onClick = { expanded = false; onEdit() }) { Text("编辑") }
                }
            }
        }
    }
}

@Composable
internal fun CategoryRenameDialog(
    category: String,
    categories: List<String>,
    onDismiss: () -> Unit,
    onSave: (String) -> String?,
) {
    var name by remember(category) { mutableStateOf(category) }
    var saveError by remember(category) { mutableStateOf<String?>(null) }
    val normalized = name.trim()
    val validationError = when {
        normalized.isEmpty() -> "分类名称不能为空"
        normalized != category && normalized in categories -> "该分类名称已存在"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑分类") },
        text = {
            Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; saveError = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("分类名称") },
                    singleLine = true,
                    isError = validationError != null || saveError != null,
                )
                (validationError ?: saveError)?.let { Text(it, color = MaterialTheme.colors.error) }
            }
        },
        confirmButton = {
            Button(enabled = validationError == null, onClick = { saveError = onSave(normalized) }) {
                Text("保存更改")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
