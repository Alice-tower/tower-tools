package dev.towertools.launcher.tabs.tools

private const val UNCATEGORIZED = "未分类"

internal fun orderedCategories(tools: List<LauncherTool>, savedOrder: List<String>): List<String> {
    val available = tools.map { it.category }.distinct()
    val saved = savedOrder.distinct().filter { it in available }
    val remaining = available.filterNot { it in saved }
        .sortedWith(compareBy<String> { it == UNCATEGORIZED }.thenBy { it })
    return saved + remaining
}

internal fun moveCategory(categories: List<String>, moving: String, target: String): List<String> {
    val from = categories.indexOf(moving)
    val to = categories.indexOf(target)
    if (from < 0 || to < 0 || from == to) return categories
    return categories.toMutableList().apply {
        removeAt(from)
        add(to, moving)
    }
}
