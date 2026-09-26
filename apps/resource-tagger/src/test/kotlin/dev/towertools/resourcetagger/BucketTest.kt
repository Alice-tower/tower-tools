package dev.towertools.resourcetagger

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributeView
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class BucketTest {
    @TempDir lateinit var temp: Path

    @Test fun `relocation rejects excluded parent buckets without changing identity or tags`() {
        for (attribute in listOf("hidden", "system")) {
            val root = Files.createDirectory(temp.resolve(attribute))
            val a = Files.createDirectory(root.resolve("bucket-000001"))
            val b = Files.createDirectory(root.resolve("bucket-000002"))
            val original = Files.writeString(a.resolve("item"), "source")
            Library(Database(temp.resolve("$attribute.sqlite"))).use { lib ->
                val id = lib.saveRoot(null, root.toString(), "Root"); lib.scan(id)
                val resource = lib.snapshot().resources.single()
                lib.setTag(setOf(resource.id), lib.createTag("keep"), true)
                val moved = Files.move(original, b.resolve("item"))
                Files.setAttribute(b, "dos:$attribute", true)
                lib.scan(id)
                val before = lib.snapshot()
                assertFailsWith<IllegalArgumentException> { lib.relocate(resource.id, moved.toString()) }
                assertEquals(before, lib.snapshot())
                Files.setAttribute(b, "dos:$attribute", false)
                lib.relocate(resource.id, moved.toString()); lib.scan(id)
                val after = lib.snapshot()
                assertEquals(Status.Active, after.resources.single().status)
                assertEquals(resource.id, after.resources.single().id)
                assertEquals(before.links, after.links)
                assertEquals("source", Files.readString(moved))
            }
        }
    }

    @Test fun `unignore does not reactivate a resource in a hidden bucket`() {
        val root = Files.createDirectory(temp.resolve("root"))
        val bucket = Files.createDirectory(root.resolve("bucket-000001"))
        Files.writeString(bucket.resolve("item"), "source")
        Library(Database(temp.resolve("library.sqlite"))).use { lib ->
            val id = lib.saveRoot(null, root.toString(), "Root"); lib.scan(id)
            val resource = lib.snapshot().resources.single()
            lib.ignore(resource.id)
            Files.setAttribute(bucket, "dos:hidden", true)
            lib.unignore(resource.id)
            assertEquals(Status.Missing, lib.snapshot().resources.single().status)
            assertEquals(Reason.Missing, lib.snapshot().reviews.single().reason)
            Files.setAttribute(bucket, "dos:hidden", false); lib.scan(id)
            assertEquals(Status.Active, lib.snapshot().resources.single().status)
            assertEquals(resource.id, lib.snapshot().resources.single().id)
        }
    }

    @Test fun `type confirmation preserves pending review while parent bucket is excluded`() {
        val root = Files.createDirectory(temp.resolve("root"))
        val bucket = Files.createDirectory(root.resolve("bucket-000001"))
        val item = Files.writeString(bucket.resolve("item"), "")
        Library(Database(temp.resolve("library.sqlite"))).use { lib ->
            val id = lib.saveRoot(null, root.toString(), "Root"); lib.scan(id)
            val resource = lib.snapshot().resources.single()
            Files.delete(item); Files.createDirectory(item); lib.scan(id)
            val before = lib.snapshot()
            Files.setAttribute(bucket, "dos:system", true)
            assertFailsWith<IllegalArgumentException> { lib.acceptType(resource.id) }
            assertEquals(before, lib.snapshot())
            Files.setAttribute(bucket, "dos:system", false)
            lib.acceptType(resource.id); lib.scan(id)
            assertEquals(Kind.Directory, lib.snapshot().resources.single().kind)
            assertEquals(Status.Active, lib.snapshot().resources.single().status)
            assertTrue(lib.snapshot().reviews.isEmpty())
        }
    }

    @Test fun `only bucket children are indexed including overfull buckets and duplicate leaf names`() {
        val root = Files.createDirectory(temp.resolve("root"))
        val a = Files.createDirectory(root.resolve("bucket-000001"))
        val b = Files.createDirectory(root.resolve("bucket-000003"))
        Files.writeString(root.resolve("loose.txt"), "not managed")
        for (name in listOf("other", "bucket-1", "bucket-000000", "bucket-000002.tmp")) {
            Files.createDirectory(root.resolve(name)); Files.writeString(root.resolve(name).resolve("ignored.txt"), "")
        }
        Files.writeString(a.resolve("same.txt"), "a"); Files.writeString(b.resolve("same.txt"), "b")
        for (i in 0 until 101) Files.writeString(a.resolve("$i.txt"), "")
        Files.createDirectories(b.resolve("comic/deep")); Files.writeString(b.resolve("comic/deep/image.txt"), "")
        val hidden = Files.writeString(b.resolve("hidden"), "")
        Files.getFileAttributeView(hidden, DosFileAttributeView::class.java).setHidden(true)
        Library(Database(temp.resolve("library.sqlite"))).use { lib ->
            val id = lib.saveRoot(null, root.toString(), "Root"); lib.scan(id)
            assertEquals(104, lib.queryPage(Query()).total)
            val same = lib.snapshot().resources.filter { it.name == "same.txt" }
            assertEquals(2, same.size); assertNotEquals(same[0].id, same[1].id)
            assertEquals(setOf("a", "b"), same.map { Files.readString(PathsPolicy.actual(lib.overview().roots.single(), it)) }.toSet())
            assertEquals(2, lib.queryPage(Query(text = "bucket-000003/")).total)
            assertEquals(setOf("comic", "same.txt"), lib.queryPage(Query(text = "bucket-000003/")).snapshot.resources.map { it.name }.toSet())
            assertEquals("not managed", Files.readString(root.resolve("loose.txt")))
        }
    }

    @Test fun `missing bucket marks its records missing and recovery retains identities and tags`() {
        val root = Files.createDirectory(temp.resolve("root"))
        val bucket = Files.createDirectory(root.resolve("bucket-000001"))
        Files.writeString(bucket.resolve("item"), "")
        Library(Database(temp.resolve("library.sqlite"))).use { lib ->
            val rootId = lib.saveRoot(null, root.toString(), "Root"); lib.scan(rootId)
            val resource = lib.snapshot().resources.single(); val tag = lib.createTag("keep"); lib.setTag(setOf(resource.id), tag, true)
            Files.move(bucket, temp.resolve("offline-bucket")); lib.scan(rootId)
            assertEquals(Status.Missing, lib.snapshot().resources.single().status)
            Files.move(temp.resolve("offline-bucket"), bucket); lib.scan(rootId)
            assertEquals(resource.id, lib.snapshot().resources.single().id)
            assertEquals(Status.Active, lib.snapshot().resources.single().status)
            assertEquals(setOf(tag), lib.snapshot().links[resource.id])
        }
    }

    @Test fun `cross bucket relocation keeps tags and rejects paths outside the two level rule`() {
        val root = Files.createDirectory(temp.resolve("root"))
        val a = Files.createDirectory(root.resolve("bucket-000001"))
        val b = Files.createDirectory(root.resolve("bucket-000002"))
        val original = Files.writeString(a.resolve("same.txt"), "original")
        Library(Database(temp.resolve("library.sqlite"))).use { lib ->
            val id = lib.saveRoot(null, root.toString(), "Root"); lib.scan(id)
            val resource = lib.snapshot().resources.single(); val tag = lib.createTag("keep"); lib.setTag(setOf(resource.id), tag, true)
            val moved = Files.move(original, b.resolve("same.txt"))
            lib.relocate(resource.id, moved.toString())
            assertEquals("bucket-000002/same.txt", lib.snapshot().resources.single().relativePath)
            assertEquals(setOf(tag), lib.snapshot().links[resource.id]); lib.scan(id)
            assertEquals(resource.id, lib.snapshot().resources.single().id)
            assertFails { lib.relocate(resource.id, Files.writeString(root.resolve("loose"), "").toString()) }
            for (path in listOf("same.txt", "bucket-000001/../x", "../x", "bucket-000001/a/b", "bucket-000000/a", "bucket-000001/C:escape")) {
                assertFails { PathsPolicy.relativeKey(path) }
            }
            assertEquals("bucket-000001/file", PathsPolicy.relativeKey("BUCKET-000001\\File"))
        }
    }

    @Test fun `failure after visiting one bucket never commits a partial resource inventory`() {
        val root = Files.createDirectory(temp.resolve("root"))
        val a = Files.createDirectory(root.resolve("bucket-000001"))
        val b = Files.createDirectory(root.resolve("bucket-000002"))
        Files.writeString(a.resolve("a"), ""); Files.writeString(b.resolve("b"), "")
        var fail = false
        val fs = object : ResourceFileSystem {
            override fun inspect(path: Path) = LocalFileSystem().inspect(path)
            override fun scan(path: Path): List<Found> {
                if (fail) {
                    Files.newDirectoryStream(a).use { it.toList() }
                    throw java.nio.file.AccessDeniedException(b.toString())
                }
                return LocalFileSystem().scan(path)
            }
        }
        Library(Database(temp.resolve("library.sqlite")), fs).use { lib ->
            val id = lib.saveRoot(null, root.toString(), "Root"); lib.scan(id)
            val before = lib.snapshot(); fail = true
            assertFails { lib.scan(id) }
            assertEquals(before.resources, lib.snapshot().resources)
            assertEquals(before.reviews, lib.snapshot().reviews)
            assertEquals(before.roots.single().lastSuccess, lib.overview().roots.single().lastSuccess)
        }
    }
}
