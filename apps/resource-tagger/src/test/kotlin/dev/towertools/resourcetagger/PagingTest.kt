package dev.towertools.resourcetagger

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PagingTest {
    @TempDir lateinit var temp: Path
    private class FakeFs(var items: List<Found>) : ResourceFileSystem {
        override fun scan(path: Path) = items
        override fun inspect(path: Path) = items.find { it.name == path.fileName.toString() }?.kind
    }
    @Test fun `SQL pages equal full reference query for Unicode literal search all filters and stable ordering`() {
        val names = listOf("ＣＡＦＥ́.txt", "café.txt", "100%_done.txt", "😀file", "\uE000file") + (0..449).map { "资源-%04d.txt".format(it) }
        val fs = FakeFs(names.mapIndexed { i, name -> Found(name, if (i % 3 == 0) Kind.Directory else Kind.File) })
        Library(Database(temp.resolve("db.sqlite")), fs).use { lib ->
            val a = lib.saveRoot(null, Files.createDirectory(temp.resolve("a")).toString(), "A"); lib.scan(a)
            val b = lib.saveRoot(null, Files.createDirectory(temp.resolve("b")).toString(), "B"); lib.scan(b)
            val before = lib.snapshot().resources
            val t1 = lib.createTag("游戏"); val t2 = lib.createTag("未完成"); val t3 = lib.createTag("隐藏")
            lib.setTag(before.filterIndexed { i, _ -> i % 2 == 0 }.map { it.id }.toSet(), t1, true)
            lib.setTag(before.filterIndexed { i, _ -> i % 3 == 0 }.map { it.id }.toSet(), t2, true)
            lib.setTag(before.filterIndexed { i, _ -> i % 5 == 0 }.map { it.id }.toSet(), t3, true)
            before.take(10).forEach { lib.ignore(it.id) }
            fs.items = fs.items.dropLast(11); lib.scan(a)
            before.filter { it.rootId == b }.takeLast(12).forEach { lib.acknowledge(it.id) }
            val snapshot = lib.snapshot()
            val queries = listOf(Query(), Query(reviewOnly = true), Query(status = Status.Ignored), Query(status = Status.Missing), Query(rootId = a), Query(rootId = b, kind = Kind.Directory), Query(untagged = true), Query(text = "café"), Query(text = "%_"), Query(text = "😀"), Query(tags = mapOf(t1 to TagFilter.Include, t2 to TagFilter.Include, t3 to TagFilter.Exclude)), Query(tags = mapOf(t1 to TagFilter.Exclude)), Query(text = "资源", reviewOnly = true, rootId = a, kind = Kind.File, tags = mapOf(t3 to TagFilter.Include)), Query(tags = mapOf("missing-tag" to TagFilter.Include)))
            for (query in queries) {
                val expected = query.apply(snapshot)
                val gathered = buildList {
                    var offset = 0
                    do { val page = lib.queryPage(query, offset, 37); assertEquals(expected.size, page.total); assertTrue(page.snapshot.resources.size <= 37); addAll(page.snapshot.resources); offset += 37 } while (offset < expected.size)
                }
                assertEquals(expected, gathered, query.toString())
                assertEquals(expected.map { it.id }.toSet(), lib.matchingIds(query))
                assertEquals(expected.filter { it.id in before.take(15).map { r -> r.id } }.map { it.id }.toSet(), lib.retainedSelection(query, before.take(15).map { it.id }.toSet()))
            }
            assertEquals(0, lib.queryPage(Query(text = "not-found"), 200).offset)
            val pastEnd = lib.queryPage(Query(), 100000); assertTrue(pastEnd.snapshot.resources.isNotEmpty())
            assertEquals(snapshot.links.values.count { t1 in it }, lib.overview().tagCounts[t1])
            assertEquals(snapshot.resources.count { it.rootId == a }, lib.overview().rootCounts[a])
            val selected = before.take(400).map { it.id }.toSet()
            assertEquals(snapshot.links.filterKeys { it in selected }.values.count { t1 in it }, lib.selectionCounts(selected)[t1])
        }
    }
    @Test fun `ten thousand resources retain atomic scan review and cross-page tag semantics`() {
        val fs = FakeFs((0 until 10000).map { Found("item-%05d".format(it), Kind.File) })
        val db = Database(temp.resolve("large.sqlite"))
        Library(db, fs).use { lib ->
            val root = lib.saveRoot(null, Files.createDirectory(temp.resolve("root")).toString(), "Root"); lib.scan(root)
            val ids = lib.matchingIds(Query()); assertEquals(10000, ids.size)
            val tag = lib.createTag("All"); lib.setTag(ids, tag, true)
            assertEquals(10000, lib.selectionCounts(ids)[tag]); assertEquals(200, lib.queryPage(Query()).snapshot.resources.size)
            val original = lib.snapshot().resources.associateBy { it.id }
            fs.items = emptyList(); lib.scan(root)
            assertEquals(10000, lib.queryPage(Query(status = Status.Missing)).total)
            assertEquals(10000, lib.overview().pendingCount)
            val first = ids.first(); lib.acknowledge(first); lib.scan(root); assertEquals(9999, lib.overview().pendingCount)
            fs.items = original.values.map { Found(it.name, it.kind) }; lib.scan(root)
            assertEquals(ids, lib.matchingIds(Query())); assertEquals(0, lib.overview().pendingCount)
            assertEquals(10000, lib.overview().tagCounts[tag])
            // Inject a write failure partway through a large scan: earlier updates must roll back.
            db.execute("CREATE TRIGGER reject_change BEFORE UPDATE ON resources WHEN NEW.relative_path=? BEGIN SELECT RAISE(ABORT,'fail'); END".replace("?", "'${original.values.last().relativePath}'"))
            val stable = lib.snapshot()
            assertFails { lib.scan(root) }
            assertEquals(stable.resources, lib.snapshot().resources)
            assertEquals(stable.links, lib.snapshot().links)
            assertEquals(stable.reviews, lib.snapshot().reviews)
        }
    }
}
