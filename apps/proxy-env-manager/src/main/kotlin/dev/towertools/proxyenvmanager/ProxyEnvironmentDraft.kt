package dev.towertools.proxyenvmanager

/** An in-memory editing session. Only the service's saveChanges writes to Windows. */
data class ProxyEnvironmentDraft(
    val current: ProxyEnvironment,
    val proposed: ProxyEnvironment = current,
) {
    val changes: Map<String, String?>
        get() = proposed.asMap().filter { (name, value) -> current.asMap().getValue(name) != value }

    fun usePreset(name: String, presets: ProxyPresets): ProxyEnvironmentDraft {
        require(name in ProxyVariables.names) { "不支持的代理环境变量：$name" }
        require(presets.isConfigured()) { "请先完成必要的代理预设配置" }
        return copy(proposed = proposed.withValue(name, presets.asMap().getValue(name)))
    }

    fun useAllPresets(presets: ProxyPresets): ProxyEnvironmentDraft {
        require(presets.isConfigured()) { "请先完成必要的代理预设配置" }
        return copy(proposed = ProxyEnvironment(presets.httpProxy, presets.httpsProxy, presets.allProxy, presets.noProxy))
    }

    fun clear(name: String) = copy(proposed = proposed.withValue(name, null))
    fun clearAll() = copy(proposed = ProxyEnvironment(null, null, null, null))
    fun undo(name: String) = copy(proposed = proposed.withValue(name, current.asMap().getValue(name)))
    fun discard() = ProxyEnvironmentDraft(current)

    /** Called only after the user explicitly chooses whether to retain their edits. */
    fun refresh(latest: ProxyEnvironment, keepChanges: Boolean): ProxyEnvironmentDraft {
        val retained = if (keepChanges) changes else emptyMap()
        return ProxyEnvironmentDraft(latest, retained.entries.fold(latest) { values, (name, value) -> values.withValue(name, value) })
    }
}

fun ProxyEnvironment.asMap(): Map<String, String?> = linkedMapOf(
    ProxyVariables.HTTP_PROXY to httpProxy,
    ProxyVariables.HTTPS_PROXY to httpsProxy,
    ProxyVariables.ALL_PROXY to allProxy,
    ProxyVariables.NO_PROXY to noProxy,
)

fun ProxyEnvironment.withValue(name: String, value: String?): ProxyEnvironment = when (name) {
    ProxyVariables.HTTP_PROXY -> copy(httpProxy = value)
    ProxyVariables.HTTPS_PROXY -> copy(httpsProxy = value)
    ProxyVariables.ALL_PROXY -> copy(allProxy = value)
    ProxyVariables.NO_PROXY -> copy(noProxy = value)
    else -> throw IllegalArgumentException("不支持的代理环境变量：$name")
}
