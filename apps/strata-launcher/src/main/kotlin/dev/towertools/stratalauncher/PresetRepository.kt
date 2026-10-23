package dev.towertools.stratalauncher

import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.serialization.json.*

data class PresetEntry(val path: Path, val name: String, val error: String? = null)

/** Lists and reads native presets without modifying the files. */
object PresetRepository {
    internal const val MAX_PRESET_BYTES = 128 * 1024

    fun defaultDirectory(environment: Map<String, String> = System.getenv()): Path {
        fun env(name: String) = environment.entries.firstOrNull { it.key.equals(name, true) }
            ?.value?.takeIf(String::isNotBlank)
        fun expanded(value: String): Path {
            val home = env("USERPROFILE") ?: System.getProperty("user.home")
            val path = when {
                value == "~" -> Paths.get(home)
                value.startsWith("~/") || value.startsWith("~\\") -> Paths.get(home).resolve(value.substring(2))
                else -> Paths.get(value)
            }
            return path.toAbsolutePath().normalize()
        }
        env("STRATA_LAUNCH_PRESET_DIR")?.let { return expanded(it) }
        env("STRATA_PROFILE_DIR")?.let {
            val profiles = expanded(it)
            return (profiles.parent ?: profiles).resolve("launch-presets")
        }
        val roaming = env("APPDATA")?.let(Paths::get)
            ?: Paths.get(System.getProperty("user.home"), "AppData", "Roaming")
        return roaming.resolve("Strata").resolve("launch-presets")
    }

    fun list(directory: Path): List<PresetEntry> {
        require(Files.isDirectory(directory)) { "预设目录不存在或不是文件夹，请选择有效目录。" }
        return try {
            Files.list(directory).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json", ignoreCase = true) }
                    .sorted(compareBy<Path> { it.fileName.toString().lowercase() })
                    .map { path ->
                        try {
                            PresetEntry(path, readName(path))
                        } catch (e: IllegalArgumentException) {
                            PresetEntry(path, path.fileName.toString(), e.message ?: "预设格式不正确。")
                        } catch (_: Exception) {
                            PresetEntry(path, path.fileName.toString(), "无法读取此预设文件。")
                        }
                    }.toList()
            }
        } catch (_: java.io.IOException) {
            throw IllegalArgumentException("无法访问预设目录，请检查访问权限。")
        }
    }

    /** Only the identity is needed for listing and launching; model parameters stay in Strata's file. */
    fun readName(path: Path): String {
        val text = readPresetText(path).removePrefix("\uFEFF")
        val doc = try {
            Json.parseToJsonElement(text) as? JsonObject
                ?: throw IllegalArgumentException()
        } catch (error: IllegalArgumentException) {
            // Parser exceptions can contain source excerpts, including API keys.
            throw IllegalArgumentException("JSON 格式不正确，或根节点不是对象。")
        }
        require((doc["schema"] as? JsonPrimitive)?.contentOrNull == "strata.launch/1") {
            "请选择 Strata 原生启动预设 JSON（strata.launch/1）。"
        }
        val rawName = doc["name"]
        require(rawName == null || rawName == JsonNull || rawName is JsonPrimitive && rawName.isString) {
            "预设名称必须是文本。"
        }
        val value = (rawName as? JsonPrimitive)?.contentOrNull.orEmpty()
        require(value.length <= 8192 && value.none { it.code in 0..31 || it.code == 127 }) {
            "预设名称含控制字符或超过 8192 字符。"
        }
        val name = value.trim().ifEmpty {
            path.fileName.toString().replace(Regex("\\.json$", RegexOption.IGNORE_CASE), "")
        }
        require(name.isNotBlank() && name.length <= 8192 && name.none { it.code in 0..31 || it.code == 127 }) {
            "预设缺少有效名称，或名称含控制字符、超过 8192 字符。"
        }
        return name
    }

    private fun readPresetText(path: Path): String {
        val bytes = try {
            Files.newInputStream(path).use { it.readNBytes(MAX_PRESET_BYTES + 1) }
        } catch (_: Exception) {
            throw IllegalArgumentException("无法读取文件，请检查文件是否存在及访问权限。")
        }
        require(bytes.size <= MAX_PRESET_BYTES) { "预设文件不能超过 128 KB。" }
        return decodeUtf8(bytes)
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        throw IllegalArgumentException("文件不是有效的 UTF-8 文本。")
    }
}
