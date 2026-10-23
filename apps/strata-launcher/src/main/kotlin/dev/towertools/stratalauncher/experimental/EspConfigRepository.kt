package dev.towertools.stratalauncher.experimental

import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.*
import java.nio.file.attribute.AclFileAttributeView
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

data class EspConfigEntry(
    val path: Path,
    val model: String,
    val enabled: Boolean = false,
    val revision: String = "",
    val vector: Path? = null,
    val error: String? = null,
    val unavailable: String? = null,
)

/** Edits only ESP argument groups; native Strata remains responsible for starting the model. */
class EspConfigRepository(
    private val environment: Map<String, String> = System.getenv(),
    private val backupDirectory: Path,
) {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }
    private val vectorName = "Qwen3.8-Flash-Next-experimental-speed-projection.gguf"

    private fun env(key: String) = environment.entries.firstOrNull { it.key.equals(key, true) }?.value?.takeIf(String::isNotBlank)
    private fun expand(value: String): Path {
        val home = env("USERPROFILE") ?: System.getProperty("user.home")
        return when {
            value == "~" -> Path.of(home)
            value.startsWith("~/") || value.startsWith("~\\") -> Path.of(home).resolve(value.substring(2))
            else -> Path.of(value)
        }.toAbsolutePath().normalize()
    }
    private fun localRoot(): Path = env("STRATA_COMPONENTS_DIR")?.let { expand(it).parent }
        ?: (env("LOCALAPPDATA")?.let(::expand) ?: Path.of(System.getProperty("user.home"), "AppData", "Local")).resolve("Strata")

    fun installationDirectories(): List<Path> {
        env("STRATA_INSTALL_DIR")?.let { return listOf(expand(it)) }
        val settings = env("STRATA_SETTINGS_FILE")?.let(::expand)
            ?: (env("APPDATA")?.let(::expand) ?: Path.of(System.getProperty("user.home"), "AppData", "Roaming"))
                .resolve("Strata/settings.json")
        val result = linkedSetOf<Path>()
        for (file in listOf(settings, localRoot().resolve("app-settings.json"))) {
            if (Files.isRegularFile(file)) {
                val doc = readDocument(file)
                (doc["installs"] as? JsonArray)?.forEach {
                    val value = (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content.orEmpty()
                    if (value.isNotBlank()) result.add(expand(value))
                }
            }
        }
        result.add(localRoot().resolve("install").toAbsolutePath().normalize())
        return result.toList()
    }

    fun list(): List<EspConfigEntry> = installationDirectories().flatMap { directory ->
        if (!Files.exists(directory)) emptyList() else {
            require(Files.isDirectory(directory)) { "基础配置位置不是文件夹。" }
            Files.list(directory).use { stream ->
                stream.filter { isBaseConfig(it) && Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .map { read(it) }.toList()
            }
        }
    }.distinctBy { it.path.toAbsolutePath().normalize() }.sortedBy { it.path.toString().lowercase() }

    fun read(path: Path): EspConfigEntry = try {
        val bytes = readBytes(path)
        val doc = parse(bytes)
        arguments(doc, "args", required = true)
        val effective = merge(arguments(doc, "args"), arguments(doc, "user_args"))
        val vectors = groups(effective).filter { it.firstOrNull() in setOf("--control-vector", "--control-vector-scaled") }
        val enabled = vectors.any(::isEspVector)
        val model = (doc["model_name"] as? JsonPrimitive)?.contentOrNull ?: path.fileName.toString()
        val compatible = model.contains("qwen3.8", true) && model.contains("flash-next", true) && !model.contains("swift", true)
        val vector = findVector(doc, effective)
        EspConfigEntry(path, model, enabled, digest(bytes), vector,
            unavailable = when {
                !compatible -> "此向量适用于原版 Qwen3.8-Flash-Next / Coder。"
                !supportsUserArgs(doc) -> "请先更新 Strata Terminal，以支持持久化 ESP 参数。"
                vector == null -> "未找到 ESP 向量文件，请先安装新版 Strata Terminal。"
                vectors.any { !isEspVector(it) } -> "已有其他控制向量，不能直接叠加 ESP。"
                else -> null
            })
    } catch (error: Exception) {
        EspConfigEntry(path, path.fileName.toString(), error = when (error) {
            is IllegalArgumentException -> error.message ?: "配置格式无效。"
            else -> "无法读取配置，请检查文件和访问权限。"
        })
    }

    fun setEnabled(entry: EspConfigEntry, enabled: Boolean): EspConfigEntry {
        val path = entry.path.toAbsolutePath().normalize()
        require(isBaseConfig(path) && path.parent in installationDirectories()) { "不是已发现的 Strata 基础配置。" }
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "基础配置不是普通文件。" }
        val original = readBytes(path)
        require(digest(original) == entry.revision) { "配置已被其他程序修改，请刷新后重试。" }
        val current = read(path)
        require(current.error == null) { current.error ?: "无法读取配置。" }
        if (enabled) require(current.unavailable == null) { current.unavailable ?: "无法启用 ESP。" }
        val doc = parse(original)
        val values = doc.toMutableMap()
        val args = arguments(doc, "args", required = true)
        val user = arguments(doc, "user_args")
        if (enabled) {
            val vector = requireNotNull(current.vector)
            val esp = listOf("--control-vector-scaled", "$vector:1.0", "--control-vector-layer-range", "4", "44",
                "--cvec-mode", "project", "--cvec-dir", "per-layer")
            values["user_args"] = strings(merge(user, esp))
        } else {
            // Remove ESP from both layers: an older copy in args must not become active again.
            fun strip(input: List<String>) = EspArguments.disable(input)
            values["args"] = strings(strip(args))
            values["user_args"] = strings(strip(user))
        }
        val output = (json.encodeToString(JsonObject.serializer(), JsonObject(values)) + "\n").toByteArray(Charsets.UTF_8)
        Files.createDirectories(backupDirectory)
        val backup = backupDirectory.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) +
            "-" + UUID.randomUUID().toString().take(8) + "-" + path.fileName)
        // Copy permissions as well as content, since a native config can contain an API key.
        Files.copy(path, backup, StandardCopyOption.COPY_ATTRIBUTES)
        val temp = Files.createTempFile(path.parent, ".esp-", ".tmp")
        try {
            Files.copy(path, temp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
            if (acl != null) Files.getFileAttributeView(temp, AclFileAttributeView::class.java)?.acl = acl.acl
            if (acl != null) Files.getFileAttributeView(backup, AclFileAttributeView::class.java)?.acl = acl.acl
            Files.write(temp, output, StandardOpenOption.TRUNCATE_EXISTING)
            require(readBytes(path).contentEquals(original)) { "配置已被其他程序修改，请刷新后重试。" }
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temp) }
        return read(path)
    }

    private fun isBaseConfig(path: Path): Boolean {
        val name = path.fileName.toString().lowercase()
        return name.startsWith("strata-") && name.endsWith(".json") && !name.endsWith(".shared-settings.json")
    }
    private fun readBytes(path: Path): ByteArray = Files.newInputStream(path).use {
        it.readNBytes(1024 * 1024 + 1).also { bytes -> require(bytes.size <= 1024 * 1024) { "配置超过 1 MiB，无法编辑。" } }
    }
    private fun parse(bytes: ByteArray): JsonObject {
        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF") }
        catch (_: Exception) { throw IllegalArgumentException("配置不是有效的 UTF-8 文本。") }
        return try { Json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException() }
        catch (_: IllegalArgumentException) { throw IllegalArgumentException("JSON 格式不正确，或根节点不是对象。") }
    }
    private fun readDocument(path: Path) = parse(readBytes(path))
    private fun arguments(doc: JsonObject, key: String, required: Boolean = false): List<String> {
        val raw = doc[key]
        if (raw == null && !required) return emptyList()
        require(raw is JsonArray && raw.all { it is JsonPrimitive && it.isString }) { "$key 必须是字符串数组。" }
        return raw.map { (it as JsonPrimitive).content }
    }
    private fun groups(args: List<String>): List<List<String>> {
        val result = mutableListOf<MutableList<String>>()
        args.forEach { token ->
            if (token.startsWith("--") || result.isEmpty()) result += mutableListOf(token) else result.last() += token
        }
        return result
    }
    private fun merge(args: List<String>, user: List<String>): List<String> {
        val own = groups(user)
        val keys = own.map { it.first() }.toSet()
        return (groups(args).filterNot { it.first() in keys } + own).flatten()
    }
    private fun isEspVector(group: List<String>): Boolean = group.firstOrNull() in setOf("--control-vector", "--control-vector-scaled") &&
        group.drop(1).any { it.contains(vectorName, ignoreCase = true) }
    private fun supportsUserArgs(doc: JsonObject): Boolean {
        if (Files.isRegularFile(localRoot().resolve("terminal/current/strata_control/user_args.py"))) return true
        val cwd = (doc["cwd"] as? JsonPrimitive)?.contentOrNull ?: return false
        return Files.isRegularFile(expand(cwd).resolve("strata_control/user_args.py"))
    }
    private fun findVector(doc: JsonObject, args: List<String>): Path? {
        val candidates = mutableListOf<Path>()
        groups(args).filter(::isEspVector).flatMap { it.drop(1) }.forEach { value ->
            val path = value.replace(Regex(":[-+]?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?$"), "")
            runCatching { expand(path) }.getOrNull()?.let(candidates::add)
        }
        candidates.add(localRoot().resolve("terminal/current/data/experimental-speed-projection/$vectorName"))
        (doc["cwd"] as? JsonPrimitive)?.contentOrNull?.let { cwd ->
            candidates.add(expand(cwd).resolve("data/experimental-speed-projection/$vectorName"))
        }
        return candidates.firstOrNull { path ->
            Files.isRegularFile(path) && runCatching { Files.newInputStream(path).use { it.readNBytes(4).contentEquals(byteArrayOf(71, 71, 85, 70)) } }.getOrDefault(false)
        }
    }
    private fun strings(args: List<String>) = JsonArray(args.map(::JsonPrimitive))
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
