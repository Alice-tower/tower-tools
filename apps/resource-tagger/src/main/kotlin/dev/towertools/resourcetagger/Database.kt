package dev.towertools.resourcetagger

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.PreparedStatement
import org.sqlite.Function
import org.sqlite.Collation

class Database(path: Path) : AutoCloseable {
    private val connection: Connection
    private val statements = object : LinkedHashMap<String, PreparedStatement>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PreparedStatement>): Boolean {
            if (size <= 64) return false
            eldest.value.close(); return true
        }
    }
    init {
        Files.createDirectories(path.toAbsolutePath().parent)
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        try {
            Collation.create(connection, "JAVA_TEXT", object : Collation() {
                override fun xCompare(a: String, b: String): Int = a.compareTo(b)
            })
            Function.create(connection, "normalize_name", object : Function() {
                override fun xFunc() { result(normalizedName(value_text(0))) }
            }, 1, Function.FLAG_DETERMINISTIC)
            execute("PRAGMA foreign_keys = ON")
            execute("PRAGMA busy_timeout = 5000")
            val version = query("PRAGMA user_version") { it.getInt(1) }.single()
            require(version <= 2) { "数据库来自更新版本的软件，请使用相应版本打开。" }
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
            if (version < 2) transaction {
                execute("ALTER TABLE resources ADD COLUMN display_key TEXT NOT NULL DEFAULT ''")
                execute("ALTER TABLE resources ADD COLUMN path_key TEXT NOT NULL DEFAULT ''")
                execute("UPDATE resources SET display_key=normalize_name(display_name),path_key=normalize_name(relative_path)")
                execute("CREATE TRIGGER resource_search_insert AFTER INSERT ON resources WHEN NEW.display_key<>normalize_name(NEW.display_name) OR NEW.path_key<>normalize_name(NEW.relative_path) BEGIN UPDATE resources SET display_key=normalize_name(NEW.display_name),path_key=normalize_name(NEW.relative_path) WHERE id=NEW.id; END")
                execute("CREATE TRIGGER resource_search_update AFTER UPDATE OF display_name,relative_path ON resources WHEN OLD.display_name<>NEW.display_name OR OLD.relative_path<>NEW.relative_path BEGIN UPDATE resources SET display_key=normalize_name(NEW.display_name),path_key=normalize_name(NEW.relative_path) WHERE id=NEW.id; END")
                execute("CREATE INDEX resource_order ON resources(display_key COLLATE JAVA_TEXT,id COLLATE JAVA_TEXT)")
                execute("CREATE INDEX root_resource_order ON resources(root_id,display_key COLLATE JAVA_TEXT,id COLLATE JAVA_TEXT)")
                execute("CREATE INDEX reviews_resource ON review_items(resource_id)")
                execute("PRAGMA user_version = 2")
            }
        } catch (error: Throwable) { connection.close(); throw error }
    }
    private fun statement(sql: String): PreparedStatement = statements.getOrPut(sql) { connection.prepareStatement(sql) }.also { it.clearParameters() }
    fun execute(sql: String, vararg values: Any?): Int {
        val statement = statement(sql)
        values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.execute()
        return statement.updateCount.coerceAtLeast(0)
    }
    fun <T> query(sql: String, vararg values: Any?, map: (ResultSet) -> T): List<T> {
        val statement = statement(sql)
        values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        return statement.executeQuery().use { result -> buildList { while (result.next()) add(map(result)) } }
    }
    fun <T> transaction(block: () -> T): T {
        check(connection.autoCommit) { "不支持嵌套事务。" }
        connection.autoCommit = false
        try { val result = block(); connection.commit(); return result }
        catch (error: Throwable) { connection.rollback(); throw error }
        finally { connection.autoCommit = true }
    }
    override fun close() { statements.values.forEach { it.close() }; statements.clear(); connection.close() }
}
