package dev.towertools.stratalauncher.experimental

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.*

class EspConfigRepositoryTest {
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory(Files.createDirectories(Path.of("build/esp-tests")), "case-")
        val install = Files.createDirectories(root.resolve("local/Strata/install"))
        val roaming = Files.createDirectories(root.resolve("roaming/Strata"))
        val backups = root.resolve("backups")
        val vector = root.resolve("local/Strata/terminal/current/data/experimental-speed-projection/Qwen3.8-Flash-Next-experimental-speed-projection.gguf")
        val env = mapOf("LOCALAPPDATA" to root.resolve("local").toString(), "APPDATA" to root.resolve("roaming").toString())
        val repo = EspConfigRepository(env, backups)
        init {
            Files.createDirectories(vector.parent); Files.writeString(vector, "GGUF-test-vector")
            val support = root.resolve("local/Strata/terminal/current/strata_control/user_args.py")
            Files.createDirectories(support.parent); Files.writeString(support, "# supported")
        }
        fun config(name: String = "strata-unsloth-ud-iq4_xs.json", extra: Map<String, JsonElement> = emptyMap()): Path {
            val doc = buildJsonObject {
                put("exe", "component:strata-engine")
                put("model_name", "qwen3.8-flash-next-unsloth-ud-iq4_xs")
                put("args", JsonArray(listOf("--native", "original.gguf", "--max-context", "65536").map(::JsonPrimitive)))
                put("api_key", "private-test-key")
                put("env", buildJsonObject { put("CUSTOM", "keep-me") })
                put("sampling", buildJsonObject { put("temperature", .7) })
                extra.forEach { (key, value) -> put(key, value) }
            }
            return install.resolve(name).also { Files.writeString(it, "\uFEFF$doc") }
        }
        fun doc(path: Path) = Json.parseToJsonElement(Files.readString(path).removePrefix("\uFEFF")).jsonObject
        override fun close() { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
    private fun args(vararg values: String) = JsonArray(values.map(::JsonPrimitive))

    @Test fun discoversRegisteredInstallsAndOnlyNativeBaseConfigFiles() = Fixture().use { f ->
        f.config()
        f.config("strata-ignored.json.bak")
        f.config("strata-a.shared-settings.json")
        f.config("esp-test.json")
        val other = Files.createDirectories(f.root.resolve("other-install"))
        Files.copy(f.install.resolve("strata-unsloth-ud-iq4_xs.json"), other.resolve("strata-second.json"))
        Files.writeString(f.roaming.resolve("settings.json"), buildJsonObject {
            put("installs", JsonArray(listOf(other.toAbsolutePath().toString(), f.install.toAbsolutePath().toString()).map(::JsonPrimitive)))
        }.toString())
        assertEquals(2, f.repo.list().size)
        val explicit = EspConfigRepository(f.env + ("STRATA_INSTALL_DIR" to other.toString()), f.backups)
        assertEquals(listOf("strata-second.json"), explicit.list().map { it.path.fileName.toString() })
    }

    @Test fun enablingUsesPersistentUserArgsAndPreservesAllOtherFields() = Fixture().use { f ->
        val path = f.config(extra = mapOf("user_args" to args("--custom-option", "keep", "--vram-reserve-mib", "2048")))
        val before = f.doc(path)
        val updated = f.repo.setEnabled(f.repo.list().single(), true)
        assertTrue(updated.enabled)
        val after = f.doc(path)
        assertEquals(before.filterKeys { it != "user_args" }, after.filterKeys { it != "user_args" })
        val user = after.getValue("user_args").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("4", "44"), user.drop(user.indexOf("--control-vector-layer-range") + 1).take(2))
        assertTrue(user.containsAll(listOf("--custom-option", "keep", "--vram-reserve-mib", "2048")))
        assertTrue(user.contains(f.vector.toAbsolutePath().toString() + ":1.0"))
        f.repo.setEnabled(updated, true)
        val again = f.doc(path).getValue("user_args").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(1, again.count { it == "--control-vector-scaled" })
        Files.list(f.backups).use { files -> assertEquals(2L, files.count()) }
    }

    @Test fun disablingRemovesLegacyAndPersistentEspWithoutRevivingTheOldArgs() = Fixture().use { f ->
        val esp = listOf("--control-vector-scaled", f.vector.toString() + ":1.0", "--control-vector-layer-range", "4", "44",
            "--cvec-mode", "project", "--cvec-dir", "per-layer")
        val originalArgs = listOf("--native", "original.gguf", "--max-context", "32768") + esp
        val path = f.config(extra = mapOf("args" to args(*originalArgs.toTypedArray()),
            "args_written" to args(*originalArgs.toTypedArray()), "user_args" to args(*(listOf("--custom-option", "keep") + esp).toTypedArray())))
        val before = f.doc(path)
        Files.delete(f.vector) // Missing weights must never prevent turning the feature off.
        val disabled = f.repo.setEnabled(f.repo.list().single(), false)
        assertFalse(disabled.enabled)
        val doc = f.doc(path)
        assertEquals(args("--native", "original.gguf", "--max-context", "32768"), doc["args"])
        assertEquals(args("--custom-option", "keep"), doc["user_args"])
        assertEquals(before["args_written"], doc["args_written"])
        assertEquals(before["api_key"], doc["api_key"])
    }

    @Test fun staleOrInvalidConfigsCannotBeOverwrittenAndErrorsDoNotExposeJson() = Fixture().use { f ->
        val path = f.config()
        val stale = f.repo.list().single()
        Files.writeString(path, "{\"api_key\":\"do-not-show-this-secret\", broken")
        val bytes = Files.readAllBytes(path)
        assertFailsWith<IllegalArgumentException> { f.repo.setEnabled(stale, true) }
        assertContentEquals(bytes, Files.readAllBytes(path))
        val invalid = f.repo.list().single()
        assertNotNull(invalid.error)
        assertFalse(invalid.error.contains("do-not-show"))
        assertFalse(Files.exists(f.backups))
    }

    @Test fun unknownVectorsAndOtherModelsAreNotSilentlyReplaced(): Unit = Fixture().use { f ->
        val custom = f.config(extra = mapOf("user_args" to args("--control-vector-scaled", "custom.gguf:0.7")))
        val entry = f.repo.read(custom)
        assertNotNull(entry.unavailable)
        assertFailsWith<IllegalArgumentException> { f.repo.setEnabled(entry, true) }
        assertEquals(args("--control-vector-scaled", "custom.gguf:0.7"), f.doc(custom)["user_args"])
        val swift = f.config("strata-swift.json", mapOf("model_name" to JsonPrimitive("swift-1.5-qwen3.8-flash-next")))
        assertNotNull(f.repo.read(swift).unavailable)
    }

    @Test fun missingVectorBlocksEnablingAndNonStringArgsAreRejected() = Fixture().use { f ->
        val path = f.config()
        Files.delete(f.vector)
        assertNotNull(f.repo.read(path).unavailable)
        assertFailsWith<IllegalArgumentException> { f.repo.setEnabled(f.repo.read(path), true) }
        f.config(extra = mapOf("user_args" to JsonArray(listOf(JsonPrimitive(42)))))
        assertEquals("user_args 必须是字符串数组。", f.repo.read(path).error)
    }

    @Test fun disablingPreservesCustomVectorHiddenByUserOverride() = Fixture().use { f ->
        val base = args("--native", "model.gguf", "--control-vector-scaled", "custom.gguf:0.7", "--cvec-mode", "custom-mode")
        val path = f.config(extra = mapOf("args" to base, "user_args" to args(
            "--control-vector-scaled", f.vector.toString() + ":1.0", "--cvec-mode", "project")))
        val before = Files.readAllBytes(path)
        f.repo.setEnabled(f.repo.read(path), false)
        assertEquals(base, f.doc(path)["args"])
        assertEquals(args(), f.doc(path)["user_args"])
        Files.list(f.backups).use { assertContentEquals(before, Files.readAllBytes(it.findFirst().orElseThrow())) }
    }

    @Test fun ambiguousVectorGroupIsRejectedWithoutWritingOrBackup() = Fixture().use { f ->
        val path = f.config(extra = mapOf("user_args" to args("--control-vector-scaled",
            f.vector.toString() + ":1.0", "custom.gguf:0.7")))
        val before = Files.readAllBytes(path)
        assertFailsWith<IllegalArgumentException> { f.repo.setEnabled(f.repo.read(path), false) }
        assertContentEquals(before, Files.readAllBytes(path))
        assertFalse(Files.exists(f.backups))
    }
}
