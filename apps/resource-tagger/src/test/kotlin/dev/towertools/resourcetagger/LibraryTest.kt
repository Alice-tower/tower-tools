package dev.towertools.resourcetagger

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributeView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LibraryTest {
    @TempDir lateinit var temp: Path
    private fun root(name: String = "Root") = Files.createDirectory(temp.resolve(name))
    private fun library(fs: ResourceFileSystem = LocalFileSystem()) = Library(Database(temp.resolve("data/library.sqlite")), fs)
    private fun Library.add(path: Path) = saveRoot(null, path.toString(), path.fileName.toString())
    @Test fun `single level scan excludes hidden system and links without reading content`() {
        val path = root(); Files.createDirectories(path.resolve("A")); Files.writeString(path.resolve("A/inner.txt"), "inside"); Files.writeString(path.resolve("B 中文 & 空格.txt"), "file")
        val hidden = Files.writeString(path.resolve("hidden.txt"), ""); Files.getFileAttributeView(hidden, DosFileAttributeView::class.java).setHidden(true)
        val system = Files.writeString(path.resolve("system.txt"), ""); Files.getFileAttributeView(system, DosFileAttributeView::class.java).setSystem(true)
        library().use { lib ->
            val rootId = lib.add(path); lib.scan(rootId)
            assertEquals(setOf("A", "B 中文 & 空格.txt"), lib.snapshot().resources.map { it.name }.toSet())
            assertEquals(setOf(Kind.Directory, Kind.File), lib.snapshot().resources.map { it.kind }.toSet())
            assertEquals(2, lib.snapshot().reviews.size)
            lib.scan(rootId); assertEquals(2, lib.snapshot().reviews.size)
        }
    }
    @Test fun `failed or partial scan preserves records reviews and last success`() {
        val path = root(); Files.writeString(path.resolve("one"), "")
        var fail = false
        val fs = object : ResourceFileSystem {
            override fun inspect(path: Path) = LocalFileSystem().inspect(path)
            override fun scan(path: Path): List<Found> { if (fail) { LocalFileSystem().scan(path); error("模拟枚举中途无权限") }; return LocalFileSystem().scan(path) }
        }
        library(fs).use { lib ->
            val rootId = lib.add(path); lib.scan(rootId); val before = lib.snapshot(); fail = true
            assertFailsWith<IllegalStateException> { lib.scan(rootId) }
            val after = lib.snapshot(); assertEquals(before.resources, after.resources); assertEquals(before.reviews, after.reviews)
            assertEquals(before.roots.single().lastSuccess, after.roots.single().lastSuccess)
            assertEquals("扫描失败", after.roots.single().availability)
        }
    }
    @Test fun `missing acknowledgement recovery and repeated loss preserve identity and tags`() {
        val path = root(); val file = Files.writeString(path.resolve("one"), "")
        library().use { lib ->
            val rootId = lib.add(path); lib.scan(rootId); val resource = lib.snapshot().resources.single(); val tag = lib.createTag("资料"); lib.setTag(setOf(resource.id), tag, true)
            lib.acknowledge(resource.id); Files.delete(file); lib.scan(rootId)
            assertEquals(Status.Missing, lib.snapshot().resources.single().status); assertEquals(Reason.Missing, lib.snapshot().reviews.single().reason)
            lib.acknowledge(resource.id); lib.scan(rootId); assertTrue(lib.snapshot().reviews.isEmpty())
            Files.writeString(file, "new"); lib.scan(rootId)
            val recovered = lib.snapshot(); assertEquals(Status.Active, recovered.resources.single().status); assertEquals(resource.id, recovered.resources.single().id); assertEquals(resource.created, recovered.resources.single().created); assertEquals(setOf(tag), recovered.links[resource.id])
            Files.delete(file); lib.scan(rootId); assertEquals(Reason.Missing, lib.snapshot().reviews.single().reason)
        }
    }
    @Test fun `offline root never marks resources missing`() {
        val path = root(); Files.writeString(path.resolve("one"), "")
        library().use { lib -> val rootId = lib.add(path); lib.scan(rootId); Files.move(path, temp.resolve("offline")); assertFails { lib.scan(rootId) }; assertEquals(Status.Active, lib.snapshot().resources.single().status) }
    }
    @Test fun `type replacement must be confirmed and revalidated`() {
        val path = root(); val item = Files.writeString(path.resolve("one"), "")
        library().use { lib ->
            val rootId = lib.add(path); lib.scan(rootId); val original = lib.snapshot().resources.single(); val tag = lib.createTag("旧标签"); lib.setTag(setOf(original.id), tag, true)
            Files.delete(item); Files.createDirectory(item); lib.scan(rootId); lib.scan(rootId)
            assertEquals(Reason.TypeChanged, lib.snapshot().reviews.single().reason); assertEquals(Kind.File, lib.snapshot().resources.single().kind)
            assertFails { lib.acknowledge(original.id) }
            lib.acceptType(original.id); val after = lib.snapshot(); assertEquals(Kind.Directory, after.resources.single().kind); assertEquals(original.id, after.resources.single().id); assertEquals(setOf(tag), after.links[original.id]); assertTrue(after.reviews.isEmpty())
        }
    }
    @Test fun `type changed object disappears then produces missing review`() {
        val path = root(); val item = Files.writeString(path.resolve("one"), "")
        library().use { lib -> val rootId = lib.add(path); lib.scan(rootId); Files.delete(item); Files.createDirectory(item); lib.scan(rootId); Files.delete(item); lib.scan(rootId); assertEquals(Reason.Missing, lib.snapshot().reviews.single().reason) }
    }
    @Test fun `ignore suppresses scans and unignore rechecks type or absence`() {
        val path = root(); val item = Files.writeString(path.resolve("one"), "")
        library().use { lib ->
            val rootId = lib.add(path); lib.scan(rootId); val id = lib.snapshot().resources.single().id; lib.ignore(id); Files.delete(item); lib.scan(rootId)
            assertTrue(lib.snapshot().reviews.isEmpty()); assertTrue(Query().apply(lib.snapshot()).isEmpty()); assertEquals(1, Query(status = Status.Ignored).apply(lib.snapshot()).size)
            lib.unignore(id); assertEquals(Status.Missing, lib.snapshot().resources.single().status)
            lib.ignore(id); Files.createDirectory(item); lib.scan(rootId); assertTrue(lib.snapshot().reviews.isEmpty()); lib.unignore(id); assertEquals(Reason.TypeChanged, lib.snapshot().reviews.single().reason)
        }
    }
    @Test fun `relocation keeps old identity and requires explicit merge permission`() {
        val path = root(); val item = Files.writeString(path.resolve("old.txt"), "")
        library().use { lib ->
            val rootId = lib.add(path); lib.scan(rootId); val old = lib.snapshot().resources.single(); val tag = lib.createTag("keep"); lib.setTag(setOf(old.id), tag, true)
            val target = Files.move(item, path.resolve("new.txt")); lib.scan(rootId)
            assertFails { lib.relocate(old.id, target.toString()) }; assertEquals(2, lib.snapshot().resources.size)
            lib.relocate(old.id, target.toString(), true)
            val after = lib.snapshot(); assertEquals(1, after.resources.size); assertEquals(old.id, after.resources.single().id); assertEquals("new.txt", after.resources.single().name); assertEquals(setOf(tag), after.links[old.id]); assertTrue(after.reviews.isEmpty())
        }
    }
    @Test fun `relocation rejects tagged or confirmed targets descendants and mismatched types`() {
        val path = root(); Files.writeString(path.resolve("old"), ""); val target = Files.writeString(path.resolve("target"), ""); val folder = Files.createDirectory(path.resolve("folder")); val inner = Files.writeString(folder.resolve("inner"), "")
        library().use { lib ->
            val rootId = lib.add(path); lib.scan(rootId); val data = lib.snapshot(); val old = data.resources.single { it.name == "old" }; val other = data.resources.single { it.name == "target" }
            val tag = lib.createTag("semantic"); lib.setTag(setOf(other.id), tag, true)
            assertFails { lib.relocate(old.id, target.toString(), true) }; lib.setTag(setOf(other.id), tag, false); lib.acknowledge(other.id)
            assertFails { lib.relocate(old.id, target.toString(), true) }; assertFails { lib.relocate(old.id, folder.toString()) }; assertFails { lib.relocate(old.id, inner.toString()) }
            assertEquals(3, lib.snapshot().resources.size)
        }
    }
    @Test fun `root relocation and offline rename retain identities`() {
        val path = root(); Files.writeString(path.resolve("one"), "")
        library().use { lib -> val rootId = lib.add(path); lib.scan(rootId); val original = lib.snapshot().resources.single(); val moved = Files.move(path, temp.resolve("moved")); lib.renameRoot(rootId, "离线改名"); lib.saveRoot(rootId, moved.toString(), "新位置"); lib.scan(rootId); assertEquals(original.id, lib.snapshot().resources.single().id) }
    }
    @Test fun `root duplicate nesting and disk root rejected`() {
        assertFails { PathsPolicy.root("") }
        val path = root(); val nested = Files.createDirectory(path.resolve("nested"))
        library().use { lib -> lib.add(path); assertFails { lib.add(nested) }; assertFails { lib.add(temp) }; assertFails { lib.add(path.resolve(".")) }; assertFails { lib.add(path.root) } }
        assertFails { PathsPolicy.childKey("../outside") }; assertFails { PathsPolicy.childKey("..\\outside") }; assertFails { PathsPolicy.childKey("C:escape") }
    }
    @Test fun `tag aliases share normalized unique namespace and preserve relationships`() {
        val path = root(); Files.writeString(path.resolve("one"), "")
        library().use { lib ->
            val rootId = lib.add(path); lib.scan(rootId); val resource = lib.snapshot().resources.single(); val tag = lib.createTag(" Cyberpunk "); lib.addAlias(tag, "赛博朋克"); lib.addAlias(tag, "café")
            assertTrue(lib.snapshot().tags.single().matches("赛博")); assertFails { lib.createTag("ＣＹＢＥＲＰＵＮＫ") }; assertFails { lib.createTag("cafe\u0301") }; assertFails { lib.addAlias(tag, " cyberpunk ") }; assertFails { lib.createTag("  ") }
            lib.setTag(setOf(resource.id), tag, true); lib.setTag(setOf(resource.id), tag, true); lib.renameTag(tag, "Future"); assertEquals(setOf(tag), lib.snapshot().links[resource.id])
            lib.removeAlias(tag, "赛博朋克"); assertFalse(lib.snapshot().tags.single().matches("赛博"))
        }
    }
    @Test fun `AND NOT query combines scope status kind text and untagged`() {
        val path = root(); Files.createDirectory(path.resolve("Alpha")); Files.writeString(path.resolve("Beta.txt"), ""); Files.writeString(path.resolve("Gamma.txt"), "")
        library().use { lib ->
            val rootId = lib.add(path); lib.scan(rootId); val data = lib.snapshot(); val alpha = data.resources.single { it.name == "Alpha" }; val beta = data.resources.single { it.name == "Beta.txt" }; val one = lib.createTag("one"); val two = lib.createTag("two"); val excluded = lib.createTag("done")
            lib.setTag(setOf(alpha.id, beta.id), one, true); lib.setTag(setOf(alpha.id, beta.id), two, true); lib.setTag(setOf(beta.id), excluded, true)
            val query = Query(rootId = rootId, tags = mapOf(one to TagFilter.Include, two to TagFilter.Include, excluded to TagFilter.Exclude))
            assertEquals(listOf(alpha.id), query.apply(lib.snapshot()).map { it.id }); assertTrue(query.copy(kind = Kind.File).apply(lib.snapshot()).isEmpty())
            assertEquals(2, Query(tags = mapOf(one to TagFilter.Include)).apply(lib.snapshot()).size)
            assertEquals("Gamma.txt", Query(untagged = true).apply(lib.snapshot()).single().name)
            assertEquals(beta.id, Query(text = "BETA.TXT", kind = Kind.File).apply(lib.snapshot()).single().id)
        }
    }
    @Test fun `database deletions cascade without changing filesystem and survive restart`() {
        val path = root(); val item = Files.writeString(path.resolve("one"), "content"); var rootId = ""; var tag = ""
        library().use { lib -> rootId = lib.add(path); lib.scan(rootId); tag = lib.createTag("keep"); lib.setTag(lib.snapshot().resources.map { it.id }.toSet(), tag, true) }
        library().use { lib ->
            assertEquals(1, lib.snapshot().resources.size); assertEquals(setOf(tag), lib.snapshot().links.values.single()); lib.removeTag(tag); assertTrue(lib.snapshot().links.isEmpty())
            tag = lib.createTag("survive"); lib.removeResource(lib.snapshot().resources.single().id); assertTrue(lib.snapshot().reviews.isEmpty()); assertEquals("content", Files.readString(item)); lib.scan(rootId); assertEquals(1, lib.snapshot().resources.size)
            lib.removeRoot(rootId); assertTrue(lib.snapshot().resources.isEmpty()); assertTrue(lib.snapshot().reviews.isEmpty()); assertEquals(tag, lib.snapshot().tags.single().id); assertTrue(Files.exists(item))
        }
    }
    @Test fun `concurrent scan on same root is refused`() {
        val path = root(); val started = CountDownLatch(1); val release = CountDownLatch(1)
        val fs = object : ResourceFileSystem { override fun inspect(path: Path) = Kind.File; override fun scan(path: Path): List<Found> { started.countDown(); check(release.await(5, TimeUnit.SECONDS)); return emptyList() } }
        library(fs).use { lib ->
            val rootId = lib.add(path); val worker = Thread { lib.scan(rootId) }; worker.start()
            try { assertTrue(started.await(5, TimeUnit.SECONDS)); assertFails { lib.scan(rootId) }; assertFails { lib.removeRoot(rootId) } } finally { release.countDown(); worker.join(5000) }
            assertFalse(worker.isAlive)
        }
    }
    @Test fun `schema guards foreign keys and future versions`() {
        val file = temp.resolve("schema.sqlite")
        Database(file).use { db -> assertFails { db.execute("INSERT INTO resource_tags VALUES('missing','missing')") }; assertEquals(1, db.query("PRAGMA user_version") { it.getInt(1) }.single()); db.execute("PRAGMA user_version=2") }
        assertFails { Database(file) }
    }
    @Test fun `navigator quotes are handled as argument boundaries without invoking a shell`() {
        val path = temp.resolve("中文 & 空格,资料.txt")
        assertEquals(listOf("explorer.exe", "/select,", path.toString()), Navigator.command(path, false))
        assertEquals(listOf("explorer.exe", path.toString()), Navigator.command(path, true))
    }

    @Test fun `database failure during scan rolls back resources tags and pending reviews`() {
        val path = root(); Files.writeString(path.resolve("one"), "")
        val db = Database(temp.resolve("atomic.sqlite"))
        Library(db).use { lib ->
            val rootId = lib.add(path); lib.scan(rootId); val before = lib.snapshot()
            Files.writeString(path.resolve("two"), "")
            db.execute("CREATE TRIGGER fail_insert BEFORE INSERT ON resources BEGIN SELECT RAISE(ABORT, 'simulated write failure'); END")
            assertFails { lib.scan(rootId) }
            assertEquals(before.resources, lib.snapshot().resources)
            assertEquals(before.reviews, lib.snapshot().reviews)
            assertEquals(before.roots.single().lastSuccess, lib.snapshot().roots.single().lastSuccess)
            db.execute("DROP TRIGGER fail_insert")
            lib.scan(rootId); assertEquals(2, lib.snapshot().resources.size)
        }
    }

    @Test fun `ambiguous case paths abort scan and retain registry`() {
        val path = root()
        val fs = object : ResourceFileSystem { override fun inspect(path: Path) = Kind.File; override fun scan(path: Path) = listOf(Found("A", Kind.File), Found("a", Kind.Directory)) }
        library(fs).use { lib -> val rootId = lib.add(path); assertFails { lib.scan(rootId) }; assertTrue(lib.snapshot().resources.isEmpty()); assertTrue(lib.snapshot().reviews.isEmpty()) }
    }
}
