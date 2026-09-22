package dev.towertools.resourcetagger

import java.nio.file.Path
import java.nio.file.NoSuchFileException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class Library(private val db: Database, private val fs: ResourceFileSystem = LocalFileSystem()) : AutoCloseable {
    private val scanning = ConcurrentHashMap.newKeySet<String>()
    private fun id() = UUID.randomUUID().toString()
    private fun now() = Instant.now().toString()
    @Synchronized fun snapshot(): Snapshot {
        val roots = db.query("SELECT * FROM roots") { Root(it.getString("id"), it.getString("path"), it.getString("display_name"), it.getString("availability_status"), it.getString("last_scan_at"), it.getString("last_successful_scan_at"), it.getString("last_scan_error")) }
        val resources = db.query("SELECT * FROM resources") { Resource(it.getString("id"), it.getString("root_id"), it.getString("relative_path"), Kind.valueOf(it.getString("kind")), it.getString("display_name"), Status.valueOf(it.getString("status")), it.getString("created_at"), it.getString("last_seen_at")) }
        val names = db.query("SELECT tag_id,name,name_kind FROM tag_names ORDER BY name") { Triple(it.getString(1), it.getString(2), it.getString(3)) }
        val tags = names.filter { it.third == "canonical" }.map { canonical -> Tag(canonical.first, canonical.second, names.filter { it.first == canonical.first && it.third == "alias" }.map { it.second }) }
        val links = db.query("SELECT resource_id,tag_id FROM resource_tags") { it.getString(1) to it.getString(2) }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
        val reviews = db.query("SELECT * FROM review_items WHERE state='Pending'") { Review(it.getString("id"), it.getString("resource_id"), Reason.valueOf(it.getString("reason")), it.getString("observed_kind")?.let(Kind::valueOf)) }
        return Snapshot(roots, resources, tags, links, reviews)
    }
    private fun checkRootAvailableForEdit(rootId: String) { require(rootId !in scanning) { "该 Root 正在扫描，请等待完成。" } }
    @Synchronized fun saveRoot(rootId: String?, pathText: String, displayName: String): String {
        rootId?.let(::checkRootAvailableForEdit)
        val path = PathsPolicy.root(pathText)
        val key = PathsPolicy.key(path)
        require(displayName.trim().isNotEmpty()) { "Root 名称不能为空。" }
        require(snapshot().roots.none { it.id != rootId && PathsPolicy.overlaps(key, PathsPolicy.key(Path.of(it.path))) }) { "Root 与已有目录重复或相互嵌套。" }
        val newId = rootId ?: id()
        if (rootId == null) db.execute("INSERT INTO roots(id,path,normalized_path,display_name,created_at) VALUES(?,?,?,?,?)", newId, path.toString(), key, displayName.trim(), now())
        else db.execute("UPDATE roots SET path=?,normalized_path=?,display_name=?,availability_status='未扫描',last_scan_error=NULL WHERE id=?", path.toString(), key, displayName.trim(), rootId)
        return newId
    }
    @Synchronized fun renameRoot(rootId: String, name: String) {
        require(name.trim().isNotEmpty()) { "Root 名称不能为空。" }
        db.execute("UPDATE roots SET display_name=? WHERE id=?", name.trim(), rootId)
    }
    @Synchronized fun removeRoot(rootId: String) { checkRootAvailableForEdit(rootId); db.execute("DELETE FROM roots WHERE id=?", rootId) }
    private fun resolve(resourceId: String, reason: Reason? = null) {
        if (reason == null) db.execute("UPDATE review_items SET state='Resolved',resolved_at=? WHERE resource_id=? AND state='Pending'", now(), resourceId)
        else db.execute("UPDATE review_items SET state='Resolved',resolved_at=? WHERE resource_id=? AND reason=? AND state='Pending'", now(), resourceId, reason.name)
    }
    private fun review(resourceId: String, reason: Reason, observed: Kind? = null) {
        db.execute("INSERT OR IGNORE INTO review_items(id,resource_id,reason,state,detected_at,observed_kind) VALUES(?,?,?,'Pending',?,?)", id(), resourceId, reason.name, now(), observed?.name)
        if (observed != null) db.execute("UPDATE review_items SET observed_kind=? WHERE resource_id=? AND reason=? AND state='Pending'", observed.name, resourceId, reason.name)
    }
    fun scan(rootId: String) {
        val root = synchronized(this) {
            require(scanning.add(rootId)) { "该 Root 已在扫描。" }
            try { snapshot().roots.single { it.id == rootId } } catch (e: Throwable) { scanning.remove(rootId); throw e }
        }
        try {
            val found = fs.scan(Path.of(root.path))
            require(found.map { PathsPolicy.childKey(it.name) }.distinct().size == found.size) { "发现重复路径，扫描已取消。" }
            synchronized(this) {
                db.transaction {
                    val existing = snapshot().resources.filter { it.rootId == rootId }.associateBy { PathsPolicy.childKey(it.relativePath) }
                    val seen = found.associateBy { PathsPolicy.childKey(it.name) }
                    found.forEach { item ->
                        val old = existing[PathsPolicy.childKey(item.name)]
                        if (old == null) {
                            val newId = id()
                            db.execute("INSERT INTO resources VALUES(?,?,?,?,?,?,?,?,?)", newId, rootId, item.name, PathsPolicy.childKey(item.name), item.kind.name, item.name, Status.Active.name, now(), now())
                            review(newId, Reason.New)
                        } else if (old.status != Status.Ignored) {
                            if (old.kind != item.kind) {
                                db.execute("UPDATE resources SET status='Missing' WHERE id=?", old.id)
                                resolve(old.id, Reason.New); resolve(old.id, Reason.Missing)
                                review(old.id, Reason.TypeChanged, item.kind)
                            } else {
                                db.execute("UPDATE resources SET relative_path=?,display_name=?,status='Active',last_seen_at=? WHERE id=?", item.name, item.name, now(), old.id)
                                resolve(old.id, Reason.Missing); resolve(old.id, Reason.TypeChanged)
                            }
                        }
                    }
                    existing.values.filter { PathsPolicy.childKey(it.relativePath) !in seen && it.status != Status.Ignored }.forEach { old ->
                        val typePending = snapshot().reviews.any { it.resourceId == old.id && it.reason == Reason.TypeChanged }
                        if (old.status != Status.Missing || typePending) {
                            db.execute("UPDATE resources SET status='Missing' WHERE id=?", old.id)
                            resolve(old.id); review(old.id, Reason.Missing)
                        }
                    }
                    db.execute("UPDATE roots SET availability_status='可访问',last_scan_at=?,last_successful_scan_at=?,last_scan_error=NULL WHERE id=?", now(), now(), rootId)
                }
            }
        } catch (error: Exception) {
            synchronized(this) { db.execute("UPDATE roots SET availability_status='扫描失败',last_scan_at=?,last_scan_error=? WHERE id=?", now(), error.message ?: error.javaClass.simpleName, rootId) }
            throw IllegalStateException("扫描失败，原有资源和标签已保留：${error.message}", error)
        } finally { scanning.remove(rootId) }
    }
    @Synchronized fun acknowledge(resourceId: String) {
        require(snapshot().reviews.none { it.resourceId == resourceId && it.reason == Reason.TypeChanged }) { "请先明确确认类型变化，或重新定位。" }
        resolve(resourceId)
    }
    @Synchronized fun ignore(resourceId: String) = db.transaction {
        db.execute("UPDATE resources SET status='Ignored' WHERE id=?", resourceId); resolve(resourceId)
    }
    @Synchronized fun unignore(resourceId: String) = db.transaction {
        val data = snapshot(); val resource = data.resources.single { it.id == resourceId }; val root = data.roots.single { it.id == resource.rootId }
        require(java.nio.file.Files.isDirectory(Path.of(root.path))) { "Root 不可访问，无法检查资源，请稍后重试。" }
        val kind = try { fs.inspect(PathsPolicy.actual(root, resource)) } catch (_: NoSuchFileException) { null }
        val status = if (kind == resource.kind) Status.Active else Status.Missing
        db.execute("UPDATE resources SET status=? WHERE id=?", status.name, resourceId)
        if (kind != null && kind != resource.kind) review(resourceId, Reason.TypeChanged, kind)
        else if (kind == null) review(resourceId, Reason.Missing)
    }
    @Synchronized fun acceptType(resourceId: String) = db.transaction {
        val data = snapshot(); val resource = data.resources.single { it.id == resourceId }; val root = data.roots.single { it.id == resource.rootId }
        val pending = data.reviews.single { it.resourceId == resourceId && it.reason == Reason.TypeChanged }
        val actual = fs.inspect(PathsPolicy.actual(root, resource))
        require(actual != null && actual == pending.observedKind) { "目标再次变化，请重新扫描后确认。" }
        db.execute("UPDATE resources SET kind=?,status='Active',last_seen_at=? WHERE id=?", actual.name, now(), resourceId)
        resolve(resourceId)
    }
    @Synchronized fun removeResource(resourceId: String) { db.execute("DELETE FROM resources WHERE id=?", resourceId) }
    @Synchronized fun relocate(resourceId: String, targetText: String, allowMerge: Boolean = false) = db.transaction {
        require(targetText.isNotBlank()) { "请选择或输入目标完整路径。" }
        val data = snapshot(); val resource = data.resources.single { it.id == resourceId }
        val target = Path.of(targetText.trim()).toAbsolutePath().normalize()
        val root = data.roots.singleOrNull { PathsPolicy.key(Path.of(it.path)) == target.parent?.let(PathsPolicy::key) } ?: error("目标必须是已配置 Root 的直接子项。")
        checkRootAvailableForEdit(resource.rootId); checkRootAvailableForEdit(root.id)
        require(fs.inspect(target) == resource.kind) { "目标类型必须与原资源一致，且不是隐藏、系统或链接对象。" }
        val name = target.fileName.toString(); val key = PathsPolicy.childKey(name)
        val collision = data.resources.singleOrNull { it.rootId == root.id && PathsPolicy.childKey(it.relativePath) == key && it.id != resourceId }
        if (collision != null) {
            require(data.links[collision.id].orEmpty().isEmpty() && collision.status == Status.Active && data.reviews.any { it.resourceId == collision.id && it.reason == Reason.New }) { "目标已经独立管理或带有标签，不能合并。" }
            require(allowMerge) { "目标是无标签的新发现记录；请勾选允许合并，以保留旧记录的 ID 和标签。" }
            db.execute("DELETE FROM resources WHERE id=?", collision.id)
        }
        db.execute("UPDATE resources SET root_id=?,relative_path=?,normalized_relative_path=?,display_name=?,status='Active',last_seen_at=? WHERE id=?", root.id, name, key, name, now(), resourceId)
        resolve(resourceId)
    }
    private fun validatedName(name: String, ownName: Pair<String, String>? = null): String {
        val trimmed = name.trim(); require(trimmed.isNotEmpty()) { "标签名称或别名不能为空。" }
        val existing = db.query("SELECT n.tag_id,n.name_kind,c.name FROM tag_names n JOIN tag_names c ON c.tag_id=n.tag_id AND c.name_kind='canonical' WHERE n.normalized_name=?", normalizedName(trimmed)) { Triple(it.getString(1), it.getString(2), it.getString(3)) }.singleOrNull()
        require(existing == null || (existing.first == ownName?.first && existing.second == ownName.second)) { "名称已属于标签「${existing?.third}」，请直接使用该标签。" }
        return trimmed
    }
    @Synchronized fun createTag(name: String): String = db.transaction {
        val value = validatedName(name); val tagId = id()
        db.execute("INSERT INTO tags VALUES(?,?)", tagId, now())
        db.execute("INSERT INTO tag_names VALUES(?,?,?,?, 'canonical')", id(), tagId, value, normalizedName(value)); tagId
    }
    @Synchronized fun renameTag(tagId: String, name: String) = db.transaction {
        val value = validatedName(name, tagId to "canonical")
        db.execute("UPDATE tag_names SET name=?,normalized_name=? WHERE tag_id=? AND name_kind='canonical'", value, normalizedName(value), tagId)
    }
    @Synchronized fun addAlias(tagId: String, name: String) {
        val value = validatedName(name)
        db.execute("INSERT INTO tag_names VALUES(?,?,?,?, 'alias')", id(), tagId, value, normalizedName(value))
    }
    @Synchronized fun removeAlias(tagId: String, name: String) { db.execute("DELETE FROM tag_names WHERE tag_id=? AND normalized_name=? AND name_kind='alias'", tagId, normalizedName(name)) }
    @Synchronized fun removeTag(tagId: String) { db.execute("DELETE FROM tags WHERE id=?", tagId) }
    @Synchronized fun setTag(resources: Set<String>, tagId: String, add: Boolean) = db.transaction {
        resources.forEach { resource ->
            if (add) db.execute("INSERT OR IGNORE INTO resource_tags VALUES(?,?)", resource, tagId)
            else db.execute("DELETE FROM resource_tags WHERE resource_id=? AND tag_id=?", resource, tagId)
        }
    }
    @Synchronized override fun close() = db.close()
}
