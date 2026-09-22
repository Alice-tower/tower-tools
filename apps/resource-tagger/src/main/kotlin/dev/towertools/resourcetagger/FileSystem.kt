package dev.towertools.resourcetagger

import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributes
import java.util.Locale

data class Found(val name: String, val kind: Kind)
object PathsPolicy {
    fun key(path: Path): String = path.toAbsolutePath().normalize().toString().replace('/', '\\').trimEnd('\\').lowercase(Locale.ROOT)
    fun childKey(name: String): String {
        require(name.isNotBlank() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\') && !name.contains(':')) { "资源必须是 Root 的直接子项。" }
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
        childKey(resource.relativePath)
        return Path.of(root.path).resolve(resource.relativePath)
    }
}

interface ResourceFileSystem {
    fun scan(path: Path): List<Found>
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
    override fun scan(path: Path): List<Found> {
        require(Files.isDirectory(path)) { "Root 不存在或不可访问：$path" }
        val found = Files.newDirectoryStream(path).use { stream -> stream.mapNotNull { child -> inspect(child)?.let { Found(child.fileName.toString(), it) } } }
        require(found.map { PathsPolicy.childKey(it.name) }.distinct().size == found.size) { "存在无法区分的大小写路径冲突，扫描未提交。" }
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
