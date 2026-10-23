package dev.towertools.stratalauncher

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

private val modelCatalog = mapOf(
    "unsloth" to listOf("ud-iq4_xs", "ud-q4_k_xl", "ud-q5_k_xl", "ud-q6_k_xl", "q8_0"),
    "qwen" to listOf("q2_0", "iq2_xs", "iq3_xxs", "iq3_s"),
    "swift" to listOf("iq2_xs", "iq3_xxs", "iq3_s"),
    "coder" to listOf("iq1_m"),
    "gyro" to listOf("gyro-s", "gyro-m"),
    "nvfp4" to listOf("nvfp4", "nvfp4-nvidia", "nvfp4-qad"),
    "qwen36" to listOf("ud-q4_k_xl", "ud-q6_k_xl", "q8_0"),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LaunchForm(
    config: LaunchConfig,
    onChange: (LaunchConfig) -> Unit,
    onChooseDataDir: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 760.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ModelFields(config, onChange, onChooseDataDir)
                        AccessFields(config, onChange)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ResourceFields(config, onChange)
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModelFields(config, onChange, onChooseDataDir)
                    ResourceFields(config, onChange)
                    AccessFields(config, onChange)
                }
            }
        }
    }
}

@Composable
private fun ModelFields(config: LaunchConfig, onChange: (LaunchConfig) -> Unit, chooseDirectory: () -> Unit) {
    StrataSection("模型") {
        StrataChoice("模型系列", config.family, listOf(
            "unsloth" to "Unsloth · 动态量化", "qwen" to "Qwen · 原版量化",
            "swift" to "Swift · 短思考", "coder" to "Coder · 半专家", "gyro" to "Gyro",
            "nvfp4" to "NVFP4", "qwen36" to "Qwen3.6 · 35B", "" to "自动识别",
        ), "field-family") { family ->
            onChange(config.copy(family = family, model = modelCatalog[family]?.firstOrNull() ?: config.model))
        }
        StrataEditableChoice("量化版本", config.model, modelCatalog[config.family].orEmpty(), "field-model") {
            onChange(config.copy(model = it))
        }
        StrataTextField("数据目录", config.dataDir, { onChange(config.copy(dataDir = it)) }, "field-dataDir",
            "留空使用 Strata 默认目录",
            trailing = { TextButton(onClick = chooseDirectory, contentPadding = PaddingValues(6.dp)) { Text("选择") } })
    }
}

@Composable
private fun AccessFields(config: LaunchConfig, onChange: (LaunchConfig) -> Unit) {
    var keyVisible by remember { mutableStateOf(false) }
    StrataSection("服务 / 访问") {
        StrataChoice("访问范围", config.access, listOf("local" to "仅本机 · local", "lan" to "局域网 · lan",
            "cloudflare" to "Cloudflare", "tailscale" to "Tailscale"), "field-access") { onChange(config.copy(access = it)) }
        StrataTextField("端口", config.port, { onChange(config.copy(port = it)) }, "field-port", "留空使用默认端口")
        val port = config.port.toIntOrNull()?.takeIf { it in 1..65535 }
        Text(if (port == null) "连接地址由 Strata 决定" else "本机地址预览：http://127.0.0.1:$port   ·   API：/v1",
            style = MaterialTheme.typography.caption)
        StrataChoice("密钥来源", config.keyMode, listOf("none" to "由 Strata 处理",
            "new" to "生成新密钥", "literal" to "直接填写"), "field-keyMode") { onChange(config.copy(keyMode = it)) }
        when (config.keyMode) {
            "literal" -> {
                StrataTextField("API Key", config.apiKey, { onChange(config.copy(apiKey = it)) }, "field-apiKey", "仅本次使用",
                    secret = !keyVisible, trailing = {
                        TextButton(onClick = { keyVisible = !keyVisible }, contentPadding = PaddingValues(6.dp), modifier = Modifier.testTag("toggle-apiKey")) {
                            Text(if (keyVisible) "隐藏" else "显示")
                        }
                    })
                Text("密钥仅在本次打开期间保留，会出现在生成命令、剪贴板与终端历史中。", color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption)
            }
        }
        StrataTextField("保存预设名称（可选）", config.savePreset, { onChange(config.copy(savePreset = it)) }, "field-savePreset", "执行命令后保存并启动")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResourceFields(config: LaunchConfig, onChange: (LaunchConfig) -> Unit) {
    StrataSection("推理 / 资源") {
        StrataTextField("上下文", config.context, { onChange(config.copy(context = it)) }, "field-context", "例如 128k；留空使用默认值")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            listOf("32k", "64k", "128k", "200k", "256k", "512k").forEach { value ->
                StrataSelectionButton(value.uppercase(), config.context.equals(value, true), "context-$value") { onChange(config.copy(context = value)) }
            }
        }
        StrataChoice("图片理解", config.vision, listOf("off" to "关闭", "on" to "开启 · 自动", "gpu" to "开启 · GPU", "cpu" to "开启 · CPU"), "field-vision") { onChange(config.copy(vision = it)) }
        StrataChoice("并发数", config.parallel, (1..8).map { it.toString() to it.toString() }, "field-parallel") { onChange(config.copy(parallel = it)) }
        StrataChoice("KV 格式", config.kv, listOf("int8" to "INT8", "k8v4" to "K8V4", "q4_0" to "Q4_0", "fp16" to "FP16"), "field-kv") { onChange(config.copy(kv = it)) }
        StrataChoice("显存预留", config.vramReserve, listOf("1024" to "1 GB", "2048" to "2 GB", "3072" to "3 GB", "4096" to "4 GB", "6144" to "6 GB", "8192" to "8 GB"), "field-vramReserve") { onChange(config.copy(vramReserve = it)) }
        StrataChoice("性能调优", config.tune, listOf("skip" to "跳过 · skip", "quick" to "Quick", "thorough" to "Thorough"), "field-tune") { onChange(config.copy(tune = it)) }
        StrataTextField("GPU 编号", config.gpu, { onChange(config.copy(gpu = it)) }, "field-gpu", "自动，或 0 / 0,1")
    }
}

@Composable
internal fun StrataSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, MaterialTheme.colors.onSurface.copy(alpha = .16f))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.subtitle2, fontWeight = FontWeight.SemiBold)
            Divider(color = MaterialTheme.colors.onSurface.copy(alpha = .10f))
            content()
        }
    }
}

@Composable
internal fun StrataTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    tag: String,
    placeholder: String = "",
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(value, onChange, modifier.fillMaxWidth().testTag(tag), label = { Text(label) },
        placeholder = { Text(placeholder, style = MaterialTheme.typography.body2) }, singleLine = true,
        textStyle = MaterialTheme.typography.body2,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = trailing)
}

@Composable
internal fun StrataChoice(label: String, value: String, options: List<Pair<String, String>>, tag: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.caption, color = MaterialTheme.colors.onSurface.copy(alpha = .70f))
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 38.dp).testTag(tag), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                Text(options.firstOrNull { it.first == value }?.second ?: value, modifier = Modifier.weight(1f), color = MaterialTheme.colors.onSurface, style = MaterialTheme.typography.body2)
                StrataDropdownArrow()
            }
            DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                options.forEach { (key, title) ->
                    DropdownMenuItem(onClick = { expanded = false; onChange(key) }, modifier = Modifier.testTag("$tag-option-${key.ifEmpty { "default" }}")) {
                        Text(title, style = MaterialTheme.typography.body2)
                    }
                }
            }
        }
    }
}

@Composable
internal fun StrataEditableChoice(label: String, value: String, options: List<String>, tag: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        StrataTextField(label, value, onChange, tag, "可输入名称", trailing = {
            IconButton(onClick = { expanded = true }, enabled = options.isNotEmpty(),
                modifier = Modifier.testTag("$tag-list").semantics { contentDescription = "展开${label}选项" }) {
                StrataDropdownArrow()
            }
        })
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            options.distinct().forEach { option ->
                DropdownMenuItem(onClick = { expanded = false; onChange(option) }) { Text(option) }
            }
        }
    }
}

@Composable
internal fun StrataDropdownArrow() {
    val color = LocalContentColor.current.copy(alpha = LocalContentAlpha.current)
    Canvas(Modifier.size(24.dp)) {
        drawPath(Path().apply {
            moveTo(size.width * .3f, size.height * .4f)
            lineTo(size.width * .7f, size.height * .4f)
            lineTo(size.width * .5f, size.height * .6f)
            close()
        }, color)
    }
}

@Composable
internal fun StrataSelectionButton(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    OutlinedButton(onClick, modifier = Modifier.testTag(tag), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
        colors = ButtonDefaults.outlinedButtonColors(backgroundColor = if (selected) MaterialTheme.colors.primary.copy(alpha = .12f) else MaterialTheme.colors.surface),
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha = .20f))) {
        Text(label, style = MaterialTheme.typography.body2)
    }
}
