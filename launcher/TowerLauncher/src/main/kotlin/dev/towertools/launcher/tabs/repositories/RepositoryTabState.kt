package dev.towertools.launcher.tabs.repositories

import androidx.compose.runtime.mutableStateOf
import dev.towertools.launcher.AppLog
import java.awt.EventQueue
import java.util.logging.Level
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

private object AwtDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        EventQueue.invokeLater(block)
    }
}

class RepositoryTabState(private val controller: RepositoryController) {
    private val initialLocations = runCatching(controller::locations)
    private val initialAppearance = runCatching(controller::appearance)
    private val scope = CoroutineScope(SupervisorJob() + AwtDispatcher)

    val locations = mutableStateOf(initialLocations.getOrDefault(emptyList()))
    val appearance = mutableStateOf(initialAppearance.getOrDefault(RepositoryAppearanceSettings()))
    internal val selectedFilter = mutableStateOf<RepositoryFilter>(
        if (displayRepositories(discoveredRepositories(locations.value), appearance.value).any { it.favorite }) {
            RepositoryFilter.Favorites
        } else {
            RepositoryFilter.All
        },
    )
    val editingRepository = mutableStateOf<DisplayRepository?>(null)
    val saveError = mutableStateOf<String?>(null)
    val message = mutableStateOf(
        initialLocations.exceptionOrNull()?.let { "读取仓库路径失败：${it.message}" }
            ?: initialAppearance.exceptionOrNull()?.let { "读取仓库分类设置失败：${it.message}" },
    )
    val isError = mutableStateOf(initialLocations.isFailure || initialAppearance.isFailure)
    val pathInput = mutableStateOf("")
    val isRefreshing = mutableStateOf(false)
    val refreshingId = mutableStateOf<String?>(null)
    val scanFailures = mutableStateOf<Map<String, String>>(emptyMap())

    fun refreshAll() {
        val targets = locations.value
        if (isRefreshing.value || targets.isEmpty()) return
        isRefreshing.value = true
        scanFailures.value = emptyMap()
        isError.value = false
        scope.launch {
            var successes = 0
            try {
                targets.forEachIndexed { index, location ->
                    refreshingId.value = location.id
                    message.value = "正在扫描 ${index + 1}/${targets.size}：${location.path}"
                    try {
                        locations.value = withContext(Dispatchers.IO) { controller.refresh(location.id) }
                        successes++
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        AppLog.logger.log(Level.WARNING, "Repository scan failed: ${location.path}", error)
                        scanFailures.value = scanFailures.value + (location.id to (error.message ?: "未知错误"))
                    }
                }
                val appearanceResult = runCatching { withContext(Dispatchers.IO) { controller.appearance() } }
                appearanceResult.onSuccess { appearance.value = it }
                val failures = scanFailures.value.size
                selectedFilter.value = validRepositoryFilter(
                    selectedFilter.value,
                    displayRepositories(discoveredRepositories(locations.value), appearance.value),
                )
                message.value = when {
                    appearanceResult.isFailure -> "仓库已刷新，但分类设置读取失败：${appearanceResult.exceptionOrNull()?.message ?: "未知错误"}"
                    failures == 0 -> "已刷新 $successes 个扫描路径。"
                    else -> "已刷新 $successes 个扫描路径，$failures 个失败；请在设置中查看。"
                }
                isError.value = failures > 0 || appearanceResult.isFailure
            } catch (error: CancellationException) {
                throw error
            } finally {
                refreshingId.value = null
                isRefreshing.value = false
            }
        }
    }

    fun close() = scope.cancel()
}
