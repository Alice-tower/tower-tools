package dev.towertools.towerreader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.sun.jna.WString
import com.sun.jna.platform.win32.Shell32
import dev.towertools.towerreader.weread.WeReadController
import dev.towertools.towerreader.weread.WeReadScreen
import dev.towertools.towerreader.ui.LibraryScreen
import dev.towertools.towerreader.ui.ReaderScreen
import dev.towertools.towerreader.ui.ReaderFullscreen
import dev.towertools.towerreader.ui.configureReaderRendering
import java.awt.EventQueue
import java.awt.Frame
import java.awt.FileDialog
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.*
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.nio.file.Files
import javafx.application.Platform

fun main(args: Array<String>) {
    configureReaderRendering()
    if (args.firstOrNull() == "--verify-runtime") {
        require(args.size == 2) { "Specify an empty verification output directory." }
        kotlin.system.exitProcess(RuntimeVerification.run(java.nio.file.Path.of(args[1])))
    }
    runCatching {
        val result = Shell32.INSTANCE.SetCurrentProcessExplicitAppUserModelID(WString(AppMetadata.id))
        check(result.toInt() == 0)
    }.onFailure { AppLog.logger.warning(it.message) }
    System.setProperty("javafx.cachedir", AppPaths.dataDirectory.resolve("cache/javafx").also(Files::createDirectories).toString())
    val instance = runCatching { SingleInstance.acquire(AppMetadata.id) }
        .onFailure { AppLog.logger.severe("无法初始化单实例控制：" + it.message) }.getOrNull() ?: return
    application {
        val state = rememberWindowState(width = 1180.dp, height = 820.dp)
        val controller = remember { ReaderController() }
        val weread = remember { WeReadController(AppPaths.dataDirectory.resolve("weread")) }
        var closingWindow by remember { mutableStateOf(false) }
        val close = {
            if (!closingWindow) {
                closingWindow = true
                controller.flushProgress()
                weread.close { controller.close { runCatching { Platform.exit() }; exitApplication() } }
            }
        }
        Window(onCloseRequest = close, state = state, title = AppMetadata.displayName, icon = painterResource("app-icon.png")) {
            val fullscreen = remember(window) { ReaderFullscreen(window) }
            val chooseFiles: () -> Unit = {
                FileDialog(window, "导入 TXT、EPUB 或 PDF", FileDialog.LOAD).apply {
                    isMultipleMode = true
                    file = "*.txt;*.epub;*.pdf"
                    isVisible = true
                    val selected = files.map { it.toPath() }
                    dispose()
                    if (selected.isNotEmpty()) controller.importFiles(selected)
                }
            }
            DisposableEffect(window) {
                window.minimumSize = java.awt.Dimension(720, 520)
                instance.onActivate { EventQueue.invokeLater { window.extendedState = Frame.NORMAL; window.isVisible = true; window.toFront(); window.requestFocus() } }
                val listener = object : WindowAdapter() { override fun windowDeactivated(e: WindowEvent?) { controller.flushProgress() } }
                window.addWindowListener(listener)
                val drop = DropTarget(window, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
                    override fun dragEnter(event: DropTargetDragEvent) {
                        if (event.isDataFlavorSupported(DataFlavor.javaFileListFlavor) && controller.ready && !controller.importing) event.acceptDrag(DnDConstants.ACTION_COPY)
                        else event.rejectDrag()
                    }
                    override fun drop(event: DropTargetDropEvent) {
                        try {
                            if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor) || !controller.ready || controller.importing) { event.rejectDrop(); return }
                            event.acceptDrop(DnDConstants.ACTION_COPY)
                            val files = (event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>().map { it.toPath() }
                            EventQueue.invokeLater { controller.importFiles(files) }
                            event.dropComplete(true)
                        } catch (e: Exception) { event.dropComplete(false); controller.error = "无法拖入文件：" + e.message }
                    }
                }, true)
                onDispose { drop.isActive = false; window.removeWindowListener(listener); instance.close() }
            }
            val immersive = fullscreen.active
            MaterialTheme(colors = readerColors(controller.settings.theme)) {
                Surface(Modifier.fillMaxSize()) {
                    Column {
                        if (!immersive && !closingWindow) Surface(elevation = 1.dp) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("静读", style = MaterialTheme.typography.h6, modifier = Modifier.padding(end = 24.dp))
                                if (!weread.selected) Button(onClick = {}, enabled = weread.ready) { Text("本地书库") }
                                else TextButton(onClick = { weread.select(false) }, enabled = weread.ready) { Text("本地书库") }
                                Spacer(Modifier.width(8.dp))
                                if (weread.selected) Button(onClick = {}, enabled = weread.ready) { Text("微信读书") }
                                else TextButton(onClick = { controller.flushProgress(); weread.select(true) }, enabled = weread.ready) { Text("微信读书") }
                                Spacer(Modifier.weight(1f))
                                ThemeSelector(controller)
                            }
                        }
                        if (controller.closing || closingWindow) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("正在保存并退出…") }
                        } else if (!controller.ready) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    if (controller.error == null) CircularProgressIndicator()
                                    Text(if (controller.error == null) "正在打开书库…" else "书库暂时无法打开，请检查错误信息。", Modifier.padding(16.dp))
                                }
                            }
                        } else {
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                val toggleFullscreen: () -> Unit = {
                                    runCatching { fullscreen.toggle() }.onFailure { controller.error = "无法切换全屏：" + it.message }
                                }
                                if (weread.activated) WeReadScreen(weread, weread.selected, fullscreen.active, toggleFullscreen, window)
                                if (!weread.selected) {
                                    if (controller.active == null) LibraryScreen(controller, chooseFiles)
                                    else ReaderScreen(controller, fullscreen.active, toggleFullscreen, window)
                                }
                            }
                        }
                        if (!immersive && controller.importing && controller.active != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(controller.importStatus, Modifier.weight(1f), style = MaterialTheme.typography.caption)
                            TextButton(onClick = controller::cancelImport) { Text("取消导入") }
                        }
                        controller.notice?.takeUnless { immersive }?.let { note ->
                            Surface(color = MaterialTheme.colors.primary.copy(alpha = .12f)) {
                                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(note, Modifier.weight(1f), style = MaterialTheme.typography.caption, maxLines = 3)
                                    TextButton(onClick = { controller.notice = null }) { Text("关闭") }
                                }
                            }
                        }
                    }
                }
                controller.error?.let { message ->
                    AlertDialog(
                        onDismissRequest = { controller.error = null },
                        title = { Text("需要注意") },
                        text = { Text(message, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) },
                        confirmButton = { TextButton(onClick = { controller.error = null }) { Text("知道了") } }
                    )
                }
            }
        }
    }
}

internal fun readerColors(theme: String): Colors =
    if (theme == "dark") darkColors(primary = Color(0xffb5caa6), background = Color(0xff1b211e), surface = Color(0xff252d28), onPrimary = Color(0xff18211a))
    else lightColors(primary = Color(0xff4b664c), secondary = Color(0xff7c8b68), background = Color(0xfff4eedf), surface = Color(0xfffaf5e9), onPrimary = Color.White, onBackground = Color(0xff34382f), onSurface = Color(0xff34382f))

@Composable
internal fun ThemeSelector(controller: ReaderController) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("paper" to "暖纸", "dark" to "深色").forEach { (key, label) ->
            OutlinedButton(
                onClick = { controller.updateSettings(controller.settings.copy(theme = key)) },
                enabled = controller.ready && !controller.closing,
            ) { Text((if (controller.settings.theme == key) "✓ " else "") + label) }
        }
    }
}