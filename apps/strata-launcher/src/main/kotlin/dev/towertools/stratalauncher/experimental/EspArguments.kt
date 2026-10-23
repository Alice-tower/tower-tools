package dev.towertools.stratalauncher.experimental

/** Pure transformation of one native argument layer. Never infer ownership from merged layers. */
internal object EspArguments {
    private const val VECTOR = "Qwen3.8-Flash-Next-experimental-speed-projection.gguf"
    private val vectors = setOf("--control-vector", "--control-vector-scaled")
    private val settings = setOf("--control-vector-layer-range", "--cvec-mode", "--cvec-dir")

    fun disable(input: List<String>): List<String> {
        val groups = mutableListOf<MutableList<String>>()
        input.forEach { token ->
            if (token.startsWith("--") || groups.isEmpty()) groups += mutableListOf(token)
            else groups.last() += token
        }
        fun esp(group: List<String>) = group.first() in vectors && group.drop(1).any { it.contains(VECTOR, true) }
        val owned = groups.filter(::esp)
        if (owned.isEmpty()) return input
        require(owned.all { group -> group.drop(1).all { it.contains(VECTOR, true) } }) {
            "ESP 与其他控制向量共用参数组，无法安全分离；请手动编辑配置。"
        }
        val otherVectors = groups.any { it.first() in vectors && !esp(it) }
        return groups.filterNot { esp(it) || (!otherVectors && it.first() in settings) }.flatten()
    }
}
