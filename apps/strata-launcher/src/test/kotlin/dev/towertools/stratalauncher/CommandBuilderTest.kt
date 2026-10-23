package dev.towertools.stratalauncher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandBuilderTest {
    private fun valid(config: LaunchConfig = LaunchConfig()): CommandResult = CommandBuilder.build(config).also {
        assertEquals(emptyList(), it.errors)
        assertTrue(it.command.isNotEmpty())
    }

    private fun invalid(config: LaunchConfig, reason: String? = null): CommandResult = CommandBuilder.build(config).also {
        assertEquals("", it.command)
        assertEquals(emptyList(), it.tokens)
        assertTrue(it.errors.isNotEmpty())
        if (reason != null) assertTrue(it.errors.any { error -> error.contains(reason) }, it.errors.toString())
    }

    @Test
    fun defaultsDoNotContainMachineSpecificPathsOrPresetNames() {
        val result = valid()
        assertEquals(
            "strata launch --model ud-iq4_xs --family unsloth --context 128k --kv int8 --vision off --parallel 1 --tune skip --access local --port 7271 --set vram-reserve-mib=2048",
            result.command,
        )
        assertEquals(emptyList(), result.warnings)
        assertEquals("", LaunchConfig().dataDir)
        assertEquals("", LaunchConfig().preset)
        assertFalse(result.command.contains('\n'))
        listOf("--no-tui", "--open", "--yes", "--plan", "--feed", "--output", "--tune-allow-small", "--api-key-env").forEach {
            assertFalse(it in result.tokens)
        }
    }

    @Test
    fun savingAPresetIsAFlagOnTheLaunchCommand() {
        val result = valid(LaunchConfig(savePreset = "Office Copy"))
        assertTrue(result.command.contains("--model ud-iq4_xs"))
        assertTrue(result.command.contains("--save-preset \"Office Copy\""))
        assertTrue(result.command.startsWith("strata launch "))
        assertFalse("--save-preset" in valid(LaunchConfig()).tokens)
    }

    @Test
    fun unsupportedActionsAndMissingModelsCannotBeGenerated() {
        invalid(LaunchConfig(action = "delete-preset", preset = "Office"), "操作")
        invalid(LaunchConfig(model = ""), "模型名称")
    }

    @Test
    fun managementCommandsNeverAcquireLaunchOptionsOrLiteralKeys() {
        for (action in listOf("status", "stop", "list-presets", "show-key")) {
            val result = valid(LaunchConfig(
                action = action, preset = "Office", context = "invalid", port = "99999",
                keyMode = "literal", apiKey = "DUMMY_UNUSED_SECRET",
            ))
            val target = if (action in listOf("stop", "show-key")) " --preset Office" else ""
            assertEquals("strata launch --$action$target", result.command)
            assertFalse(result.command.contains("DUMMY_UNUSED_SECRET"))
            assertFalse("--context" in result.tokens)
        }
    }

    @Test
    fun stoppingRequiresAnExplicitTargetOrAllScope() {
        val missing = CommandBuilder.management(ManagementConfig(action = "stop"))
        assertEquals("", missing.command)
        assertEquals(emptyList(), missing.tokens)
        assertTrue(missing.errors.any { it.contains("目标预设") })
        assertEquals(emptyList(), missing.warnings)

        val named = CommandBuilder.management(ManagementConfig(action = "stop", preset = "Office"))
        assertEquals(emptyList(), named.errors)
        assertEquals("strata launch --stop --preset Office", named.command)

        val all = CommandBuilder.management(ManagementConfig(action = "stop", scope = "all", preset = "Office"))
        assertEquals(emptyList(), all.errors)
        assertEquals("strata launch --stop", all.command)
        assertEquals(emptyList(), all.warnings)
    }

    @Test
    fun showKeyRequiresAPresetWithoutAnExtraWarning() {
        for (scope in listOf("preset", "all")) {
            val missing = CommandBuilder.management(ManagementConfig(action = "show-key", scope = scope))
            assertEquals("", missing.command)
            assertTrue(missing.errors.isNotEmpty())
            val named = CommandBuilder.management(ManagementConfig(action = "show-key", scope = scope, preset = "Office"))
            assertEquals("strata launch --show-key --preset Office", named.command)
            assertEquals(emptyList(), named.warnings)
        }
        invalid(LaunchConfig(action = "show-key"), "目标预设")
    }

    @Test
    fun malformedManagementChoicesNeverProduceAPartialCommand() {
        for (config in listOf(
            ManagementConfig(action = "launch"),
            ManagementConfig(action = "delete-preset", preset = "Office"),
            ManagementConfig(action = "stop", scope = "unknown"),
            ManagementConfig(preset = "hidden\nvalue"),
            ManagementConfig(preset = "x".repeat(8193)),
        )) {
            val result = CommandBuilder.management(config)
            assertEquals("", result.command)
            assertEquals(emptyList(), result.tokens)
            assertTrue(result.errors.isNotEmpty())
        }
    }

    @Test
    fun numericAndEnumErrorsBlockTheWholeCommand() {
        for (context in listOf("0", "100", "-32k", "32.5k", "32k trailing", "9007199254740993", "99999999999999999999999999999m")) {
            invalid(LaunchConfig(context = context), "上下文")
        }
        for (port in listOf("0", "65536", "1.5", "8080;exit")) invalid(LaunchConfig(port = port), "端口")
        for (parallel in listOf("0", "9", "1.5", "x")) invalid(LaunchConfig(parallel = parallel), "并发")
        for (gpu in listOf("-1", "0,,1", "0,0", "0,00", "0 & calc", "0,9007199254740992")) invalid(LaunchConfig(gpu = gpu), "GPU")
        for (config in listOf(
            LaunchConfig(kv = "int4"), LaunchConfig(vision = "maybe"),
            LaunchConfig(access = "public"), LaunchConfig(tune = "fast"), LaunchConfig(keyMode = "plain"),
        )) invalid(config)
        invalid(LaunchConfig(vramReserve = "-1"), "显存")
    }

    @Test
    fun numericNormalizationAndAdvisoryWarningsArePreserved() {
        val result = valid(LaunchConfig(context = "128K", gpu = "00, 1", port = "08081", vramReserve = "0"))
        assertTrue(result.command.contains("--context 128k"))
        assertTrue(result.command.contains("--gpu 0,1"))
        assertTrue(result.command.contains("--port 8081"))
        assertTrue("vram-reserve-mib=0" in result.tokens)
        val larger = valid(LaunchConfig(context = "1m", parallel = "2"))
        assertEquals(2, larger.warnings.size)
        assertTrue(larger.warnings.any { it.contains("256k") })
        assertTrue(larger.warnings.any { it.contains("并发") })
    }

    @Test
    fun controlCharactersAreRejectedEvenInHiddenUnusedFields() {
        for (bad in listOf('\r', '\n', '\u0000', '\t', '\u007f')) {
            invalid(LaunchConfig(apiKey = "hidden${bad}field"), "控制字符")
            invalid(LaunchConfig(action = "status", dataDir = "hidden$bad"), "控制字符")
            invalid(LaunchConfig(savePreset = "name$bad"), "控制字符")
        }
    }

    @Test
    fun cmdQuotesMetacharactersAndDoublesTrailingBackslashes() {
        assertEquals("\"a&b|c<d>e^f(g)\"", CommandBuilder.quote("a&b|c<d>e^f(g)"))
        assertEquals("\"C:\\模型 文件\\\\\"", CommandBuilder.quote("C:\\模型 文件\\"))
        assertEquals("\"\"", CommandBuilder.quote(""))
        assertFailsWith<IllegalArgumentException> { CommandBuilder.quote("hello\nworld") }
        val result = valid(LaunchConfig(model = "模型 & echo INJECT | more", savePreset = "中文预设"))
        assertTrue(result.command.contains("--model \"模型 & echo INJECT | more\""))
        assertTrue(result.command.contains("--save-preset \"中文预设\""))
        for (preset in listOf("%PATH%", "!PATH!", "a\"&echo INJECT")) invalid(LaunchConfig(model = preset), "CMD")
    }

    @Test
    fun dashPrefixedValuesRemainValues() {
        val result = valid(LaunchConfig(keyMode = "literal", apiKey = "--stop", savePreset = "--status"))
        assertTrue("--api-key=--stop" in result.tokens)
        assertTrue("--save-preset=--status" in result.tokens)
        assertFalse("--stop" in result.tokens)
        assertFalse("--status" in result.tokens)
    }

    @Test
    fun apiKeyModesAreExclusiveAndEnvironmentModeIsRejected() {
        for (mode in listOf("none", "new")) {
            val result = valid(LaunchConfig(keyMode = mode, apiKey = "DUMMY_UNUSED_SECRET"))
            assertFalse(result.command.contains("DUMMY_UNUSED_SECRET"))
            assertFalse("--api-key" in result.tokens)
            assertFalse("--api-key-env" in result.tokens)
            assertEquals(mode == "new", "--new-key" in result.tokens)
        }
        invalid(LaunchConfig(keyMode = "env"), "密钥方式")
        invalid(LaunchConfig(keyMode = "literal", apiKey = "   "), "API 密钥")
    }

    @Test
    fun unreasonableFieldSizesAreRejectedWithoutTruncation() {
        invalid(LaunchConfig(model = "x".repeat(8193)), "8192")
    }

}
