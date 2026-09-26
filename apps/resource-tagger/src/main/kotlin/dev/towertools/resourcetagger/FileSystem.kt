package dev.towertools.resourcetagger

import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributes
import java.util.Locale

data class Found(val relativePath: String, val kind: Kind) {
    val name get() = relativePath.replace('\\', '/').substringAfterLast('/')
}
object PathsPolicy {
    private val bucketPattern = Regex("bucket-[0-9]{6}", RegexOption.IGNORE_CASE)
    fun bucketNumber(name: String): Int? = name.takeIf { bucketPattern.matches(it) }?.substringAfter('-')?.toInt()?.takeIf { it > 0 }
    fun relativeKey(relative: String): String {
        val parts = relative.replace('\\', '/').split('/')
        require(parts.size == 2 && bucketNumber(parts[0]) != null) { "资源必须位于 Root/bucket-000001/资源 这样的两层路径。" }
        parts.forEach(::childKey)
        return parts.joinToString("/").lowercase(Locale.ROOT)
    }
    fun relative(root: Root, target: Path): String {
        require(target.parent?.parent?.let(::key) == key(Path.of(root.path))) { "目标必须是已配置 Root 下 Bucket 的直接子项。" }
        val relative = "${target.parent.fileName}/${target.fileName}"
        relativeKey(relative)
        return relative
    }
    fun key(path: Path): String = path.toAbsolutePath().normalize().toString().replace('/', '\\').trimEnd('\\').lowercase(Locale.ROOT)
    fun childKey(name: String): String {
        require(name.isNotBlank() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\') && !name.contains(':')) { "无效的路径组成部分。" }
        return name.lowercase(Locale.ROOT)
    }
    fun root(value: String): Path {
        require(value.isNotBlank()) { "请选择或输入 Root 目录路径。" }
        val path = Path.of(value.trim()).toAbsolutePath().normalize().toRealPath()
        require(Files.isDirectory(path)) { "Root 必须是可访问的目录。" }
        require(path.parent != null) { "不能把整块磁盘作为 Root。" }
        return path
    }
    fun overlaps(a: String, b: String): Boolean = a == b || a.startsWith("$b\\") || b.startsWith("$a\\")
    fun actual(root: Root, resource: Resource): Path {
        relativeKey(resource.relativePath)
        return Path.of(root.path).resolve(resource.relativePath.replace('\\', '/'))
    }
}

interface ResourceFileSystem {
    fun scan(path: Path): List<Found>
    fun scan(path: Path, control: ScanControl): List<Found> {
        control.check()
        return scan(path).also { control.check() }
    }
    fun inspect(path: Path): Kind?
}
class LocalFileSystem : ResourceFileSystem {
    override fun inspect(path: Path): Kind? {
        val dos = Files.readAttributes(path, DosFileAttributes::class.java, NOFOLLOW_LINKS)
        if (dos.isSymbolicLink || dos.isOther) return null
        if (dos.isHidden || dos.isSystem) return null
        // A junction may look like a directory on a provider: reject redirected children too.
        if (PathsPolicy.key(path.toRealPath()) != PathsPolicy.key(path)) return null
        return when { dos.isDirectory -> Kind.Directory; dos.isRegularFile -> Kind.File; else -> null }
    }
    override fun scan(path: Path): List<Found> = scan(path, ScanControl())
    override fun scan(path: Path, control: ScanControl): List<Found> {
        control.check()
        // One root enumeration, then one enumeration per matching bucket. Never inspect unrelated entries.
        require(PathsPolicy.key(path.toRealPath()) == PathsPolicy.key(path)) { "Root 路径已重定向，请重新定位 Root。" }
        val found = buildList {
            Files.newDirectoryStream(path).use { buckets ->
                for (bucket in buckets) {
                    control.check()
                    if (PathsPolicy.bucketNumber(bucket.fileName.toString()) == null) continue
                    control.report(bucket.fileName.toString(), size)
                    if (inspect(bucket) != Kind.Directory) continue
                    Files.newDirectoryStream(bucket).use { children ->
                        for (child in children) {
                            control.check()
                            val dos = Files.readAttributes(child, DosFileAttributes::class.java, NOFOLLOW_LINKS)
                            if (dos.isSymbolicLink || dos.isOther || dos.isHidden || dos.isSystem) continue
                            // Resolve directories to exclude junctions; ordinary files need no extra canonical lookup.
                            if (dos.isDirectory && PathsPolicy.key(child.toRealPath()) != PathsPolicy.key(child)) continue
                            val kind = when { dos.isDirectory -> Kind.Directory; dos.isRegularFile -> Kind.File; else -> continue }
                            add(Found("${bucket.fileName}/${child.fileName}", kind))
                            control.report(bucket.fileName.toString(), size)
                        }
                    }
                }
            }
        }
        require(found.map { PathsPolicy.relativeKey(it.relativePath) }.distinct().size == found.size) { "存在无法区分的大小写路径冲突，扫描未提交。" }
        return found
    }
}

object Navigator {
    fun command(path: Path, openDirectory: Boolean): List<String> = if (openDirectory) listOf("explorer.exe", path.toString()) else listOf("explorer.exe", "/select,", path.toString())
    fun navigate(root: Root, resource: Resource, openDirectory: Boolean) {
        val path = PathsPolicy.actual(root, resource)
        require(Files.isDirectory(Path.of(root.path))) { "Root 当前不可访问。" }
        require(resource.status == Status.Active && LocalFileSystem().inspect(path) == resource.kind) { "资源不存在、已忽略或类型发生变化，请重新扫描。" }
        require(!openDirectory || resource.kind == Kind.Directory) { "文件只支持在所在目录中显示。" }
        if (openDirectory) Desktop.getDesktop().open(path.toFile())
        else ProcessBuilder(command(path, false)).start()
    }
}
