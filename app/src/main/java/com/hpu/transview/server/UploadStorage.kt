package com.hpu.transview.server

import android.content.Context
import android.media.MediaScannerConnection
import com.hpu.transview.model.Category
import com.hpu.transview.storage.FileStorage
import com.hpu.transview.storage.StorageFile
import com.hpu.transview.util.FileLocations
import java.io.File
import java.io.IOException

/**
 * 上传文件的落盘规则（落点始终跟随 [FileLocations.activeStorage] 的**活动存储**：
 * 首选外接盘且可用时写入该盘沙盒；降级模式（首选盘已拔出）自动写入内部存储沙盒，
 * 上传不中断；插回后恢复写所选盘）：
 * - 分类根目录：视频→Movies 图片→Pictures 其他→Downloads
 * - 文件夹上传：按 relativePath 重建层级，同名文件夹自动合并
 * - 同名文件：追加 (1)(2)… 后缀，绝不覆盖已有文件
 *
 * ## 落盘方式（v1.14）
 * 存储只有 [FileStorage] 一种实现（纯 `java.io.File`），落盘统一走
 * [FileStorage.moveFileInto]：NanoHTTPD 的临时文件与沙盒同卷时 `renameTo` 零拷贝改名，
 * 跨卷（临时文件在内部存储、目标在 U 盘）退化为 64KiB 流式 copy + delete。
 * 不再有任何 `ContentResolver` / `content://` 分支。
 *
 * 落盘结果统一为 [Saved]（存储身份 + 名称 + 父节点 + 尺寸 + 修改时间），
 * 供媒体索引入库 —— 数据库里 `filePath` 即 [Saved.path]（绝对路径）。
 */
class UploadStorage(private val context: Context) {

    /**
     * 「列目录 → 算唯一名 → 落盘」的进程内互斥锁。
     * NanoHTTPD 是多工作线程模型（两台手机可同时上传），整段必须串行，
     * 否则并发同名上传会双双选中同一个名字并互相覆盖（详见 [save] 注释）。
     * 锁粒度取「整个落盘动作」：相比按目录分锁，少一层「锁表」管理，
     * 而单次落盘只是一次 rename（同卷）或一次流式 copy，串行代价可忽略。
     */
    private val nameAllocLock = Any()

    /** 一次成功落盘的结果描述 */
    data class Saved(
        val name: String,
        /** 存储身份：绝对路径（= 数据库 filePath） */
        val path: String,
        /** 父节点身份（= 数据库 parentFolder） */
        val parentPath: String,
        val size: Long,
        val lastModified: Long
    )

    /** 保存并返回最终落盘结果（供媒体索引入库） */
    fun save(tempFile: File, rawName: String, rawRelPath: String, category: Category): Result<Saved> {
        return runCatching {
            // v1.14：存储只有 FileStorage 一种实现，直接用它（拿到同卷零拷贝改名的能力）
            val storage = FileLocations.activeStorageOf(context) as FileStorage
            val name0 = sanitizeFileName(rawName)
            val segments = sanitizeRelativePath(rawRelPath)
            val relDir = buildString {
                append(FileLocations.categoryRelative(category))
                segments.forEach { append('/').append(it) }
            }
            if (!storage.createDirectory(relDir)) throw IOException("无法创建文件夹：${relDir.substringAfter('/')}")

            // 同名不覆盖 + 落盘必须**原子**（v1.28）：
            // 两个并发上传若各自「列目录 → 判无冲突 → 改名」，会双双选中同一个名字，
            // 而 Linux 同卷 renameTo 会静默覆盖已存在目标 → 先到者的文件被后到者整个盖掉
            //（跨卷时 copyTo(overwrite=false) 反而会抛异常，表现为后者上传直接失败）。
            // 用一把进程内锁把「列目录 → 算唯一名 → 落盘」整段串起来，锁内再核对并换名重试。
            var name = ""
            var entry: StorageFile? = null
            synchronized(nameAllocLock) {
                val used = storage.listFiles(relDir).mapTo(HashSet()) { it.name }
                name = if (name0 in used) uniqueName(name0, used) else name0
                var guard = 0
                while (true) {
                    if (storage.moveFileInto(tempFile, "$relDir/$name")) {
                        // 落盘后再列一次拿真实的尺寸/修改时间
                        entry = storage.listFiles(relDir).firstOrNull { it.name == name }
                        return@synchronized
                    }
                    // 失败分两种：① 目标名被并发占用（moveFileInto 拒绝覆盖）→ 换名重试；
                    //             ② 目标名空闲却仍失败 → 真实 I/O 失败（磁盘满 / 权限），立即报错
                    val node = storage.nodeFor("$relDir/$name")
                    if (!runCatching { File(node).exists() }.getOrDefault(false)) {
                        throw IOException("写入存储失败")
                    }
                    used += name
                    name = uniqueName(name0, used)
                    if (++guard > MAX_NAME_RETRY) throw IOException("写入存储失败")
                }
            }
            val rel = "$relDir/$name"

            scanToMediaStore(storage.resolve(rel))

            Saved(
                name = entry?.name ?: name,
                path = entry?.path ?: storage.nodeFor(rel),
                parentPath = entry?.parentPath ?: storage.nodeFor(relDir),
                size = entry?.size ?: 0L,
                lastModified = entry?.lastModified ?: System.currentTimeMillis()
            )
        }
    }

    /** 同名不冲突的名字：`a.mp4` → `a(1).mp4`、`a(2).mp4`… */
    private fun uniqueName(name: String, used: Set<String>): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = "$base($i)$ext"
            if (candidate !in used) return candidate
            i++
        }
    }

    private fun scanToMediaStore(file: File) {
        try {
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        } catch (_: Exception) {
            // 扫描失败不影响上传结果（媒体库索引由 TransHttpServer / SyncManager 另行维护）
        }
    }

    companion object {

        /** 并发撞名时的换名重试上限（正常最多 1~2 次；超过即判为真实 I/O 失败） */
        private const val MAX_NAME_RETRY = 50

        /** 文件名消毒：去掉路径分隔、控制字符，防止路径穿越 */
        fun sanitizeFileName(raw: String?): String {
            var n = (raw ?: "")
                .substringAfterLast('/')
                .substringAfterLast('\\')
            n = n.replace(Regex("[\\x00-\\x1f]"), "").trim().trimEnd('.', ' ')
            if (n.isEmpty()) n = "file"
            if (n.length > 180) {
                val dot = n.lastIndexOf('.')
                n = if (dot > 0) n.substring(0, dot).take(170) + n.substring(dot) else n.take(180)
            }
            return n
        }

        /** 相对路径消毒：拆分为安全文件夹段，过滤 ../ 等非法内容 */
        fun sanitizeRelativePath(raw: String?): List<String> =
            (raw ?: "")
                .split('/', '\\')
                .map { it.trim() }
                .filter { seg ->
                    seg.isNotEmpty() && seg != "." && seg != ".." && !seg.startsWith('.') &&
                        seg.length <= 100 && !seg.contains(':')
                }
                .take(8)
    }
}
