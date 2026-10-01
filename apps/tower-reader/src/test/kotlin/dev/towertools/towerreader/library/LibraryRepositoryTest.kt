package dev.towertools.towerreader.library

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LibraryRepositoryTest {
    @TempDir lateinit var directory:Path
    @Test fun deduplicationPersistenceAndManagedRemoval() {
        val source=Files.writeString(directory.resolve("original.txt"),"第一本小说")
        val renamed=Files.writeString(directory.resolve("renamed.txt"),"第一本小说")
        val otherDirectory=Files.createDirectory(directory.resolve("other"))
        val other=Files.writeString(otherDirectory.resolve("original.txt"),"另一部小说")
        val data=directory.resolve("data")
        lateinit var id:String
        LibraryRepository(data).use { repo ->
            val report=repo.importBooks(listOf(source,source,renamed,other))
            assertEquals(2,report.added); assertEquals(2,report.duplicates); assertTrue(report.failures.isEmpty())
            val book=repo.listBooks().first { Files.readString(repo.bookPath(it))=="第一本小说" }; id=book.id
            repo.updateBook(id,"自定义标题","文学")
            repo.saveProgress(id,"chapter:2",0.42); repo.markOpened(id); repo.setSetting("font","22")
        }
        LibraryRepository(data).use { repo ->
            val book=repo.listBooks().first { it.id==id }
            assertEquals("original.txt",book.sourceName); assertEquals("自定义标题",book.title); assertEquals("文学",book.category)
            assertEquals(0.42,book.progress); assertEquals("chapter:2",book.locator); assertTrue(book.lastOpenedAt>0)
            assertEquals("22",repo.setting("font","18"))
            assertEquals(1,repo.importBooks(listOf(renamed)).duplicates)
            val managed=repo.bookPath(book); repo.removeBook(id); assertFalse(Files.exists(managed))
            assertEquals("第一本小说",Files.readString(source)); assertEquals("第一本小说",Files.readString(renamed))
        }
    }
    @Test fun failureDoesNotStopBatchOrLeaveTemporaryFiles() {
        val bad=Files.writeString(directory.resolve("bad.epub"),"not a zip")
        val good=Files.writeString(directory.resolve("good.txt"),"good")
        LibraryRepository(directory.resolve("data")).use { repo ->
            val report=repo.importBooks(listOf(bad,good))
            assertEquals(1,report.added); assertEquals(1,report.failures.size)
            Files.list(directory.resolve("data/books")).use { assertEquals(1L,it.count()) }
        }
    }
    private fun epub(name:String,entries:Map<String,String>):Path {
        val path=directory.resolve(name)
        ZipOutputStream(Files.newOutputStream(path)).use { zip -> entries.forEach { (key,value) -> zip.putNextEntry(ZipEntry(key)); zip.write(value.toByteArray()); zip.closeEntry() } }
        return path
    }
    @Test fun epubMetadataAndXmlSafety() {
        val container="""<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>"""
        val opf="""<package xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>书名</dc:title><dc:creator>作者</dc:creator></metadata><manifest/></package>"""
        val good=epub("good.epub",mapOf("META-INF/container.xml" to container,"book.opf" to opf))
        val bad=epub("bad.epub",mapOf("META-INF/container.xml" to "<!DOCTYPE x [<!ENTITY x SYSTEM 'file:///secret'>]><x>&x;</x>"))
        val traversal=epub("traversal.epub",mapOf("../escape" to "bad","META-INF/container.xml" to container,"book.opf" to opf))
        LibraryRepository(directory.resolve("data")).use { repo ->
            val report=repo.importBooks(listOf(good,bad,traversal))
            assertEquals(1,report.added); assertEquals(2,report.failures.size)
            assertEquals("书名",repo.listBooks().single().title); assertEquals("作者",repo.listBooks().single().author)
        }
    }
    @Test fun epubCoverIsNormalizedToPng() {
        val image=java.awt.image.BufferedImage(20,30,java.awt.image.BufferedImage.TYPE_INT_RGB)
        val bytes=java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image,"jpg",it) }.toByteArray()
        val path=directory.resolve("cover.epub")
        val entries=mapOf(
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>".toByteArray(),
            "OPS/book.opf" to "<package><metadata><title>带封面</title></metadata><manifest><item id='cover' properties='cover-image' href='cover.jpg'/></manifest></package>".toByteArray(),
            "OPS/cover.jpg" to bytes)
        ZipOutputStream(Files.newOutputStream(path)).use { zip -> entries.forEach { (name,data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() } }
        LibraryRepository(directory.resolve("data")).use { repo ->
            assertEquals(1,repo.importBooks(listOf(path)).added)
            val cover=assertNotNull(repo.coverPath(repo.listBooks().single()))
            assertEquals(20,javax.imageio.ImageIO.read(cover.toFile()).width)
            assertEquals(0x89.toByte(),Files.readAllBytes(cover)[0])
        }
    }
    @Test fun corruptDatabaseIsNotReplaced() {
        val data=Files.createDirectory(directory.resolve("data"))
        val database=Files.writeString(data.resolve("library.sqlite"),"damaged user data")
        assertFails { LibraryRepository(data) }
        assertEquals("damaged user data",Files.readString(database))
    }
    @Test fun damagedSchemaClosesConnectionBeforeReportingFailure() {
        val data=directory.resolve("data")
        LibraryRepository(data).use { }
        val database=data.resolve("library.sqlite")
        val damaged=Files.readAllBytes(database)
        // Preserve SQLite's header so corruption is discovered after a connection opens.
        for(i in 100 until damaged.size) damaged[i]=0x7f.toByte()
        Files.write(database,damaged)
        assertFails { LibraryRepository(data) }
        assertContentEquals(damaged,Files.readAllBytes(database))
        val moved=data.resolve("preserved.sqlite")
        Files.move(database,moved)
        assertTrue(Files.exists(moved))
    }
    @Test fun cancellationDuringCopyKeepsCompletedBooksAndCleansPartialCopy() {
        val first=Files.writeString(directory.resolve("first.txt"),"已完成的书籍")
        val original=ByteArray(1024*1024) { (it%113).toByte() }
        val second=Files.write(directory.resolve("second.txt"),original)
        val third=Files.writeString(directory.resolve("third.txt"),"不应导入")
        val data=directory.resolve("data")
        var copyingSecond=false
        var secondChecks=0
        val completed=mutableListOf<Int>()
        LibraryRepository(data).use { repo ->
            val report=repo.importBooks(listOf(first,second,third),shouldCancel={
                if(!copyingSecond) false else { secondChecks++; secondChecks>=3 }
            }) { progress -> completed+=progress.completed; if(progress.completed==1) copyingSecond=true }
            assertTrue(report.cancelled)
            assertEquals(1,report.added); assertEquals(0,report.duplicates); assertTrue(report.failures.isEmpty())
            assertEquals(listOf(1),completed)
            assertEquals("first.txt",repo.listBooks().single().sourceName)
            Files.list(data.resolve("books")).use { files ->
                val names=files.map { it.fileName.toString() }.toList()
                assertEquals(1,names.size); assertTrue(names.single().endsWith(".txt"))
            }
            assertContentEquals(original,Files.readAllBytes(second))
            assertEquals("已完成的书籍",Files.readString(first)); assertEquals("不应导入",Files.readString(third))
            val retry=repo.importBooks(listOf(second,third))
            assertFalse(retry.cancelled); assertEquals(2,retry.added)
        }
    }
    @Test fun cancellationBeforeFirstFileHasNoFailuresOrManagedFiles() {
        val source=Files.writeString(directory.resolve("source.txt"),"原文保留")
        val data=directory.resolve("data")
        LibraryRepository(data).use { repo ->
            val report=repo.importBooks(listOf(source),shouldCancel={ true })
            assertTrue(report.cancelled); assertEquals(0,report.added); assertTrue(report.failures.isEmpty())
            assertTrue(repo.listBooks().isEmpty())
            Files.list(data.resolve("books")).use { assertEquals(0L,it.count()) }
            assertEquals("原文保留",Files.readString(source))
        }
    }
    @Test fun categoryRenameIsAtomicAndPreservesBooksProgressAndOrder() {
        val data=directory.resolve("categories")
        LibraryRepository(data).use { repo ->
            val files=(0..2).map { Files.writeString(directory.resolve("category-$it.txt"),"正文 $it") }
            assertEquals(3,repo.importBooks(files).added)
            val books=repo.listBooks()
            books.forEachIndexed { index,book -> repo.updateBook(book.id,book.title,if(index<2) "文学" else "旅行") }
            repo.saveProgress(books[0].id,"flow:3:120",0.42)
            repo.reorderCategories(listOf("旅行","文学"))
            val before=repo.listBooks()
            assertFailsWith<IllegalArgumentException> { repo.renameCategory("文学","  ") }
            assertFailsWith<IllegalArgumentException> { repo.renameCategory("文学","旅行") }
            java.sql.DriverManager.getConnection("jdbc:sqlite:${data.resolve("library.sqlite")}").use { db ->
                db.createStatement().use { it.execute("CREATE TRIGGER refuse_rename BEFORE UPDATE OF name ON category_order BEGIN SELECT RAISE(ABORT,'forced test failure'); END") }
                assertFails { repo.renameCategory("文学","诗歌") }
                assertEquals(before,repo.listBooks(),"Failed order update must roll back book categories as well")
                db.createStatement().use { it.execute("DROP TRIGGER refuse_rename") }
            }
            repo.renameCategory("文学","  诗歌  ")
            assertEquals(listOf("旅行","诗歌"),repo.listCategories())
            assertEquals(before.map { if(it.category=="文学") it.copy(category="诗歌") else it },repo.listBooks())
        }
        LibraryRepository(data).use { repo ->
            assertEquals(listOf("旅行","诗歌"),repo.listCategories())
            assertEquals(2,repo.listBooks().count { it.category=="诗歌" })
            assertTrue(repo.listBooks().any { it.locator=="flow:3:120" && it.progress==0.42 })
        }
    }
    @Test fun existingCategoriesMigrateAndReorderingSurvivesNewAndRemovedCategories() {
        val data=directory.resolve("old-library")
        LibraryRepository(data).use { repo ->
            repo.importBooks((0..2).map { Files.writeString(directory.resolve("order-$it.txt"),"章节 $it") })
            repo.listBooks().forEachIndexed { index,book -> repo.updateBook(book.id,book.title,listOf("C","A","B")[index]) }
        }
        java.sql.DriverManager.getConnection("jdbc:sqlite:${data.resolve("library.sqlite")}").use { db -> db.createStatement().use { it.execute("DROP TABLE category_order") } }
        LibraryRepository(data).use { repo ->
            assertEquals(listOf("A","B","C"),repo.listCategories())
            repo.reorderCategories(listOf("C","A","B"))
            val book=repo.listBooks().single { it.category=="A" }
            repo.updateBook(book.id,book.title,"D")
            assertEquals(listOf("C","B","D"),repo.listCategories())
            repo.reorderCategories(listOf("B","C","A"))
            assertEquals(listOf("B","C","D"),repo.listCategories(),"A stale drag must preserve newly added categories")
            repo.removeBook(repo.listBooks().single { it.category=="C" }.id)
            assertEquals(listOf("B","D"),repo.listCategories())
        }
        LibraryRepository(data).use { assertEquals(listOf("B","D"),it.listCategories()) }
    }
    @Test fun relativeAndEncodedCoverPathsShareTheReadingResolver() {
        val bytes=java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(java.awt.image.BufferedImage(24,32,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",it) }.toByteArray()
        val cases=listOf("../Images/cover.png" to "Images/cover.png", "cover%20image.png" to "OPS/cover image.png", "cover+image.png" to "OPS/cover+image.png")
        LibraryRepository(directory.resolve("covers-data")).use { repo ->
            cases.forEachIndexed { index,(href,entry) ->
                val path=coverFixture("path-$index.epub",href,entry,bytes)
                val report=repo.importBooks(listOf(path))
                assertEquals(1,report.added,report.toString())
                val book=repo.listBooks().first { it.sourceName==path.fileName.toString() }
                assertEquals(24,javax.imageio.ImageIO.read(assertNotNull(repo.coverPath(book)).toFile()).width)
                assertEquals(1,dev.towertools.towerreader.flow.FlowLoader.open(path,directory.resolve("flow-$index")).chapters.size)
            }
        }
    }
    @Test fun missingDamagedOrUnsafeCoverDoesNotRejectReadableBook() {
        LibraryRepository(directory.resolve("fallback-data")).use { repo ->
            listOf("missing.png","bad.png","../../outside.png","https://example.invalid/cover.png","%2foutside.png","..%2f..%2foutside.png").forEachIndexed { index,href ->
                val path=coverFixture("fallback-$index.epub",href,"OPS/bad.png","not an image".toByteArray())
                val report=repo.importBooks(listOf(path))
                assertEquals(1,report.added,report.toString())
                assertNull(repo.coverPath(repo.listBooks().first { it.sourceName==path.fileName.toString() }))
            }
        }
    }
    private fun coverFixture(name:String,href:String,imageEntry:String,bytes:ByteArray):Path {
        val path=directory.resolve(name)
        val entries=mapOf(
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>".toByteArray(),
            "OPS/book.opf" to "<package><metadata><title>$name</title></metadata><manifest><item id='cover' properties='cover-image' href='$href'/><item id='c' href='chapter.xhtml'/></manifest><spine><itemref idref='c'/></spine></package>".toByteArray(),
            "OPS/chapter.xhtml" to "<html><body><p>可阅读的正文。</p></body></html>".toByteArray(),imageEntry to bytes)
        ZipOutputStream(Files.newOutputStream(path)).use { zip -> entries.forEach { (key,value) -> zip.putNextEntry(ZipEntry(key)); zip.write(value); zip.closeEntry() } }
        return path
    }
    @Test fun failedRemovalKeepsBookCoverCacheAndSettingsIntact() {
        val data=directory.resolve("rollback-data")
        val source=Files.writeString(directory.resolve("rollback.txt"),"正文".repeat(500))
        LibraryRepository(data).use { repo ->
            repo.importBooks(listOf(source)); val book=repo.listBooks().single()
            repo.updateBook(book.id,book.title,"保留分类"); repo.setSetting("encoding.${book.id}","UTF-8")
            val before=repo.listBooks()
            val cover=Files.writeString(data.resolve("covers/${book.id}.png"),"cover")
            val cache=data.resolve("cache/${book.id}")
            dev.towertools.towerreader.flow.FlowLoader.open(repo.bookPath(book),cache)
            java.sql.DriverManager.getConnection("jdbc:sqlite:${data.resolve("library.sqlite")}").use { db ->
                db.createStatement().use { it.execute("CREATE TRIGGER refuse_remove BEFORE DELETE ON books BEGIN SELECT RAISE(ABORT,'forced removal failure'); END") }
                assertFails { repo.removeBook(book.id) }
            }
            assertEquals(before,repo.listBooks()); assertTrue(Files.exists(repo.bookPath(book)))
            assertTrue(Files.exists(cover)); assertTrue(Files.exists(cache))
            assertEquals("UTF-8",repo.setting("encoding.${book.id}","")); assertEquals(listOf("保留分类"),repo.listCategories())
        }
        LibraryRepository(data).use { repo -> assertEquals(1,repo.listBooks().size); assertTrue(Files.exists(repo.bookPath(repo.listBooks().single()))) }
    }
    @Test fun successfulRemovalCleansOnlyThatBooksDerivedDataAndAllowsReimport() {
        val data=directory.resolve("cleanup-data")
        val source=Files.writeString(directory.resolve("cleanup.txt"),"原文".repeat(1000))
        LibraryRepository(data).use { repo ->
            repo.importBooks(listOf(source)); val book=repo.listBooks().single()
            val cache=data.resolve("cache/${book.id}")
            dev.towertools.towerreader.flow.FlowLoader.open(repo.bookPath(book),cache)
            val unrelated=Files.writeString(Files.createDirectories(data.resolve("cache/other-book")).resolve("keep.txt"),"保留")
            repo.setSetting("encoding.${book.id}","UTF-8"); repo.setSetting("theme","dark")
            repo.removeBook(book.id)
            assertFalse(Files.exists(cache)); assertTrue(Files.exists(source)); assertTrue(Files.exists(unrelated))
            assertEquals("",repo.setting("encoding.${book.id}","")); assertEquals("dark",repo.setting("theme",""))
            assertEquals(1,repo.importBooks(listOf(source)).added)
        }
    }
    @Test fun committedRemovalRetriesLockedFilesAfterRestart() {
        val data=directory.resolve("retry-data")
        val source=Files.writeString(directory.resolve("retry.txt"),"原文")
        lateinit var managed:Path
        lateinit var cache:Path
        val backup=directory.resolve("temporary-copy.txt")
        LibraryRepository(data).use { repo ->
            repo.importBooks(listOf(source)); val book=repo.listBooks().single(); managed=repo.bookPath(book)
            cache=data.resolve("cache/${book.id}")
            dev.towertools.towerreader.flow.FlowLoader.open(managed,cache)
            // A non-empty directory deterministically denies delete without relying on OS file-sharing flags.
            Files.move(managed,backup); Files.createDirectory(managed); Files.writeString(managed.resolve("busy"),"blocked")
            repo.removeBook(book.id)
            assertTrue(repo.listBooks().isEmpty())
        }
        LibraryRepository(data).use { assertTrue(it.listBooks().isEmpty()) }
        Files.delete(managed.resolve("busy")); Files.delete(managed); Files.move(backup,managed)
        LibraryRepository(data).use { repo ->
            assertTrue(repo.listBooks().isEmpty()); assertFalse(Files.exists(managed)); assertFalse(Files.exists(cache))
            assertEquals(1,repo.importBooks(listOf(source)).added)
        }
    }
    @Test fun cleanupHandlesRedirectedDataRootWithoutFollowingCacheJunctions() {
        val actual=Files.createDirectory(directory.resolve("actual-data"))
        val data=directory.resolve("redirected-data")
        val outside=Files.createDirectory(directory.resolve("unrelated-files"))
        val keep=Files.writeString(outside.resolve("keep.txt"),"不能删除")
        fun junction(link:Path,target:Path) {
            val process=ProcessBuilder("cmd.exe","/c","mklink","/J",link.toString(),target.toString()).redirectErrorStream(true).start()
            assertTrue(process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0,process.exitValue(),process.inputStream.bufferedReader().readText())
        }
        junction(data,actual)
        var link:Path?=null
        try {
            val source=Files.writeString(directory.resolve("junction.txt"),"正文")
            lateinit var cache:Path
            LibraryRepository(data).use { repo ->
                repo.importBooks(listOf(source)); val book=repo.listBooks().single()
                cache=data.resolve("cache/${book.id}")
                dev.towertools.towerreader.flow.FlowLoader.open(repo.bookPath(book),cache)
                link=cache.resolve("external"); junction(link!!,outside)
                repo.removeBook(book.id)
                assertTrue(repo.listBooks().isEmpty()); assertEquals("不能删除",Files.readString(keep))
            }
            // Remove only the junction itself, then let the persisted cleanup finish on restart.
            link?.let { Files.deleteIfExists(it) }; link=null
            LibraryRepository(data).use { assertFalse(Files.exists(cache)); assertEquals("不能删除",Files.readString(keep)) }
        } finally {
            link?.let { Files.deleteIfExists(it) }
            Files.deleteIfExists(data)
        }
    }
}
