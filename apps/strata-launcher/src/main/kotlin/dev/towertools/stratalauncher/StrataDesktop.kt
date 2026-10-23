package dev.towertools.stratalauncher

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.awt.Component
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.nio.file.Path
import java.nio.file.Files
import dev.towertools.stratalauncher.tabs.presets.PresetCommandExecutor
import dev.towertools.stratalauncher.experimental.EspConfigRepository
import dev.towertools.stratalauncher.experimental.EspConfigEntry
import dev.towertools.stratalauncher.experimental.ExperimentalSettingsDialog

@Composable
fun StrataDesktop(state: StrataState, owner: Component) {
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(emptyList<PresetEntry>()) }
    var presetsLoading by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    var actionBusy by remember { mutableStateOf(false) }
    val portUpdates = remember { LocalService.observe() }
    val portStatus by portUpdates.collectAsState(initial = PortStatus.CHECKING)
    val espRepository = remember { EspConfigRepository(backupDirectory = AppPaths.dataDirectory.resolve("esp-backups")) }
    var experimentalOpen by remember { mutableStateOf(false) }
    var baseConfigs by remember { mutableStateOf(emptyList<EspConfigEntry>()) }
    var configsLoading by remember { mutableStateOf(false) }
    var espBusyPath by remember { mutableStateOf<Path?>(null) }
    var espMessage by remember { mutableStateOf("") }
    var espError by remember { mutableStateOf("") }

    fun refreshBaseConfigs() {
        configsLoading = true
        espError = ""
        scope.launch {
            try { baseConfigs = withContext(Dispatchers.IO) { espRepository.list() } }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                espError = "读取基础配置失败，请检查 Strata 安装目录、设置文件和访问权限。"
                AppLog.logger.warning("读取基础配置失败 (${error.javaClass.simpleName})")
            }
            finally { configsLoading = false }
        }
    }

    fun failure(operation: String, error: Throwable) {
        if (error is CancellationException) throw error
        state.message = "$operation：${error.message ?: "操作未完成，请检查文件权限。"}"
        // Never log preset documents, commands, arbitrary parameter values or exception messages.
        AppLog.logger.warning("$operation (${error.javaClass.simpleName})")
    }

    LaunchedEffect(Unit) {
        val directory = runCatching { PresetRepository.defaultDirectory().toString() }.getOrElse {
            state.message = "预设目录环境变量无效，请在预设列表选择目录。"
            ""
        }
        state.setPresetDirectory(directory)
    }

    LaunchedEffect(state.session.presetDirectory, refresh) {
        if (state.session.presetDirectory.isNotBlank()) {
            presetsLoading = true
            entries = emptyList()
            try {
                entries = withContext(Dispatchers.IO) { PresetRepository.list(Path.of(state.session.presetDirectory)) }
                state.updatePresetCommand(state.session.presetCommand.resolve(entries))
            }
            catch (error: Exception) { failure("读取预设目录失败", error) }
            finally { presetsLoading = false }
        }
    }

    StrataApp(state, entries, presetsLoading, actionBusy = actionBusy, portStatus = portStatus,
        onOpenExperimental = {
            experimentalOpen = true
            espMessage = ""
            refreshBaseConfigs()
        },
        onCopyApi = { address ->
            try {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(address), null)
                state.message = "已复制 API 地址：$address"
            } catch (error: Exception) { failure("复制 API 地址失败", error) }
        },
        onOpenWeb = { address ->
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { Desktop.getDesktop().browse(URI.create(address)) }
                    state.message = "已在默认浏览器打开：$address"
                } catch (error: Exception) { failure("打开网页失败", error) }
            }
        },
        onExecuteModelCommand = { config ->
            actionBusy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { TerminalCommandExecutor.executeModel(config) }
                    state.message = "命令已发送，请在终端查看结果。"
                } catch (error: Exception) { failure("执行命令失败", error) }
                finally { actionBusy = false }
            }
        },
        onOpenPresetJson = { entry ->
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        require(Files.isRegularFile(entry.path)) { "预设文件不存在，请刷新列表。" }
                        Desktop.getDesktop().open(entry.path.toFile())
                    }
                    state.message = "已用系统默认程序打开：${entry.path.fileName}。"
                } catch (error: Exception) { failure("打开预设 JSON 失败", error) }
            }
        },
        onExecutePresetCommand = { selection ->
            val directory = state.session.presetDirectory
            actionBusy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { PresetCommandExecutor.execute(Path.of(directory), selection) }
                    state.message = "命令已发送，请在终端查看结果。"
                } catch (error: Exception) { failure("执行命令失败", error) }
                finally { actionBusy = false }
            }
        },
        onChooseDataDir = {
            val config = state.config
            scope.launch {
                try {
                    DesktopFiles.chooseDirectory(owner, initial = config.dataDir)?.let { state.updateConfig(config.copy(dataDir = it.toString())) }
                } catch (error: Exception) { failure("选择目录失败", error) }
            }
        },
        onChoosePresetDir = {
            scope.launch {
                try {
                    DesktopFiles.chooseDirectory(owner, initial = state.session.presetDirectory)?.let { state.setPresetDirectory(it.toString()) }
                } catch (error: Exception) { failure("选择目录失败", error) }
            }
        },
        onRefresh = { refresh++ },
        onOpenPresetDir = {
            scope.launch {
                try { withContext(Dispatchers.IO) { Desktop.getDesktop().open(Path.of(state.session.presetDirectory).toFile()) } }
                catch (error: Exception) { failure("打开目录失败", error) }
            }
        },
        onCopy = { command ->
            try {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(command), null)
                state.message = "已复制，粘贴到 CMD 执行。"
            } catch (error: Exception) { failure("复制失败，可选中命令后手动复制", error) }
        },
    )
    if (experimentalOpen) ExperimentalSettingsDialog(baseConfigs, configsLoading, espBusyPath, espMessage, espError,
        onRefresh = { espMessage = ""; refreshBaseConfigs() },
        onDismiss = { experimentalOpen = false },
        onToggle = { entry, enabled ->
            espBusyPath = entry.path
            espMessage = ""
            espError = ""
            scope.launch {
                try {
                    val updated = withContext(Dispatchers.IO) { espRepository.setEnabled(entry, enabled) }
                    baseConfigs = baseConfigs.map { if (it.path == entry.path) updated else it }
                    espMessage = "已${if (enabled) "启用" else "关闭"} ESP；重启该模型后生效。修改前配置已备份。"
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    espError = if (error is IllegalArgumentException) error.message ?: "配置已变化，请刷新后重试。"
                        else "保存失败，请检查文件权限；未自动重启模型。"
                    AppLog.logger.warning("修改 ESP 失败 (${error.javaClass.simpleName})")
                } finally { espBusyPath = null }
            }
        })
}
