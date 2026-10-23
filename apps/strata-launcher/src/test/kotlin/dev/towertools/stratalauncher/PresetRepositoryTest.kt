package dev.towertools.stratalauncher

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class PresetRepositoryTest {
    @TempDir lateinit var temp: Path

    @Test fun directoryOverridesFollowStrataPrecedence() {
        val explicit = temp.resolve("explicit")
        val profiles = temp.resolve("custom").resolve("profiles")
        val appData = temp.resolve("roaming")
        assertEquals(explicit, PresetRepository.defaultDirectory(mapOf(
            "STRATA_LAUNCH_PRESET_DIR" to explicit.toString(), "STRATA_PROFILE_DIR" to profiles.toString(), "APPDATA" to appData.toString())))
        assertEquals(profiles.parent.resolve("launch-presets"), PresetRepository.defaultDirectory(mapOf(
            "STRATA_PROFILE_DIR" to profiles.toString(), "APPDATA" to appData.toString())))
        assertEquals(appData.resolve("Strata").resolve("launch-presets"), PresetRepository.defaultDirectory(mapOf("APPDATA" to appData.toString())))
        assertEquals(temp.resolve("custom-presets"), PresetRepository.defaultDirectory(mapOf(
            "STRATA_LAUNCH_PRESET_DIR" to "~/custom-presets", "USERPROFILE" to temp.toString())))
    }

    @Test fun aBrokenPresetDoesNotHideOtherFilesAndNoNativeFileIsChanged() {
        val valid = temp.resolve("good.json")
        val original = """{"schema":"strata.launch/1","name":"真实预设","context":131072,"api_key":"FAKE-PRIVATE-KEY"}"""
        Files.writeString(valid, original)
        Files.writeString(temp.resolve("broken.json"), "{ invalid secret-marker")
        Files.writeString(temp.resolve("ignore.txt"), "not a preset")
        val entries = PresetRepository.list(temp)
        assertEquals(2, entries.size)
        assertEquals("真实预设", entries.first { it.path == valid }.name)
        assertNull(entries.first { it.path == valid }.error)
        assertNotNull(entries.first { it.path.fileName.toString() == "broken.json" }.error)
        assertFalse(entries.toString().contains("FAKE-PRIVATE"))
        assertFalse(entries.toString().contains("secret-marker"))
        assertEquals("真实预设", PresetRepository.readName(valid))
        assertEquals(original, Files.readString(valid))
    }

    @Test fun readingEnforcesSizeEncodingAndNativeSchema() {
        val path = temp.resolve("file.json")
        Files.write(path, ByteArray(PresetRepository.MAX_PRESET_BYTES + 1))
        assertFailsWith<IllegalArgumentException> { PresetRepository.readName(path) }
        Files.write(path, byteArrayOf(0xC3.toByte(), 0x28))
        assertFailsWith<IllegalArgumentException> { PresetRepository.readName(path) }
        Files.writeString(path, """{"schema":"strata-launch-builder/1","config":{}}""")
        assertFailsWith<IllegalArgumentException> { PresetRepository.readName(path) }
    }

    @Test fun missingDirectoryIsAnActionableError() {
        val error = assertFailsWith<IllegalArgumentException> { PresetRepository.list(temp.resolve("absent")) }
        assertContains(error.message.orEmpty(), "预设目录")
    }

    @Test fun namesSupportUtf8BomAndFilenameFallbackWithoutLoadingModelParameters() {
        val path = temp.resolve("fallback.JSON")
        Files.writeString(path, "\uFEFF" + """{"schema":"strata.launch/1","name":null}""")
        assertEquals("fallback", PresetRepository.readName(path))
        Files.writeString(path, """{"schema":"strata.launch/1","name":" 工作预设 ","api_key":"DO-NOT-RETURN","extra":{"STRATA_API_KEY":"SECRET"},"context":{"future":"format"}}""")
        assertEquals("工作预设", PresetRepository.readName(path))
        assertFalse(PresetRepository.list(temp).toString().contains("SECRET"))
        assertFalse(PresetRepository.list(temp).toString().contains("DO-NOT-RETURN"))
    }

    @Test fun malformedIdentityAndJsonErrorsNeverExposeSourceExcerpts() {
        val path = temp.resolve("invalid.json")
        listOf(
            """{"schema":"strata.launch/1","name":123}""",
            """{"schema":"strata.launch/1","name":"bad\nname"}""",
            """{"schema":"strata.launch/1","name":"too-long"}""".replace("too-long", "x".repeat(8193)),
            """{"schema":"strata.model/1","api_key":"PRIVATE-MARKER"}""",
            """{"api_key":"PRIVATE-MARKER", broken}""",
        ).forEach { raw ->
            Files.writeString(path, raw)
            val error = assertFailsWith<IllegalArgumentException> { PresetRepository.readName(path) }
            assertFalse(error.message.orEmpty().contains("PRIVATE-MARKER"))
        }
    }

}
