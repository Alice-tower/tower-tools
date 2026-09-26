package dev.towertools.resourcetagger

import java.nio.file.Path
import java.sql.DriverManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class MigrationTest {
    @TempDir lateinit var temp: Path
    @Test fun `legacy databases are rejected without deleting or migrating their data`() {
        Class.forName("org.sqlite.JDBC")
        for (version in listOf(1, 2, 4)) {
            val path = temp.resolve("v$version.sqlite")
            DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE sentinel(value TEXT)")
                    statement.execute("INSERT INTO sentinel VALUES('keep')")
                    statement.execute("PRAGMA user_version=$version")
                }
            }
            assertFailsWith<IllegalArgumentException> { Database(path) }
            DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT value FROM sentinel").use { assertTrue(it.next()); assertEquals("keep", it.getString(1)) }
                    statement.executeQuery("PRAGMA user_version").use { assertTrue(it.next()); assertEquals(version, it.getInt(1)) }
                }
            }
        }
    }
}
