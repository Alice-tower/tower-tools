package dev.towertools.launcher.tabs.tools

import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolActionsTest {
    @Test fun launchedGuiToolsCannotBlockOnUnconsumedOutputPipes() {
        val executable = Paths.get("C:/Tools/SampleTool/SampleTool.exe")
        val tool = LauncherTool(
            id = "dev.towertools.sample",
            projectName = "SampleTool",
            displayName = "示例工具",
            description = "测试",
            version = "1.0.0",
            executable = executable,
            category = "未分类",
            order = 0,
        )

        val builder = ToolActions.processBuilder(tool)

        assertEquals(executable.parent.toFile(), builder.directory())
        assertEquals(ProcessBuilder.Redirect.Type.READ, builder.redirectInput().type())
        assertEquals("NUL", builder.redirectInput().file()?.path)
        assertEquals(ProcessBuilder.Redirect.DISCARD, builder.redirectOutput())
        assertEquals(ProcessBuilder.Redirect.DISCARD, builder.redirectError())
    }

    @Test fun windowsNullInputLetsAReadingChildReachEndOfFile() {
        val commandProcessor = Paths.get(System.getenv("ComSpec") ?: "C:/Windows/System32/cmd.exe")
        val tool = LauncherTool(
            id = "dev.towertools.stdin-test",
            projectName = "InputTest",
            displayName = "输入测试",
            description = "测试",
            version = "1.0.0",
            executable = commandProcessor,
            category = "未分类",
            order = 0,
        )

        val process = ToolActions.processBuilder(tool).start()

        assertTrue(process.waitFor(5, TimeUnit.SECONDS), "子进程没有在读取到 NUL 的 EOF 后退出")
        assertEquals(0, process.exitValue())
    }
}
