package dev.towertools.towerreader.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.towertools.towerreader.*
import dev.towertools.towerreader.flow.FlowPane
import dev.towertools.towerreader.library.BookFormat
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(controller: ReaderController, fullScreen: Boolean, onFullScreen: () -> Unit, window: java.awt.Window? = null) {
    val book = controller.active ?: return
    var pane by remember(book.id) { mutableStateOf<FlowPane?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showContents by remember { mutableStateOf(false) }
    var showPageJump by remember { mutableStateOf(false) }
    var pdfColumns by remember { mutableStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(0f) }
    val currentFullScreen by rememberUpdatedState(fullScreen)
    val currentToggleFullscreen by rememberUpdatedState(onFullScreen)
    val style = controller.settings
    val state = controller.pageState
    val progress = controller.progress.toFloat()
    val turn: (Int) -> Unit = { d ->
        if (!controller.loading) {
            if (controller.pdf != null) controller.turnPdf(d, pdfColumns)
            else if (controller.pageState != null) pane?.turn(d)
        }
    }
    val currentTurn by rememberUpdatedState(turn)
    val shortcutsEnabled by rememberUpdatedState(!showSettings && !showContents && !showPageJump && controller.error == null)
    DisposableEffect(window, book.id) {
        val keys = window?.let { ReaderKeyDispatcher(it,
            enabled = { shortcutsEnabled }, onTurn = { currentTurn(it) },
            onFullScreen = { currentToggleFullscreen() },
            onEscape = { if (currentFullScreen) currentToggleFullscreen() else controller.returnToLibrary() }) }
        onDispose { keys?.close() }
    }
    Column(Modifier.fillMaxSize()) {
        if (!fullScreen) Surface(elevation = 1.dp, modifier = Modifier.testTag("reader-top-bar")) {
            Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { controller.returnToLibrary() }) { Text("← 书库") }
                Text(book.title, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.subtitle1)
                TextButton(onClick = { showContents = true }, enabled = controller.flow != null || controller.pdf != null) { Text("目录") }
                if (book.format != BookFormat.PDF) {
                    TextButton(onClick = { controller.updateSettings(style.copy(fontSize = (style.fontSize - 2).coerceAtLeast(14))) }) { Text("A−") }
                    TextButton(onClick = { controller.updateSettings(style.copy(fontSize = (style.fontSize + 2).coerceAtMost(48))) }) { Text("A+") }
                }
                if (book.format == BookFormat.PDF) TextButton(onClick = { showPageJump = true }, enabled = controller.pdf != null) { Text("跳转页码") }
                TextButton(onClick = { showSettings = true }) { Text("阅读设置") }
                TextButton(onClick = onFullScreen) { Text(if (fullScreen) "退出全屏" else "全屏") }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(java.awt.Color.decode(style.background).rgb)), contentAlignment = Alignment.Center) {
            val session = controller.pdf
            val html = controller.chapterHtml
            if (session != null) {
                PdfPages(controller, onColumns = { pdfColumns = it })
            } else if (html != null) {
                val token = controller.chapterToken
                key(book.id) {
                    var created by remember { mutableStateOf<FlowPane?>(null) }
                    val submitted = remember { longArrayOf(Long.MIN_VALUE) }
                    SwingPanel(
                        background = java.awt.Color.decode(style.background).let { Color(it.rgb) },
                        factory = {
                            FlowPane(
                                onPosition = { if (controller.chapterToken == it.requestId) controller.onFlowPosition(it) },
                                onBoundary = { direction ->
                                    if (!controller.loading && controller.pageState != null) controller.goChapter(controller.chapter + direction, if (direction < 0) -1 else 0)
                                },
                                onLink = { href ->
                                    if (href.startsWith("#")) pane?.fragment(href.drop(1))
                                },
                                onAction = { key -> if (key == "F11") currentToggleFullscreen() else if (key == "Escape") { if (currentFullScreen) currentToggleFullscreen() else controller.returnToLibrary() } },
                                onError = { controller.error = it },
                                windowKeyboard = window != null,
                            ).also {
                                created = it; pane = it
                            }
                        },
                        update = {
                            if (submitted[0] != token) {
                                submitted[0] = token
                                it.showChapter(html, controller.pageState?.takeIf { position -> position.requestId == token }?.offset ?: controller.chapterOffset, style, token)
                            }
                            it.updateSettings(style)
                            it.preloadChapter(controller.chapterPreloadHtml)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    DisposableEffect(Unit) {
                        onDispose { created?.disposeReader(); if (pane === created) pane = null }
                    }
                }
            }
            if (controller.loading && html == null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(32.dp))
                Text("正在准备阅读…", Modifier.padding(top = 16.dp))
            } else if (session == null && html == null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("暂时无法打开这本书")
                TextButton(onClick = { controller.open(book) }) { Text("重试") }
            }
        }
        if (!fullScreen) Surface(elevation = 2.dp, modifier = Modifier.testTag("reader-bottom-bar")) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { turn(-1) }, enabled = !controller.loading) { Text("‹ 上一页") }
                    Spacer(Modifier.width(12.dp))
                    Slider(
                        value = if (dragging) sliderValue else progress,
                        onValueChange = { dragging = true; sliderValue = it },
                        onValueChangeFinished = { controller.seek(sliderValue.toDouble()); dragging = false },
                        modifier = Modifier.weight(1f),
                        enabled = controller.flow != null || controller.pdf != null,
                    )
                    Text(String.format("%.1f%%", (if (dragging) sliderValue else progress) * 100), Modifier.width(72.dp).padding(start = 12.dp), style = MaterialTheme.typography.caption)
                    TextButton(onClick = { turn(1) }, enabled = !controller.loading) { Text("下一页 ›") }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val document = controller.flow
                    val pdf = controller.pdf
                    if (document != null) {
                        Text(document.chapters[controller.chapter].title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.caption)
                        Text("第 " + (controller.chapter + 1) + "/" + document.chapters.size + " 节 · " +
                            if (state == null) "排版中" else "本节 " + (state.page + 1) +
                                (if (state.columns == 2 && state.page + 1 < state.pageCount) "–" + minOf(state.page + 2, state.pageCount) else "") +
                                "/" + state.pageCount + " 页", style = MaterialTheme.typography.caption)
                    } else if (pdf != null) {
                        Spacer(Modifier.weight(1f))
                        Text("第 " + (controller.pdfPage + 1) +
                            (if (pdfColumns == 2 && controller.pdfPage + 1 < pdf.pageCount) "–" + (controller.pdfPage + 2) else "") +
                            " / " + pdf.pageCount + " 页", modifier = Modifier.testTag("pdf-page-count"), style = MaterialTheme.typography.caption)
                    }
                }
            }
        }
    }
    if (showPageJump) controller.pdf?.let { pdf ->
        PdfPageJumpDialog(controller.pdfPage, pdf.pageCount, onClose = { showPageJump = false }) {
            controller.jumpPdfPage(it); showPageJump = false
        }
    }
    if (showSettings) ReaderSettingsDialog(controller, onClose = { showSettings = false })
    if (showContents) Dialog(onCloseRequest = { showContents = false }, title = "目录") {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(24.dp)) {
                Text("目录", style = MaterialTheme.typography.h6)
                val chapters = controller.flow?.chapters
                val outline = controller.pdf?.outline
                if (chapters != null) {
                    LazyColumn(Modifier.weight(1f).padding(top = 16.dp)) {
                        itemsIndexed(chapters) { index, entry ->
                            TextButton(onClick = { controller.goChapter(index); showContents = false }, modifier = Modifier.fillMaxWidth()) {
                                Text((index + 1).toString() + "  " + entry.title, Modifier.fillMaxWidth(), maxLines = 2)
                            }
                        }
                    }
                } else if (!outline.isNullOrEmpty()) {
                    LazyColumn(Modifier.weight(1f).padding(top = 16.dp)) {
                        itemsIndexed(outline) { _, entry ->
                            TextButton(onClick = { controller.jumpPdfPage(entry.pageIndex); showContents = false }, modifier = Modifier.fillMaxWidth()) {
                                Text(entry.title + " · " + (entry.pageIndex + 1), Modifier.fillMaxWidth())
                            }
                        }
                    }
                } else Text("这本 PDF 没有内置目录，可使用“跳转页码”按钮。", Modifier.weight(1f).padding(top = 24.dp))
                TextButton(onClick = { showContents = false }, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
            }
        }
    }
}

@Composable
private fun PdfPages(controller: ReaderController, onColumns: (Int) -> Unit) {
    val session = controller.pdf ?: return
    var width by remember { mutableIntStateOf(1000) }
    var height by remember { mutableIntStateOf(700) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val settings = controller.settings
    val columns = when (settings.spread) {
        "double" -> if (width / density >= 620) 2 else 1
        "auto" -> if (width / density >= 1080) 2 else 1
        else -> 1
    }
    SideEffect { onColumns(columns); controller.updatePdfColumns(columns) }
    var images by remember(session) { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    val generation = remember(session) { AtomicLong() }
    val page = controller.pdfPage
    var renderedPage by remember(session) { mutableIntStateOf(-1) }
    LaunchedEffect(session, page, width, height, settings.pdfZoom, columns) {
        val token = generation.incrementAndGet()
        if (renderedPage == page) kotlinx.coroutines.delay(120)
        renderedPage = page
        controller.readerExecutor.execute {
            if (generation.get() == token) {
                runCatching {
                    val result = (page until minOf(page + columns, session.pageCount)).map {
                        session.render(it, ((width - 48 * density) / columns).toInt().coerceAtLeast(32), (height - 28 * density).toInt().coerceAtLeast(32), settings.pdfZoom).toComposeImageBitmap()
                    }
                    EventQueue.invokeLater { if (generation.get() == token) { images = result } }
                    // Only one adjacent spread is prefetched, and stale work is skipped.
                    if (generation.get() == token && settings.pdfZoom == 1.0) {
                        val next = page + columns
                        if (next < session.pageCount) session.render(next, ((width - 48 * density) / columns).toInt().coerceAtLeast(32), (height - 28 * density).toInt().coerceAtLeast(32))
                    }
                }.onFailure { e -> EventQueue.invokeLater { if (generation.get() == token) { controller.error = e.message ?: "PDF 显示失败" } } }
            }
        }
    }
    DisposableEffect(session) { onDispose { generation.incrementAndGet(); images = emptyList() } }
    val filter = if (settings.pdfDark) ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
        -0.78f,0f,0f,0f,230f, 0f,-0.78f,0f,0f,230f, 0f,0f,-0.78f,0f,230f, 0f,0f,0f,1f,0f
    ))) else null
    Box(Modifier.fillMaxSize().onSizeChanged { width = it.width; height = it.height }, contentAlignment = Alignment.Center) {
        val horizontal = rememberScrollState()
        val vertical = rememberScrollState()
        LaunchedEffect(page) { horizontal.scrollTo(0); vertical.scrollTo(0) }
        Row(Modifier.fillMaxSize().horizontalScroll(horizontal).verticalScroll(vertical).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically) {
            for (bitmap in images) Image(bitmap, "PDF 页面",
                Modifier.width((bitmap.width / density).dp).height((bitmap.height / density).dp), colorFilter = filter)
        }

    }
}

@Composable
private fun ReaderSettingsDialog(controller: ReaderController, onClose: () -> Unit) {
    val settings = controller.settings
    val pdf = controller.active?.format == BookFormat.PDF
    val fonts = remember { GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toList() }
    var fontMenu by remember { mutableStateOf(false) }
    Dialog(onCloseRequest = onClose, title = "阅读设置") {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("阅读设置", style = MaterialTheme.typography.h6)
                Text("每屏页数", style = MaterialTheme.typography.subtitle2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("single" to "单页", "double" to "双页", "auto" to "自动").forEach { (key, label) ->
                        OutlinedButton(onClick = { controller.updateSettings(settings.copy(spread = key)) }) { Text((if (settings.spread == key) "✓ " else "") + label) }
                    }
                }
                if (pdf) {
                    Text("缩放 " + (settings.pdfZoom * 100).roundToInt() + "% · 100% 适合页面")
                    Slider(settings.pdfZoom.toFloat(), { controller.updateSettings(settings.copy(pdfZoom = (it * 10).roundToInt() / 10.0)) }, valueRange = 1f..3f, steps = 19)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(settings.pdfDark, { controller.updateSettings(settings.copy(pdfDark = it)) })
                        Text("页面变暗（会改变图片颜色）")
                    }
                } else {
                    Box {
                        OutlinedButton(onClick = { fontMenu = true }) { Text("字体 · " + settings.fontFamily) }
                        DropdownMenu(fontMenu, onDismissRequest = { fontMenu = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                            fonts.forEach { font -> DropdownMenuItem(onClick = { controller.updateSettings(settings.copy(fontFamily = font)); fontMenu = false }) { Text(font) } }
                        }
                    }
                    Text("字号 " + settings.fontSize)
                    Slider(settings.fontSize.toFloat(), { controller.updateSettings(settings.copy(fontSize = it.roundToInt())) }, valueRange = 14f..48f, steps = 33)
                    Text("行距 " + String.format("%.1f", settings.lineHeight))
                    Slider(settings.lineHeight.toFloat(), { controller.updateSettings(settings.copy(lineHeight = (it * 10).roundToInt() / 10.0)) }, valueRange = 1.2f..2.4f, steps = 11)
                    Text("页边距 " + settings.margin)
                    Slider(settings.margin.toFloat(), { controller.updateSettings(settings.copy(margin = it.roundToInt())) }, valueRange = 16f..80f)
                    if (controller.active?.format == BookFormat.TXT) {
                        Text("文字编码 · " + (controller.flow?.encoding ?: "自动"))
                        Row {
                            listOf("UTF-8", "GB18030", "UTF-16LE").forEach { encoding ->
                                TextButton(onClick = { controller.changeEncoding(encoding); onClose() }) { Text(encoding) }
                            }
                        }
                    }
                }
                Text("← / → 或 Page Up / Page Down 翻页 · F11 全屏 · Esc 退出全屏", style = MaterialTheme.typography.caption)
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("完成") }
            }
        }
    }
}

@Composable
internal fun PdfPageJumpDialog(currentPage: Int, pageCount: Int, onClose: () -> Unit, onJump: (Int) -> Unit) {
    var pageInput by remember { mutableStateOf((currentPage + 1).toString()) }
    val page = pageInput.toIntOrNull()
    val valid = page != null && page in 1..pageCount
    val focus = remember { FocusRequester() }
    val submit = { if (valid) onJump(page!! - 1) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("跳转页码") },
        text = {
            Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("输入 1–$pageCount 之间的页码")
                OutlinedTextField(pageInput, { pageInput = it.filter(Char::isDigit).take(8) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) { submit(); true } else false
                    },
                    label = { Text("页码") }, singleLine = true, isError = pageInput.isNotEmpty() && !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }))
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        },
        confirmButton = { TextButton(onClick = submit, enabled = valid) { Text("跳转") } },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}
