package dev.towertools.towerreader.weread

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import kotlin.math.roundToInt
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.awt.KeyboardFocusManager
import java.awt.KeyEventDispatcher
import java.awt.event.KeyEvent

@Composable
internal fun WeReadScreen(controller: WeReadController, visible: Boolean, fullScreen: Boolean,
    onFullScreen: () -> Unit, window: java.awt.Window) {
    val toggle by rememberUpdatedState(onFullScreen)
    val immersive by rememberUpdatedState(fullScreen)
    val selected by rememberUpdatedState(visible)
    val shortcut: (Boolean) -> Unit = { isToggle -> if (selected && (isToggle || immersive)) toggle() }
    DisposableEffect(window) {
        val held = mutableSetOf<Int>()
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val dispatcher = KeyEventDispatcher { event ->
            if (event.id == KeyEvent.KEY_RELEASED) held.remove(event.keyCode)
            if (!selected || manager.activeWindow !== window || event.isAltDown || event.isControlDown || event.isMetaDown ||
                (event.keyCode != KeyEvent.VK_F11 && !(event.keyCode == KeyEvent.VK_ESCAPE && immersive))) false
            else { if (event.id == KeyEvent.KEY_PRESSED && held.add(event.keyCode)) toggle(); event.consume(); true }
        }
        manager.addKeyEventDispatcher(dispatcher)
        onDispose { manager.removeKeyEventDispatcher(dispatcher) }
    }
    val pane = remember(window) { controller.createPane(window, shortcut) }
    SideEffect { pane.showPage(visible, fullScreen) }
    var confirmClear by remember { mutableStateOf(false) }
    Column(if (visible) Modifier.fillMaxSize() else Modifier.size(0.dp)) {
        if (visible && !fullScreen) {
            Surface(elevation = 1.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { controller.pane?.back() }, enabled = controller.state.back && !controller.clearing) { Text("← 后退") }
                    TextButton(onClick = { controller.pane?.forward() }, enabled = controller.state.forward && !controller.clearing) { Text("前进 →") }
                    TextButton(onClick = { controller.pane?.reload() }, enabled = controller.state.ready && !controller.clearing) { Text("刷新") }
                    TextButton(onClick = { controller.pane?.shelf() }, enabled = controller.state.ready && !controller.clearing) { Text("回书架") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { confirmClear = true }, enabled = controller.state.ready && !controller.clearing) { Text("清除登录数据") }
                    TextButton(onClick = onFullScreen, enabled = !controller.clearing) { Text("全屏") }
                }
            }
            if (controller.state.loading || controller.clearing) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        }
        if (visible) (controller.message ?: controller.state.error)?.let { message ->
            Surface(color = MaterialTheme.colors.surface) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), style = MaterialTheme.typography.body2)
                    if (controller.state.error != null) TextButton(onClick = { controller.pane?.restart() }) { Text("重新打开") }
                    if (controller.message != null) TextButton(onClick = { controller.message = null }) { Text("关闭") }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().testTag("weread-browser").onGloballyPositioned { coordinates ->
            if (visible && coordinates.size.width > 0 && coordinates.size.height > 0) {
                val bounds = coordinates.boundsInWindow()
                pane.setBounds(java.awt.Rectangle(bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.width.roundToInt(), bounds.height.roundToInt()))
            }
        })
    }
    if (confirmClear && visible) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("清除微信读书登录数据？") },
        text = { Text("将清除此页面保存的登录状态和网站缓存，下次需要重新扫码。本地书库和阅读记录会保留。") },
        confirmButton = { TextButton(onClick = { confirmClear = false; controller.clearLogin() }) { Text("清除") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
}
