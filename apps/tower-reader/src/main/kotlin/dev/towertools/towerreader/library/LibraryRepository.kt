package dev.towertools.towerreader.library

import java.nio.file.*
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.UUID
import javax.imageio.ImageIO
import org.apache.pdfbox.Loader
import dev.towertools.towerreader.pdf.renderPdfCover

enum class BookFormat { TXT, EPUB, PDF }
data class BookRecord(val id:String,val title:String,val author:String,val format:BookFormat,val fileName:String,val category:String,val importedAt:Long,val lastOpenedAt:Long,val progress:Double,val locator:String,val sourceName:String=fileName)
data class ImportProgress(val completed:Int,val total:Int,val fileName:String)
data class ImportReport(val added:Int,val duplicates:Int,val failures:List<String>,val cancelled:Boolean=false)
internal data class Metadata(val title:String,val author:String,val cover:ByteArray?)

class LibraryRepository(dataDirectory:Path):AutoCloseable {
    private val root=dataDirectory.toAbsolutePath().normalize()
    private val books=root.resolve("books")
    private val covers=root.resolve("covers")
    private val importLock=Any()
    private class ImportCancelled:RuntimeException()
    private val db:java.sql.Connection
    init {
        Files.createDirectories(books); Files.createDirectories(covers)
        Class.forName("org.sqlite.JDBC")
        val databasePath=root.resolve("library.sqlite")
        if(Files.exists(databasePath) && Files.size(databasePath)>0) {
            val header=Files.newInputStream(databasePath).use { it.readNBytes(16) }
            require(header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))) { "书库数据库已损坏；原文件已保留，请恢复备份" }
        }
        db=DriverManager.getConnection("jdbc:sqlite:$databasePath")
        try {
        db.createStatement().use { s ->
            s.execute("PRAGMA busy_timeout=10000")
            s.execute("CREATE TABLE IF NOT EXISTS books(id TEXT PRIMARY KEY,title TEXT NOT NULL,author TEXT NOT NULL,format TEXT NOT NULL,fileName TEXT NOT NULL,category TEXT NOT NULL,importedAt INTEGER NOT NULL,lastOpenedAt INTEGER NOT NULL,progress REAL NOT NULL,locator TEXT NOT NULL,hash TEXT NOT NULL UNIQUE)")
            val columns=s.executeQuery("PRAGMA table_info(books)").use { r -> buildSet { while(r.next()) add(r.getString("name")) } }
            if("sourceName" !in columns) s.execute("ALTER TABLE books ADD COLUMN sourceName TEXT NOT NULL DEFAULT ''")
            s.execute("CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
            s.execute("CREATE TABLE IF NOT EXISTS category_order(name TEXT PRIMARY KEY,position INTEGER NOT NULL)")
            s.execute("CREATE TABLE IF NOT EXISTS pending_book_cleanup(id TEXT PRIMARY KEY,fileName TEXT NOT NULL)")
        }
        transaction { syncCategories() }
        cleanupPendingRemovals()
        } catch(failure: Throwable) {
            try { db.close() } catch(closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
            throw failure
        }
    }
    @Synchronized fun listBooks():List<BookRecord> = db.createStatement().use { s -> s.executeQuery("SELECT * FROM books ORDER BY importedAt DESC,id").use { r -> buildList {
        while(r.next()) add(BookRecord(r.getString("id"),r.getString("title"),r.getString("author"),BookFormat.valueOf(r.getString("format")),r.getString("fileName"),r.getString("category"),r.getLong("importedAt"),r.getLong("lastOpenedAt"),r.getDouble("progress"),r.getString("locator"),r.getString("sourceName").ifBlank { r.getString("fileName") }))
    } } }
    fun importBooks(paths:List<Path>,shouldCancel:()->Boolean={false},onProgress:(ImportProgress)->Unit={}):ImportReport = synchronized(importLock) {
        var added=0; var duplicates=0; val failures=mutableListOf<String>()
        for((index,source) in paths.withIndex()) {
            if(shouldCancel()) return@synchronized ImportReport(added,duplicates,failures,true)
            var staged:Path?=null; var published:Path?=null; var cover:Path?=null
            try {
                require(Files.isRegularFile(source)) { "文件不存在或不是普通文件" }
                val ext=source.fileName.toString().substringAfterLast('.',"").lowercase()
                val format=when(ext) { "txt"->BookFormat.TXT; "epub"->BookFormat.EPUB; "pdf"->BookFormat.PDF; else->error("仅支持 TXT、EPUB、PDF") }
                val temp=Files.createTempFile(books,"import-",".tmp"); staged=temp
                val digest=MessageDigest.getInstance("SHA-256")
                Files.newInputStream(source).use { input -> Files.newOutputStream(temp).use { out ->
                    val buffer=ByteArray(65536)
                    while(true) { if(shouldCancel()) throw ImportCancelled(); val n=input.read(buffer); if(n<0) break; digest.update(buffer,0,n); out.write(buffer,0,n) }
                } }
                val hash=digest.digest().joinToString("") { "%02x".format(it) }
                val exists=synchronized(this) { db.prepareStatement("SELECT 1 FROM books WHERE hash=?").use { s -> s.setString(1,hash); s.executeQuery().use { it.next() } } }
                if(exists) duplicates++ else {
                    val id=UUID.randomUUID().toString()
                    val metadata=when(format) {
                        BookFormat.EPUB->EpubMetadata.read(temp)
                        BookFormat.PDF->Loader.loadPDF(temp.toFile()).use { pdf ->
                            require(pdf.numberOfPages>0) { "PDF 没有页面" }
                            val image=renderPdfCover(temp,240,340)
                            val bytes=java.io.ByteArrayOutputStream().also { ImageIO.write(image,"png",it) }.toByteArray()
                            Metadata(pdf.documentInformation.title.orEmpty(),pdf.documentInformation.author.orEmpty(),bytes)
                        }
                        BookFormat.TXT->Metadata("","",null)
                    }
                    if(shouldCancel()) throw ImportCancelled()
                    val name="$id.$ext"; val dest=books.resolve(name)
                    Files.move(temp,dest,StandardCopyOption.ATOMIC_MOVE); staged=null; published=dest
                    metadata.cover?.let { bytes ->
                        val ct=Files.createTempFile(covers,"cover-",".tmp")
                        try { Files.write(ct,bytes); val cd=covers.resolve("$id.png"); Files.move(ct,cd,StandardCopyOption.ATOMIC_MOVE); cover=cd }
                        finally { Files.deleteIfExists(ct) }
                    }
                    synchronized(this) { update("INSERT INTO books(id,title,author,format,fileName,category,importedAt,lastOpenedAt,progress,locator,hash,sourceName) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",id,metadata.title.ifBlank { source.fileName.toString().substringBeforeLast('.') },metadata.author,format.name,name,"",System.currentTimeMillis(),0L,0.0,"",hash,source.fileName.toString()) }
                    published=null; cover=null; added++
                }
            } catch(e:ImportCancelled) { return@synchronized ImportReport(added,duplicates,failures,true) }
            catch(e:Exception) { failures+="${source.fileName}: ${e.message ?: e.javaClass.simpleName}" }
            finally { staged?.let { Files.deleteIfExists(it) }; published?.let { Files.deleteIfExists(it) }; cover?.let { Files.deleteIfExists(it) } }
            onProgress(ImportProgress(index+1,paths.size,source.fileName.toString()))
        }
        ImportReport(added,duplicates,failures)
    }
    fun bookPath(book:BookRecord):Path = books.resolve(book.fileName).normalize().also { require(it.parent==books) { "无效书籍路径" } }
    fun coverPath(book:BookRecord):Path? = covers.resolve("${UUID.fromString(book.id)}.png").takeIf { Files.isRegularFile(it) }
    @Synchronized fun updateBook(id:String,title:String,category:String) { require(title.isNotBlank()); transaction { update("UPDATE books SET title=?,category=? WHERE id=?",title.trim(),category.trim(),id); syncCategories() } }
    @Synchronized fun listCategories():List<String> = db.createStatement().use { s -> s.executeQuery("SELECT name FROM category_order ORDER BY position,name").use { r -> buildList { while(r.next()) add(r.getString(1)) } } }
    @Synchronized fun renameCategory(previous:String,requested:String) {
        val name=requested.trim()
        require(name.isNotEmpty()) { "分类名称不能为空" }
        val categories=listCategories()
        require(previous in categories) { "这个分类已不存在" }
        if(name==previous) return
        require(name !in categories) { "已有同名分类，请换一个名称" }
        transaction {
            update("UPDATE books SET category=? WHERE category=?",name,previous)
            update("UPDATE category_order SET name=? WHERE name=?",name,previous)
        }
    }
    @Synchronized fun reorderCategories(requested:List<String>) {
        val current=listCategories()
        // Keep categories added by another queued edit, and ignore names that disappeared meanwhile.
        val order=requested.filter { it in current }.distinct()+current.filter { it !in requested }
        transaction { order.forEachIndexed { index,name -> update("UPDATE category_order SET position=? WHERE name=?",index,name) } }
    }
    private fun syncCategories() {
        update("DELETE FROM category_order WHERE name NOT IN (SELECT DISTINCT category FROM books WHERE category<>'')")
        val missing=db.createStatement().use { s -> s.executeQuery("SELECT DISTINCT category FROM books WHERE category<>'' AND category NOT IN (SELECT name FROM category_order) ORDER BY category").use { r -> buildList { while(r.next()) add(r.getString(1)) } } }
        val next=db.createStatement().use { s -> s.executeQuery("SELECT COALESCE(MAX(position),-1)+1 FROM category_order").use { it.next(); it.getInt(1) } }
        missing.forEachIndexed { index,name -> update("INSERT INTO category_order(name,position) VALUES(?,?)",name,next+index) }
    }
    private fun <T> transaction(action:()->T):T {
        db.autoCommit=false
        try { val result=action(); db.commit(); return result }
        catch(failure:Throwable) { runCatching { db.rollback() }.onFailure(failure::addSuppressed); throw failure }
        finally { db.autoCommit=true }
    }
    @Synchronized fun saveProgress(id:String,locator:String,progress:Double) { require(progress.isFinite()); update("UPDATE books SET locator=?,progress=? WHERE id=?",locator,progress.coerceIn(0.0,1.0),id) }
    @Synchronized fun markOpened(id:String) { update("UPDATE books SET lastOpenedAt=? WHERE id=?",System.currentTimeMillis(),id) }
    @Synchronized fun removeBook(id:String) {
        val book=listBooks().firstOrNull { it.id==id } ?: return
        cleanupPaths(id,book.fileName) // Validate every target before changing the database.
        transaction {
            update("INSERT INTO pending_book_cleanup(id,fileName) VALUES(?,?)",id,book.fileName)
            update("DELETE FROM books WHERE id=?",id)
            update("DELETE FROM settings WHERE key=?","encoding.$id")
            syncCategories()
        }
        // The removal is committed. Failed physical cleanup remains durable and retryable;
        // it must never turn a successful removal into a broken, still-visible book record.
        cleanupPendingRemovals()
    }
    private fun cleanupPaths(id:String,fileName:String):List<Path> {
        require(UUID.fromString(id).toString()==id) { "无效书籍编号" }
        require(fileName in listOf("$id.txt","$id.epub","$id.pdf")) { "无效托管文件名" }
        return listOf(books.resolve(fileName),covers.resolve("$id.png"),root.resolve("cache").resolve(id)).onEach {
            require(it.toAbsolutePath().normalize().startsWith(root) && it!=root) { "清理路径越界" }
        }
    }
    private fun cleanupPendingRemovals() {
        val pending=db.createStatement().use { s ->
            s.executeQuery("SELECT id,fileName FROM pending_book_cleanup WHERE id NOT IN (SELECT id FROM books)").use { r ->
                buildList { while(r.next()) add(r.getString(1) to r.getString(2)) }
            }
        }
        for((id,fileName) in pending) try {
            val (book,cover,cache)=cleanupPaths(id,fileName)
            val allowed=root.toRealPath()
            // Parent directories may have been replaced with junctions after import.
            for(file in listOf(book,cover)) {
                require(file.parent.toRealPath().startsWith(allowed)) { "托管文件目录越界" }
                Files.deleteIfExists(file)
            }
            if(Files.exists(cache,LinkOption.NOFOLLOW_LINKS)) {
                require(cache.parent.toRealPath().startsWith(allowed)) { "缓存目录越界" }
                val expectedCache=cache.parent.toRealPath().resolve(id)
                Files.walkFileTree(cache,object:SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir:Path,attrs:java.nio.file.attribute.BasicFileAttributes):FileVisitResult {
                        // Never descend into a junction/reparse directory or outside this book's cache.
                        require(!attrs.isOther && dir.toRealPath().startsWith(expectedCache)) { "拒绝清理重定向的缓存目录" }
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFile(file:Path,attrs:java.nio.file.attribute.BasicFileAttributes):FileVisitResult {
                        Files.delete(file); return FileVisitResult.CONTINUE
                    }
                    override fun postVisitDirectory(dir:Path,error:java.io.IOException?):FileVisitResult {
                        if(error!=null) throw error
                        Files.delete(dir); return FileVisitResult.CONTINUE
                    }
                })
            }
            update("DELETE FROM pending_book_cleanup WHERE id=?",id)
        } catch(error:Exception) {
            dev.towertools.towerreader.AppLog.logger.warning("书籍已移出，文件清理将在下次启动重试（$id）：${error.message}")
        }
    }
    @Synchronized fun setting(key:String,defaultValue:String):String = db.prepareStatement("SELECT value FROM settings WHERE key=?").use { s -> s.setString(1,key); s.executeQuery().use { if(it.next()) it.getString(1) else defaultValue } }
    @Synchronized fun setSetting(key:String,value:String) { update("INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",key,value) }
    private fun update(sql:String,vararg values:Any) { db.prepareStatement(sql).use { s -> values.forEachIndexed { i,v -> s.setObject(i+1,v) }; s.executeUpdate() } }
    override fun close() { synchronized(importLock) { synchronized(this) { db.close() } } }
}
