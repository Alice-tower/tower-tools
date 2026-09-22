package dev.towertools.resourcetagger

import java.sql.ResultSet

/** All result filtering runs in SQLite before LIMIT; a page never changes query semantics. */
internal class LibraryQueries(private val db: Database) {
    fun roots(): List<Root> = db.query("SELECT * FROM roots") { Root(it.getString("id"), it.getString("path"), it.getString("display_name"), it.getString("availability_status"), it.getString("last_scan_at"), it.getString("last_successful_scan_at"), it.getString("last_scan_error")) }
    fun resourceRow(it: ResultSet) = Resource(it.getString("id"), it.getString("root_id"), it.getString("relative_path"), Kind.valueOf(it.getString("kind")), it.getString("display_name"), Status.valueOf(it.getString("status")), it.getString("created_at"), it.getString("last_seen_at"))
    fun resources(where: String = "1", vararg values: Any?) = db.query("SELECT * FROM resources WHERE $where", *values, map = ::resourceRow)
    fun reviews(where: String = "1", vararg values: Any?) = db.query("SELECT * FROM review_items WHERE state='Pending' AND ($where)", *values) { Review(it.getString("id"), it.getString("resource_id"), Reason.valueOf(it.getString("reason")), it.getString("observed_kind")?.let(Kind::valueOf)) }
    fun tags(): List<Tag> {
        val names = db.query("SELECT tag_id,name,name_kind FROM tag_names ORDER BY name") { Triple(it.getString(1), it.getString(2), it.getString(3)) }
        return names.groupBy { it.first }.map { (id, entries) -> Tag(id, entries.single { it.third == "canonical" }.second, entries.filter { it.third == "alias" }.map { it.second }) }.sortedBy { it.name }
    }
    private fun counts(sql: String): Map<String, Int> = db.query(sql) { it.getString(1) to it.getInt(2) }.toMap()
    fun overview(): Snapshot = Snapshot(roots = roots(), tags = tags(),
        rootCounts = counts("SELECT root_id,COUNT(*) FROM resources GROUP BY root_id"),
        tagCounts = counts("SELECT tag_id,COUNT(*) FROM resource_tags GROUP BY tag_id"),
        pendingCount = db.query("SELECT COUNT(*) FROM review_items WHERE state='Pending'") { it.getInt(1) }.single())
    fun records(resources: List<Resource>): Snapshot {
        if (resources.isEmpty()) return Snapshot()
        val json = idsJson(resources.map { it.id })
        val links = db.query("SELECT resource_id,tag_id FROM resource_tags WHERE resource_id IN (SELECT value FROM json_each(?))", json) { it.getString(1) to it.getString(2) }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
        return Snapshot(resources = resources, links = links, reviews = reviews("resource_id IN (SELECT value FROM json_each(?))", json))
    }
    fun snapshot(): Snapshot {
        val meta = overview(); val rows = records(resources())
        return meta.copy(resources = rows.resources, links = rows.links, reviews = rows.reviews)
    }
    private fun predicate(query: Query): Pair<String, Array<Any?>> {
        val clauses = mutableListOf<String>(); val values = mutableListOf<Any?>()
        fun add(sql: String, vararg args: Any?) { clauses += sql; values.addAll(args) }
        query.rootId?.let { add("r.root_id=?", it) }; query.kind?.let { add("r.kind=?", it.name) }
        if (query.status == null) add("r.status<>'Ignored'") else add("r.status=?", query.status.name)
        if (query.untagged) add("NOT EXISTS(SELECT 1 FROM resource_tags t WHERE t.resource_id=r.id)")
        if (query.reviewOnly) add("EXISTS(SELECT 1 FROM review_items v WHERE v.resource_id=r.id AND v.state='Pending')")
        val text = normalizedName(query.text)
        if (text.isNotEmpty()) add("(instr(r.display_key,?)>0 OR instr(r.path_key,?)>0)", text, text)
        val includes = query.tags.filterValues { it == TagFilter.Include }.keys
        val excludes = query.tags.filterValues { it == TagFilter.Exclude }.keys
        if (includes.isNotEmpty()) add("(SELECT COUNT(*) FROM resource_tags t WHERE t.resource_id=r.id AND t.tag_id IN (SELECT value FROM json_each(?)))=?", idsJson(includes), includes.size)
        if (excludes.isNotEmpty()) add("NOT EXISTS(SELECT 1 FROM resource_tags t WHERE t.resource_id=r.id AND t.tag_id IN (SELECT value FROM json_each(?)))", idsJson(excludes))
        return clauses.joinToString(" AND ").ifEmpty { "1" } to values.toTypedArray()
    }
    fun page(query: Query, requestedOffset: Int, pageSize: Int): ResourcePage {
        require(pageSize in 1..1000 && requestedOffset >= 0)
        val (where, values) = predicate(query)
        val total = db.query("SELECT COUNT(*) FROM resources r WHERE $where", *values) { it.getInt(1) }.single()
        val offset = if (total == 0) 0 else requestedOffset.coerceAtMost((total - 1) / pageSize * pageSize)
        val resources = db.query("SELECT r.* FROM resources r WHERE $where ORDER BY r.display_key COLLATE JAVA_TEXT,r.id COLLATE JAVA_TEXT LIMIT ? OFFSET ?", *values, pageSize, offset, map = ::resourceRow)
        return ResourcePage(records(resources), total, offset, pageSize)
    }
    fun matchingIds(query: Query, candidates: Set<String>? = null): Set<String> {
        val (where, values) = predicate(query)
        if (candidates != null) return db.query("SELECT r.id FROM resources r WHERE $where AND r.id IN (SELECT value FROM json_each(?))", *values, idsJson(candidates)) { it.getString(1) }.toSet()
        return db.query("SELECT r.id FROM resources r WHERE $where", *values) { it.getString(1) }.toSet()
    }
    fun selectionCounts(ids: Set<String>): Map<String, Int> = db.query("SELECT tag_id,COUNT(*) FROM resource_tags WHERE resource_id IN (SELECT value FROM json_each(?)) GROUP BY tag_id", idsJson(ids)) { it.getString(1) to it.getInt(2) }.toMap()
}

/** IDs are normally UUIDs; encode properly so imported identifiers cannot alter the query. */
internal fun idsJson(ids: Collection<String>): String = ids.joinToString(",", "[", "]") { value ->
    "\"" + buildString { value.forEach { char -> when { char == '\\' -> append("\\\\"); char == '"' -> append("\\\""); char.code < 32 -> append("\\u%04x".format(char.code)); else -> append(char) } } } + "\""
}
