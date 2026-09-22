package dev.towertools.resourcetagger

import java.text.Normalizer
import java.util.Locale

fun normalizedName(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
enum class Kind(val title: String) { Directory("目录"), File("文件") }
enum class Status(val title: String) { Active("可用"), Missing("缺失"), Ignored("已忽略") }
enum class Reason(val title: String) { New("新发现"), Missing("缺失"), TypeChanged("类型变化") }
enum class TagFilter(val title: String) { Neutral("不限"), Include("包含 +"), Exclude("排除 −") }
data class Root(val id: String, val path: String, val name: String, val availability: String, val lastScan: String?, val lastSuccess: String?, val error: String?)
data class Resource(val id: String, val rootId: String, val relativePath: String, val kind: Kind, val name: String, val status: Status, val created: String, val lastSeen: String?)
data class Tag(val id: String, val name: String, val aliases: List<String>) {
    fun matches(query: String) = (listOf(name) + aliases).any { normalizedName(it).contains(normalizedName(query)) }
}
data class Review(val id: String, val resourceId: String, val reason: Reason, val observedKind: Kind?)
data class Snapshot(val roots: List<Root> = emptyList(), val resources: List<Resource> = emptyList(), val tags: List<Tag> = emptyList(), val links: Map<String, Set<String>> = emptyMap(), val reviews: List<Review> = emptyList(), val rootCounts: Map<String, Int> = emptyMap(), val tagCounts: Map<String, Int> = emptyMap(), val pendingCount: Int = reviews.size)
data class ResourcePage(val snapshot: Snapshot, val total: Int, val offset: Int, val pageSize: Int)
data class Query(val text: String = "", val rootId: String? = null, val kind: Kind? = null, val status: Status? = null, val untagged: Boolean = false, val reviewOnly: Boolean = false, val tags: Map<String, TagFilter> = emptyMap()) {
    fun apply(data: Snapshot): List<Resource> {
        val pending = data.reviews.mapTo(HashSet()) { it.resourceId }
        val search = normalizedName(text)
        return data.resources.filter { r ->
        val assigned = data.links[r.id].orEmpty()
        (rootId == null || r.rootId == rootId) && (kind == null || r.kind == kind) &&
            (if (status == null) r.status != Status.Ignored else r.status == status) &&
            (!untagged || assigned.isEmpty()) && (!reviewOnly || r.id in pending) &&
            (normalizedName(r.name).contains(search) || normalizedName(r.relativePath).contains(search)) &&
            tags.all { (id, mode) -> when (mode) { TagFilter.Neutral -> true; TagFilter.Include -> id in assigned; TagFilter.Exclude -> id !in assigned } }
    }.sortedWith(compareBy({ normalizedName(it.name) }, { it.id }))
    }
}
