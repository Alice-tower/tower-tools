package dev.towertools.naibox

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun SectionTitle(title: String) { Text(title, style = MaterialTheme.typography.h6, modifier = Modifier.padding(vertical = 8.dp)) }

@Composable internal fun KeyValues(rows: List<Pair<String, String>>) {
    rows.filter { it.second.isNotEmpty() }.forEach { (name, value) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(name, Modifier.width(145.dp), color = MaterialTheme.colors.onSurface.copy(alpha = .65f))
            SelectionContainer { Text(value, Modifier.weight(1f)) }
        }
        Divider(color = MaterialTheme.colors.onSurface.copy(alpha = .08f))
    }
}

@Composable internal fun Notice(message: String, warn: Boolean = false) {
    Surface(color = (if (warn) Color(0xffff9800) else MaterialTheme.colors.primary).copy(alpha = .12f), shape = RoundedCornerShape(7.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp)) {
        SelectionContainer { Text(message, Modifier.padding(10.dp)) }
    }
}

@Composable internal fun CopyBlock(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionTitle(title)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null) }) { Text("复制") }
    }
    Surface(color = MaterialTheme.colors.onSurface.copy(alpha = .05f), shape = RoundedCornerShape(7.dp)) {
        SelectionContainer { Text(value.take(20000) + if (value.length > 20000) "\n…（显示已截断，复制仍包含全文）" else "", Modifier.fillMaxWidth().padding(12.dp)) }
    }
}

@Composable internal fun ImagePreview(bytes: ByteArray?, modifier: Modifier = Modifier) {
    val decoded = produceState<java.awt.image.BufferedImage?>(null, bytes) {
        value = bytes?.let { withContext(Dispatchers.IO) { runCatching { PreviewDecoder.decode(it) }.getOrNull() } }
    }
    val image = remember(decoded.value) { decoded.value?.toComposeImageBitmap() }
    Surface(modifier, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colors.onSurface.copy(alpha = .06f)) {
        if (image != null) Image(image, "图片预览", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Box(contentAlignment = Alignment.Center) { Text("无法预览图片") }
    }
}

@Composable internal fun ParamsPanel(info: ImageInfo) {
    info.warnings.forEach { Notice(it, true) }
    val nai = info.novelAi
    when {
        nai.isNai -> {
            Notice("检测到 NovelAI 元数据${nai.model.takeIf(String::isNotEmpty)?.let { " · $it" }.orEmpty()}${nai.hash.takeIf(String::isNotEmpty)?.let { " · $it" }.orEmpty()}")
            if (nai.params == null) Notice("没有可解析的参数 JSON。可在原始元数据中查看现有文本块。", true)
            if (nai.description.isNotEmpty()) CopyBlock("Prompt (Description)", nai.description)
            nai.params?.get("uc").value().takeIf(String::isNotEmpty)?.let { CopyBlock("Undesired Content (uc)", it) }
            val params = nai.params
            if (params != null) {
                val order = listOf("seed", "steps", "sampler", "scale", "noise_schedule", "cfg_rescale", "ucPreset", "qualityToggle", "autoSmea", "dynamic_thresholding", "params_version", "sm", "sm_dyn", "skip_cfg_above_sigma", "use_coords", "legacy_uc", "strength", "noise", "extra_noise_seed", "add_original_image", "inpaintImg2ImgStrength", "controlnet_model", "controlnet_strength", "stream")
                SectionTitle("生成参数")
                KeyValues(buildList {
                    if (nai.model.isNotEmpty()) add("model" to nai.model)
                    if (nai.hash.isNotEmpty()) add("model hash" to nai.hash)
                    if (params["width"] != null || params["height"] != null) add("width × height" to "${params["width"].value()} × ${params["height"].value()}")
                    order.forEach { name -> params[name]?.let { add(name to it.value()) } }
                })
                val extra = params.filterKeys { it !in order && it !in setOf("v4_prompt", "v4_negative_prompt", "characterPrompts", "uc", "width", "height") }
                if (extra.isNotEmpty()) { SectionTitle("其他字段"); KeyValues(extra.toList().map { it.first to it.second.value().take(240) }) }
                val count = params["reference_image_multiple"].array()?.size ?: 0
                if (count > 0) Notice("Vibe Transfer：记录了 $count 张参考图的参数；参考图内容不在元数据里。", true)
            }
        }
        info.card != null -> CardPanel(info.card!!)
        info.a1111 != null -> {
            Notice("检测到 A1111 / Forge parameters")
            val a = info.a1111!!
            CopyBlock("正向提示词", a.positive)
            if (a.negative.isNotEmpty()) CopyBlock("反向提示词", a.negative)
            KeyValues(a.fields.toList())
        }
        else -> {
            if (info.texts.isEmpty()) Notice("没有可展示的文本参数", true)
            info.texts.forEach { t -> if (t.entries.isNotEmpty()) { SectionTitle("${t.chunkType} · ${t.keyword}"); KeyValues(t.entries) } else CopyBlock("${t.chunkType} · ${t.keyword}", t.text) }
        }
    }
}

@Composable internal fun CharactersPanel(info: ImageInfo) {
    val base = info.novelAi.params?.get("v4_prompt").obj()?.get("caption").obj()?.get("base_caption").value().ifEmpty { info.novelAi.description }
    if (base.isNotEmpty()) CopyBlock("base_caption", base)
    info.novelAi.characters.forEachIndexed { i, c ->
        SectionTitle("角色 ${i + 1} · ${c.source} · ${c.centers.joinToString(" ") { "(${it.first}, ${it.second})" }.ifEmpty { "无坐标" }}")
        CopyBlock("char_caption", c.caption)
        if (c.uc.isNotEmpty()) CopyBlock("uc", c.uc)
    }
}

@Composable internal fun CardPanel(card: CardInfo) {
    Notice("${card.specLabel} · ${card.name} · 世界书 ${card.worldBookEntries} 条 · 备选开场白 ${card.alternateGreetings} 条")
    KeyValues(card.fields)
    if (card.tags.isNotEmpty()) CopyBlock("tags", card.tags.joinToString("、"))
    if (card.creatorNotes.isNotEmpty()) CopyBlock("creator_notes", card.creatorNotes)
    if (card.description.isNotEmpty()) CopyBlock("description", card.description)
    if (card.firstMessage.isNotEmpty()) CopyBlock("first_mes", card.firstMessage)
    if (card.example.isNotEmpty()) CopyBlock("mes_example", card.example)
    CopyBlock("角色卡 JSON", card.raw)
}

@Composable internal fun ContainerPanel(container: ContainerInfo, bytes: ByteArray?) {
    KeyValues(listOf(
        "主图结束位置" to "${container.iendEnd} 字节",
        "尾部追加数据" to formatBytes(container.trailingSize),
        "尾部类型" to (container.signature ?: "未识别的私有数据"),
        "尾部开头 hex" to container.trailingHead,
        "尾部可见字符" to container.trailingAscii,
        "私有块空隙" to container.gaps.joinToString { "${it.first}+${it.second}" }.ifEmpty { "无" },
    ))
    val embedded = container.pngs.filter { it.offset > 0 }
    SectionTitle("内嵌 PNG (${embedded.size})")
    if (embedded.isEmpty()) Notice("尾部没有找到完整的 PNG")
    embedded.forEach { item ->
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            if (bytes != null && item.end > item.offset) {
                val imageBytes = remember(bytes, item.offset, item.end) { bytes.range(item.offset, item.end) }
                ImagePreview(imageBytes, Modifier.width(110.dp).height(100.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("@${item.offset} · ${item.parsed?.width ?: "?"} × ${item.parsed?.height ?: "?"} · ${formatBytes(item.end - item.offset)}", fontWeight = FontWeight.Bold)
                item.error?.let { Text("解析失败：$it") }
                item.parsed?.let { p ->
                    Text("${item.chunks} 块 · ${p.texts.size} 条文本元数据")
                    p.texts.forEach { Text("${it.keyword}: ${it.text.take(80)}") }
                    if (p.novelAi.isNai) Text("NovelAI · ${p.novelAi.model} · seed ${p.novelAi.params?.get("seed").value()}")
                }
            }
        }
        Divider()
    }
}

@Composable internal fun RawPanel(info: ImageInfo, window: Frame) {
    info.texts.forEach { t ->
        val decoded = t.decoded
        if (decoded == null) CopyBlock("${t.chunkType} · ${t.keyword} (${t.encoding})", parseJson(t.text)?.pretty() ?: t.text)
        else {
            SectionTitle("${t.chunkType} · ${t.keyword} (${t.encoding}) · base64 → ${decoded.kind} · ${formatBytes(decoded.bytes.size)}")
            Row {
                TextButton(onClick = { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(t.text), null) }) { Text("复制 base64 原文") }
                if (decoded.kind in setOf("png", "jpeg", "webp", "zip", "binary", "compressed")) TextButton(onClick = {
                    val ext = mapOf("jpeg" to "jpg", "binary" to "bin", "compressed" to "bin")[decoded.kind] ?: decoded.kind
                    FileDialog(window, "保存解码结果", FileDialog.SAVE).apply {
                        file = "${t.keyword}.$ext"; isVisible = true
                        file?.let { runCatching { Files.write(File(directory, it).toPath(), decoded.bytes) }.onFailure { AppLog.logger.warning("保存失败：${it.message}") } }
                    }
                }) { Text("保存解码结果") }
            }
            if (decoded.kind in setOf("png", "jpeg", "webp")) ImagePreview(decoded.bytes, Modifier.fillMaxWidth().height(220.dp))
            else CopyBlock("解码内容", decoded.json?.pretty() ?: decoded.preview)
        }
        t.decodeError?.let { Notice("base64 解码失败：$it", true) }
    }
    info.a1111?.let { SectionTitle("A1111 / Forge 参数"); KeyValues(it.fields.toList()) }
    SectionTitle("容器块结构 (${info.chunks.size})")
    info.chunks.forEach { c ->
        Text("#${c.index}  ${c.type}  ${c.length} B  ${c.note}${if (c.crcOk == false) "  · CRC 异常" else ""}${c.error?.let { "  · $it" }.orEmpty()}", Modifier.padding(vertical = 3.dp))
    }
}
