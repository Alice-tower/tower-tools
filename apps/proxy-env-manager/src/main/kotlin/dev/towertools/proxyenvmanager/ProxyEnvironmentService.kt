package dev.towertools.proxyenvmanager

class ProxyEnvironmentConflict(
    val names: List<String>,
    val latest: ProxyEnvironment,
) : IllegalStateException("以下变量已被其他程序修改：${names.joinToString("、")}")

class ProxyEnvironmentSaveFailure(
    cause: Throwable,
    val rollbackFailures: List<String>,
    val latest: ProxyEnvironment? = null,
) : IllegalStateException(
    if (rollbackFailures.isEmpty()) "保存失败，已恢复修改前的值：${cause.message ?: cause.javaClass.simpleName}"
    else "保存失败，以下变量未能恢复：${rollbackFailures.joinToString("、")}。请刷新并检查当前值。",
    cause,
)

class ProxyEnvironmentService(
    private val store: UserEnvironmentStore = WindowsUserEnvironmentStore,
    private val presetStore: ProxyPresetStore = FileProxyPresetStore(AppPaths.proxyPresetsFile),
) {
    fun presets(): ProxyPresets = presetStore.read()

    fun savePresets(presets: ProxyPresets): ProxyPresets {
        require(presets.httpProxy.isNotBlank()) { "HTTP_PROXY 预设不能为空" }
        require(presets.httpsProxy.isNotBlank()) { "HTTPS_PROXY 预设不能为空" }
        require(presets.allProxy.isNotBlank()) { "ALL_PROXY 预设不能为空" }
        presetStore.write(presets)
        return presets
    }

    fun read(): ProxyEnvironment = ProxyEnvironment(
        httpProxy = store.read(ProxyVariables.HTTP_PROXY),
        httpsProxy = store.read(ProxyVariables.HTTPS_PROXY),
        allProxy = store.read(ProxyVariables.ALL_PROXY),
        noProxy = store.read(ProxyVariables.NO_PROXY),
    )

    fun saveChanges(draft: ProxyEnvironmentDraft): ProxyEnvironment {
        val changes = draft.changes
        val previous = read()
        val original = draft.current.asMap()
        val actual = previous.asMap()
        val conflicts = changes.keys.filter { actual.getValue(it) != original.getValue(it) }
        if (conflicts.isNotEmpty()) throw ProxyEnvironmentConflict(conflicts, previous)
        val attempted = mutableListOf<String>()
        try {
            changes.forEach { (name, value) ->
                // Include the failing operation: a store may throw after changing a value.
                attempted += name
                restore(name, value)
            }
            val saved = read()
            check(changes.all { (name, value) -> saved.asMap().getValue(name) == value }) { "保存后读取的值与待保存值不一致" }
            return saved
        } catch (failure: Exception) {
            val rollbackFailures = mutableListOf<String>()
            attempted.asReversed().forEach { name ->
                runCatching {
                    restore(name, actual.getValue(name))
                    check(store.read(name) == actual.getValue(name)) { "恢复后读取的值不一致" }
                }.onFailure {
                    rollbackFailures += name
                    failure.addSuppressed(it)
                }
            }
            val latest = if (rollbackFailures.isNotEmpty()) {
                runCatching(::read).onFailure { failure.addSuppressed(it) }.getOrNull()
            } else null
            throw ProxyEnvironmentSaveFailure(failure, rollbackFailures, latest)
        }
    }

    private fun restore(name: String, value: String?) {
        if (value == null) store.delete(name) else store.write(name, value)
    }
}
