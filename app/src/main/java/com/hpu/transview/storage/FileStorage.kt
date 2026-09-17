package com.hpu.transview.storage

import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * 存储实现（v1.14 起为**唯一实现**）：纯 `java.io.File`，内部存储与 U 盘同样处理。
 *
 * 根路径形如 `/storage/emulated/0/TransView/`（内部存储）或 `/storage/XXXX-XXXX/TransView/`（U 盘）。
 * 所有 relativePath 都相对这个沙盒根解析，因此**天然不可能越界到沙盒外**
 * （`..`、绝对路径段在 [resolve] 里被吃掉）。
 *
 * 刻意不用 `java.nio.file.Files`：那套 API 要求 API 26+，本工程 minSdk 是 21。
 */
class FileStorage(private val rootDir: File) : IStorage {

    override fun getRootPath(): String = rootDir.absolutePath

    override fun exists(): Boolean = rootDir.isDirectory

    override fun canWrite(): Boolean = runCatching { rootDir.isDirectory && rootDir.canWrite() }
        .getOrDefault(false)

    override fun getUsableSpace(): Long = runCatching { rootDir.usableSpace }.getOrDefault(0L)

    override fun getTotalSpace(): Long = runCatching { rootDir.totalSpace }.getOrDefault(0L)

    override fun createDirectory(relativePath: String): Boolean = runCatching {
        val dir = resolve(relativePath)
        dir.isDirectory || dir.mkdirs()
    }.getOrDefault(false)

    override fun writeFile(relativePath: String, inputStream: InputStream): Boolean = runCatching {
        val target = resolve(relativePath)
        target.parentFile?.mkdirs()
        inputStream.use { input ->
            FileOutputStream(target).use { out -> input.copyTo(out, BUFFER_SIZE) }
        }
        target.isFile
    }.getOrDefault(false)

    override fun listFiles(relativePath: String): List<StorageFile> {
        val dir = resolve(relativePath)
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return emptyList()
        val parentPath = dir.absolutePath
        return children.map { child ->
            val rel = if (relativePath.isEmpty()) child.name else "$relativePath/${child.name}"
            StorageFile(
                name = child.name,
                path = child.absolutePath,
                relativePath = rel,
                parentPath = parentPath,
                isDirectory = child.isDirectory,
                size = if (child.isDirectory) 0L else child.length(),
                lastModified = child.lastModified()
            )
        }
    }

    override fun deleteFile(relativePath: String): Boolean = runCatching {
        val f = resolve(relativePath)
        if (f.absolutePath == rootDir.absolutePath) return@runCatching false   // 根目录永不删
        !f.exists() || f.delete()
    }.getOrDefault(false)

    override fun openInputStream(relativePath: String): InputStream? = runCatching {
        val f = resolve(relativePath)
        if (f.isFile) FileInputStream(f) else null
    }.getOrNull()

    override fun nodeFor(relativePath: String): String = resolve(relativePath).absolutePath

    override fun relativeOf(nodePath: String): String? {
        val path = runCatching { File(nodePath).absolutePath }.getOrNull() ?: return null
        val root = rootDir.absolutePath
        return when {
            path == root -> ""
            path.startsWith(root + File.separator) -> path.removePrefix(root + File.separator)
            else -> null
        }
    }

    override fun uriOfNode(nodePath: String): Uri = Uri.fromFile(File(nodePath))

    override fun nodeExists(nodePath: String): Boolean =
        relativeOf(nodePath) != null && runCatching { File(nodePath).exists() }.getOrDefault(false)

    /**
     * 把本地临时文件**搬**进沙盒（同卷时 `renameTo` 即改名，零拷贝；跨卷退化为 copy + delete）。
     * 上传落盘优先走这条快路径（同卷零拷贝）；跨卷时退化为流式 copy。
     *
     * **绝不覆盖已有文件**（v1.28）：目标已存在时直接返回 false，由调用方换名重试。
     * 这条不变量必须由本方法自己守住 —— Linux 同卷 `renameTo`（`rename(2)`）会**静默覆盖**
     * 已存在的目标文件：两台手机并发上传同名文件时，后到者会把先到者的文件整个盖掉
     * （数据不可恢复）。调用方的「先列目录、再挑唯一名」本身不是原子的，
     * 单靠它挡不住并发。
     */
    fun moveFileInto(src: File, relativePath: String): Boolean {
        val target = runCatching { resolve(relativePath) }.getOrNull() ?: return false
        if (target.exists()) return false
        target.parentFile?.mkdirs()
        if (src.renameTo(target)) return true
        // renameTo 失败（多为跨卷）后目标可能已被并发创建，再核一次，避免 copyTo 撞名抛异常
        if (target.exists()) return false
        return try {
            src.copyTo(target, overwrite = false)
            src.delete()
            true
        } catch (e: Exception) {
            // 跨卷复制中途失败（磁盘满 / 掉盘）：清掉半截文件，避免「半个视频」被对账入库
            runCatching { if (target.exists()) target.delete() }
            false
        }
    }

    /** 相对路径 → 沙盒内的 File。逐段过滤 `..`、`.`、空段与绝对路径分隔，防越界 */
    fun resolve(relativePath: String): File {
        if (relativePath.isEmpty()) return rootDir
        var dir = rootDir
        for (seg in relativePath.split('/', '\\')) {
            if (seg.isEmpty() || seg == "." || seg == "..") continue
            dir = File(dir, seg)
        }
        return dir
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
