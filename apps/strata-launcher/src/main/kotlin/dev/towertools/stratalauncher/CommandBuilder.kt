package dev.towertools.stratalauncher

import java.util.Locale

data class CommandResult(
    val command: String = "",
    val tokens: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val invocation: String = command,
    val environment: Map<String, String> = emptyMap(),
)

/** Produces text and argument tokens only; never starts Strata or any shell. */
object CommandBuilder {
    /** The preset list must not inherit any defaults or overrides from the launch forms. */
    fun preset(name: String): CommandResult = presetTarget(name, "--preset")

    fun deletePreset(name: String): CommandResult = presetTarget(name, "--delete-preset")

    private fun presetTarget(name: String, flag: String): CommandResult {
        if (name.isBlank()) return CommandResult(errors = listOf("请选择预设。"))
        return try {
            require(name.length <= 8192) { "预设名称不能超过 8192 个字符。" }
            val argument = quote(name)
            val tokens = if (name.startsWith('-')) listOf("strata", "launch", "$flag=$name")
                else listOf("strata", "launch", flag, name)
            val command = if (name.startsWith('-')) "strata launch $flag=$argument"
                else "strata launch $flag $argument"
            CmdCommand.validate(CommandResult(command = command, tokens = tokens))
        } catch (error: IllegalArgumentException) {
            CommandResult(errors = listOf(error.message ?: "预设名称无法用于命令。"))
        }
    }

    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    private val safeArgument = Regex("""[A-Za-z0-9_./:\\,=+\-]+""")
    private val digits = Regex("[0-9]+")
    private val contextSize = Regex("([1-9][0-9]*)([kKmM])?")
    private val gpuList = Regex("""[0-9]+(\s*,\s*[0-9]+)*""")
    private val actions = setOf("launch", "status", "stop", "list-presets", "show-key")

    fun quote(value: String): String {
        require(!hasControl(value)) { "参数不能包含换行、NUL 或其他控制字符。" }
        require(value.none { it == '"' || it == '%' || it == '!' }) {
            "CMD 无法可靠传递双引号、百分号或感叹号；请移除这些字符。"
        }
        if (safeArgument.matches(value)) return value
        // The Windows argv parser doubles trailing backslashes inside a quoted argument.
        return "\"$value${value.takeLastWhile { it == '\\' }}\""
    }

    fun management(config: ManagementConfig): CommandResult {
        val action = config.action.trim()
        val scope = config.scope.trim()
        val targetRequired = action == "show-key" || (action == "stop" && scope == "preset")
        val result = build(
            LaunchConfig(
                action = action,
                preset = if (targetRequired) config.preset else "",
            ),
        )
        val errors = result.errors.toMutableList()
        if (action !in actions || action == "launch") errors += "请选择有效的管理操作。"
        if (scope !in setOf("preset", "all")) errors += "请选择指定预设或所有 launch 服务。"
        val managementFields = listOf(config.action, config.scope, config.preset)
        if (managementFields.any(::hasControl)) {
            errors += "所有字段均不能包含换行、NUL 或其他控制字符。"
        }
        if (managementFields.any { it.length > 8192 }) errors += "文本字段不能超过 8192 个字符。"
        if (targetRequired && config.preset.isBlank()) errors += "请填写目标预设名称。"
        return if (errors.isEmpty()) result else result.copy(
            command = "",
            tokens = emptyList(),
            errors = errors.distinct(),
        )
    }

    fun build(config: LaunchConfig): CommandResult {
        val errors = linkedSetOf<String>()
        val warnings = linkedSetOf<String>()
        val groups = mutableListOf(listOf("strata", "launch"))
        fun error(message: String) { errors += message }
        fun warn(message: String) { warnings += message }
        fun flag(name: String) { groups += listOf(name) }
        fun pair(name: String, value: String) {
            // A leading dash is an argparse option even when the shell quotes it.
            groups += if (value.startsWith('-')) listOf("$name=$value") else listOf(name, value)
        }
        fun optional(name: String, value: String) {
            if (value.isNotBlank()) pair(name, value.trim())
        }
        fun choice(value: String, label: String, options: Set<String>, allowEmpty: Boolean = false): String {
            val text = value.trim()
            if (!(allowEmpty && text.isEmpty()) && text !in options) error("“$label”请选择有效选项。")
            return text
        }
        fun integer(value: String, label: String, low: Long, high: Long): Long? {
            val text = value.trim()
            if (text.isEmpty()) return null
            val number = text.toLongOrNull()
            if (!digits.matches(text) || number == null || number !in low..high) {
                error("${label}需为 $low～$high 的整数。")
                return null
            }
            return number
        }
        fun result(command: String = "", tokens: List<String> = emptyList()) = CommandResult(
            command, tokens, errors.toList(), warnings.toList(),
        )

        val textFields = listOf(
            config.action, config.family, config.model,
            config.preset, config.dataDir, config.context, config.kv, config.vision, config.parallel,
            config.gpu, config.tune, config.access, config.port, config.keyMode, config.apiKey,
            config.savePreset, config.vramReserve,
        )
        if (textFields.any(::hasControl)) error("所有字段均不能包含换行、NUL 或其他控制字符。")
        if (textFields.any { it.length > 8192 }) error("文本字段不能超过 8192 个字符。")

        val action = choice(config.action, "操作", actions)
        if (action != "launch") {
            if (action in actions) flag("--$action")
            if (action == "stop" || action == "show-key") optional("--preset", config.preset)
            if (action == "show-key" && config.preset.isBlank()) error("请填写目标预设名称。")
        } else {
            if (config.model.isBlank()) error("请填写模型名称。") else pair("--model", config.model.trim())
            optional("--family", config.family)
            optional("--data-dir", config.dataDir)
            val context = config.context.trim()
            if (context.isNotEmpty()) {
                val match = contextSize.matchEntire(context)
                val number = match?.groupValues?.get(1)?.toLongOrNull()
                val multiplier = when (match?.groupValues?.get(2)?.lowercase(Locale.ROOT)) {
                    "k" -> 1024L
                    "m" -> 1_048_576L
                    else -> 1L
                }
                val tokens = number?.takeIf { it <= MAX_SAFE_INTEGER / multiplier }?.times(multiplier)
                if (tokens == null || tokens < 1024) error("上下文需为至少 1024 的整数 token，或 32k、64k 等大小。")
                else {
                    pair("--context", context.lowercase(Locale.ROOT))
                    if (tokens > 262144) warn("超过 256k 的上下文属于扩展范围，会增加内存占用并可能影响回答质量。")
                }
            }
            val kv = choice(config.kv, "KV 格式", setOf("int8", "q4_0", "k8v4", "fp16"), true)
            if (kv.isNotEmpty()) pair("--kv", kv)
            val vision = choice(config.vision, "图片理解", setOf("off", "on", "gpu", "cpu"), true)
            if (vision.isNotEmpty()) pair("--vision", vision)
            val parallel = integer(config.parallel, "并发数", 1, 8)
            if (parallel != null) pair("--parallel", parallel.toString())
            if (parallel != null && parallel > 1) warn("提高并发数会增加对话缓存的内存占用。")
            val gpu = config.gpu.trim()
            if (gpu.isNotEmpty()) {
                val ids = gpu.split(',').map { it.trim().toLongOrNull() }
                if (!gpuList.matches(gpu) || ids.any { it == null || it !in 0..MAX_SAFE_INTEGER }) {
                    error("GPU 编号请写成 0 或 0,1 等逗号分隔的非负整数。")
                } else {
                    if (ids.distinct().size != ids.size) error("GPU 编号不能重复。")
                    pair("--gpu", ids.joinToString(","))
                }
            }
            val tune = choice(config.tune, "调优", setOf("quick", "thorough", "skip"), true)
            if (tune.isNotEmpty()) pair("--tune", tune)
            val access = choice(config.access, "访问范围", setOf("local", "lan", "cloudflare", "tailscale"), true)
            if (access.isNotEmpty()) pair("--access", access)
            integer(config.port, "端口", 1, 65535)?.let { pair("--port", it.toString()) }
            when (choice(config.keyMode, "密钥方式", setOf("none", "literal", "new"))) {
                "literal" -> {
                    if (config.apiKey.isBlank()) error("请填写 API 密钥，或选择其他密钥方式。")
                    else pair("--api-key", config.apiKey)
                    warn("明文密钥会出现在复制的命令和终端历史中。")
                }
                "new" -> flag("--new-key")
            }
            optional("--save-preset", config.savePreset)
            integer(config.vramReserve, "显存预留 MiB", 0, MAX_SAFE_INTEGER)?.let {
                pair("--set", "vram-reserve-mib=$it")
            }
        }
        if (errors.isNotEmpty()) return result()
        val rendered = try {
            groups.map { group -> group.joinToString(" ") { quote(it) } }
        } catch (failure: IllegalArgumentException) {
            error(failure.message ?: "无法生成命令。")
            return result()
        }
        return CmdCommand.validate(result(rendered.joinToString(" "), groups.flatten()))
    }

    private fun hasControl(value: String): Boolean = value.any { it.code in 0..31 || it.code == 127 }
}
