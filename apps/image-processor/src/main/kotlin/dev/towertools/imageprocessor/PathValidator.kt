package dev.towertools.imageprocessor

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

object PathValidator {
    fun normalize(rawValue: String): Path {
        val trimmed = rawValue.trim().removeSurrounding("\"").trim()
        if (trimmed.isEmpty()) throw UserFacingException("请先设置输出目录。")
        val path = try {
            Paths.get(trimmed).toAbsolutePath().normalize()
        } catch (_: InvalidPathException) {
            throw UserFacingException("输出目录路径格式不合法。")
        }
        if (!Paths.get(trimmed).isAbsolute) throw UserFacingException("输出目录必须使用完整的绝对路径。")
        if (!Files.exists(path)) throw UserFacingException("输出目录不存在。")
        if (!Files.isDirectory(path)) throw UserFacingException("输出路径不是文件夹。")
        return path
    }

    fun requireWritable(rawValue: String): Path {
        val path = normalize(rawValue)
        val probe = try {
            Files.createTempFile(path, ".imageprocessor-write-test-", ".tmp")
        } catch (_: Exception) {
            throw UserFacingException("输出目录不可写，请检查权限。")
        }
        runCatching { Files.deleteIfExists(probe) }
        return path
    }
}
