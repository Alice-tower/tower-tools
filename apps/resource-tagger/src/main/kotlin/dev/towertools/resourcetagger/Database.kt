package dev.towertools.resourcetagger

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

class Database(path: Path) : AutoCloseable {
    private val connection: Connection
    init {
        Files.createDirectories(path.toAbsolutePath().parent)
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        try {
            execute("PRAGMA foreign_keys = ON")
            execute("PRAGMA busy_timeout = 5000")
            val version = query("PRAGMA user_version") { it.getInt(1) }.single()
            require(version <= 1) { "数据库来自更新版本的软件，请使用相应版本打开。" }
            if (version == 0) transaction {
                execute("CREATE TABLE roots(id TEXT PRIMARY KEY, path TEXT NOT NULL, normalized_path TEXT NOT NULL UNIQUE, display_name TEXT NOT NULL, availability_status TEXT NOT NULL DEFAULT '未扫描', created_at TEXT NOT NULL, last_scan_at TEXT, last_successful_scan_at TEXT, last_scan_error TEXT)")
                execute("CREATE TABLE resources(id TEXT PRIMARY KEY, root_id TEXT NOT NULL REFERENCES roots(id) ON DELETE CASCADE, relative_path TEXT NOT NULL, normalized_relative_path TEXT NOT NULL, kind TEXT NOT NULL CHECK(kind IN ('Directory','File')), display_name TEXT NOT NULL, status TEXT NOT NULL CHECK(status IN ('Active','Missing','Ignored')), created_at TEXT NOT NULL, last_seen_at TEXT, UNIQUE(root_id, normalized_relative_path))")
                execute("CREATE TABLE tags(id TEXT PRIMARY KEY, created_at TEXT NOT NULL)")
                execute("CREATE TABLE tag_names(id TEXT PRIMARY KEY, tag_id TEXT NOT NULL REFERENCES tags(id) ON DELETE CASCADE, name TEXT NOT NULL, normalized_name TEXT NOT NULL UNIQUE, name_kind TEXT NOT NULL CHECK(name_kind IN ('canonical','alias')))")
                execute("CREATE UNIQUE INDEX one_canonical ON tag_names(tag_id) WHERE name_kind='canonical'")
                execute("CREATE TABLE resource_tags(resource_id TEXT NOT NULL REFERENCES resources(id) ON DELETE CASCADE, tag_id TEXT NOT NULL REFERENCES tags(id) ON DELETE CASCADE, PRIMARY KEY(resource_id,tag_id))")
                execute("CREATE TABLE review_items(id TEXT PRIMARY KEY, resource_id TEXT NOT NULL REFERENCES resources(id) ON DELETE CASCADE, reason TEXT NOT NULL CHECK(reason IN ('New','Missing','TypeChanged')), state TEXT NOT NULL CHECK(state IN ('Pending','Resolved')), detected_at TEXT NOT NULL, resolved_at TEXT, observed_kind TEXT)")
                execute("CREATE UNIQUE INDEX pending_reason ON review_items(resource_id,reason) WHERE state='Pending'")
                execute("CREATE INDEX resources_status ON resources(status)")
                execute("CREATE INDEX tags_resources ON resource_tags(tag_id,resource_id)")
                execute("CREATE INDEX reviews_state ON review_items(state,resource_id)")
                execute("PRAGMA user_version = 1")
            }
        } catch (error: Throwable) { connection.close(); throw error }
    }
    fun execute(sql: String, vararg values: Any?) = connection.prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.execute()
        statement.updateCount.coerceAtLeast(0)
    }
    fun <T> query(sql: String, vararg values: Any?, map: (ResultSet) -> T): List<T> = connection.prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { result -> buildList { while (result.next()) add(map(result)) } }
    }
    fun <T> transaction(block: () -> T): T {
        check(connection.autoCommit) { "不支持嵌套事务。" }
        connection.autoCommit = false
        try { val result = block(); connection.commit(); return result }
        catch (error: Throwable) { connection.rollback(); throw error }
        finally { connection.autoCommit = true }
    }
    override fun close() = connection.close()
}
