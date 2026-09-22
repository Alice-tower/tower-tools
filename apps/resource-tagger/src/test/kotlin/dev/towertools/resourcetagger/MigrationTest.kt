package dev.towertools.resourcetagger

import java.nio.file.Path
import java.sql.DriverManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class MigrationTest {
    @TempDir lateinit var temp: Path
    @Test fun `version one migration preserves identifiers tags aliases statuses and review history`() {
        val path = temp.resolve("v1.sqlite")
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            val statements = listOf(
                "CREATE TABLE roots(id TEXT PRIMARY KEY,path TEXT NOT NULL,normalized_path TEXT NOT NULL UNIQUE,display_name TEXT NOT NULL,availability_status TEXT NOT NULL DEFAULT '未扫描',created_at TEXT NOT NULL,last_scan_at TEXT,last_successful_scan_at TEXT,last_scan_error TEXT)",
                "CREATE TABLE resources(id TEXT PRIMARY KEY,root_id TEXT NOT NULL REFERENCES roots(id) ON DELETE CASCADE,relative_path TEXT NOT NULL,normalized_relative_path TEXT NOT NULL,kind TEXT NOT NULL,display_name TEXT NOT NULL,status TEXT NOT NULL,created_at TEXT NOT NULL,last_seen_at TEXT,UNIQUE(root_id,normalized_relative_path))",
                "CREATE TABLE tags(id TEXT PRIMARY KEY,created_at TEXT NOT NULL)",
                "CREATE TABLE tag_names(id TEXT PRIMARY KEY,tag_id TEXT NOT NULL REFERENCES tags(id) ON DELETE CASCADE,name TEXT NOT NULL,normalized_name TEXT NOT NULL UNIQUE,name_kind TEXT NOT NULL)",
                "CREATE TABLE resource_tags(resource_id TEXT NOT NULL REFERENCES resources(id) ON DELETE CASCADE,tag_id TEXT NOT NULL REFERENCES tags(id) ON DELETE CASCADE,PRIMARY KEY(resource_id,tag_id))",
                "CREATE TABLE review_items(id TEXT PRIMARY KEY,resource_id TEXT NOT NULL REFERENCES resources(id) ON DELETE CASCADE,reason TEXT NOT NULL,state TEXT NOT NULL,detected_at TEXT NOT NULL,resolved_at TEXT,observed_kind TEXT)",
                "CREATE UNIQUE INDEX pending_reason ON review_items(resource_id,reason) WHERE state='Pending'",
                "INSERT INTO roots VALUES('root','C:\\Example','c:\\example','Root','扫描失败','created','attempt','success','offline')",
                "INSERT INTO resources VALUES('r1','root','ＣＡＦＥ́.txt','ｃａｆｅ́.txt','File','ＣＡＦＥ́.txt','Missing','created','seen')",
                "INSERT INTO resources VALUES('r2','root','ignored','ignored','Directory','ignored','Ignored','created','seen')",
                "INSERT INTO tags VALUES('t1','created')",
                "INSERT INTO tag_names VALUES('n1','t1','资料','资料','canonical')",
                "INSERT INTO tag_names VALUES('n2','t1','Docs','docs','alias')",
                "INSERT INTO resource_tags VALUES('r1','t1')",
                "INSERT INTO review_items VALUES('old','r1','New','Resolved','created','resolved',NULL)",
                "INSERT INTO review_items VALUES('new','r1','Missing','Pending','detected',NULL,NULL)",
                "PRAGMA user_version=1",
            )
            connection.createStatement().use { statement -> statements.forEach { statement.execute(it) } }
        }
        val db = Database(path)
        Library(db).use { lib ->
            val state = lib.snapshot()
            assertEquals(listOf("r1", "r2"), state.resources.map { it.id })
            assertEquals(listOf(Status.Missing, Status.Ignored), state.resources.map { it.status })
            assertEquals("created", state.resources.first().created); assertEquals("seen", state.resources.first().lastSeen)
            assertEquals(setOf("t1"), state.links["r1"]); assertEquals(listOf("Docs"), state.tags.single().aliases)
            assertEquals("new", state.reviews.single().id); assertEquals("success", state.roots.single().lastSuccess)
            assertEquals(1, lib.queryPage(Query(text = "café")).total)
            assertEquals(2, db.query("SELECT COUNT(*) FROM review_items") { it.getInt(1) }.single())
            assertEquals(2, db.query("PRAGMA user_version") { it.getInt(1) }.single())
        }
        Library(Database(path)).use { assertEquals(1, it.queryPage(Query(text = "CAFÉ")).total) }
    }
}
