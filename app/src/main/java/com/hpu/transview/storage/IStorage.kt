package com.hpu.transview.storage

import android.net.Uri
import java.io.InputStream

/**
 * 存储中的一条条目（文件或目录）——[IStorage] 与 UI/数据库之间的统一载体。
 *
 * [path] 是**存储身份**，也是数据库 `media_items.filePath` / `parentFolder` 的取值，
 * v1.14 起恒为**绝对路径**，如 `/storage/0000-0000/TransView/Movies/a.mp4`
 */
data class StorageFile(
    val name: String,
    val path: String,
    /** 相对沙盒根（`TransView/`）的路径，如 `Movies/第一集.mp4`；沙盒根自身为空串 */
    val relativePath: String,
    /** 父节点身份（= 数据库 parentFolder）；沙盒根的子项的父即沙盒根路径 */
    val parentPath: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
) {
    /** 小写扩展名（无扩展名时为空串） */
    val extension: String get() = name.substringAfterLast('.', "").lowercase()

    /** 去掉扩展名的文件名（无扩展名时即 [name]） */
    val nameWithoutExtension: String
        get() = if (extension.isEmpty()) name else name.dropLast(extension.length + 1)
}

/**
 * 存储访问接口：目录/文件的增删查写。
 *
 * v1.14 起只有**唯一实现** [FileStorage]（纯 `java.io.File`）—— 原有的 SAF 文档树实现已删除：
 * Vidda 等电视 ROM 直接屏蔽 `ACTION_OPEN_DOCUMENT_TREE`，而 U 盘挂载点
 * `/storage/XXXX-XXXX` 用 `java.io.File` 反而可读写（`MANAGE_EXTERNAL_STORAGE` 已授权）。
 * 接口本身保留，是为了让上传落盘 / 媒体库 / 对账 / 播放继续用同一组语义，无需散落 File 细节。
 *
 * ## 两套寻址
 * - **相对寻址**（接口规格里的 `relativePath`）：相对沙盒根 `TransView/`，用于上传落盘、遍历、
 *   对账等「按固定层级走」的场景；
 * - **节点寻址**（[nodeFor] / [relativeOf] / [listChildren] / [deleteNode] 等）：以
 *   [StorageFile.path] 为节点的唯一身份，用于 UI 导航（媒体库进入子目录、返回上级、删除卡片）
 *   —— 它同时就是数据库里存的字符串，两端天然对齐。
 */
interface IStorage {

    /** 沙盒根路径（绝对路径） */
    fun getRootPath(): String

    /** 沙盒根是否存在（目录存在） */
    fun exists(): Boolean

    /** 沙盒根当前是否可写 */
    fun canWrite(): Boolean

    /** 可用空间（字节）；取不到返回 0 */
    fun getUsableSpace(): Long

    /** 总空间（字节）；取不到返回 0 */
    fun getTotalSpace(): Long

    /** 创建目录（含多级），已存在视为成功 */
    fun createDirectory(relativePath: String): Boolean

    /** 把 [inputStream] 的内容写入 `relativePath`（覆盖同名），父目录不存在时自动创建 */
    fun writeFile(relativePath: String, inputStream: InputStream): Boolean

    /** 列出 `relativePath` 目录下的直接子项（不含孙项）；失败返回空列表 */
    fun listFiles(relativePath: String): List<StorageFile>

    /** 删除 `relativePath`（文件直接删；空目录可删，非空目录失败）。**不做**父目录连带清理，
     *  需要「删文件后顺手收掉空目录」的场景请用 [deleteNode] */
    fun deleteFile(relativePath: String): Boolean

    /** 打开 `relativePath` 的输入流；不存在或不可读返回 null */
    fun openInputStream(relativePath: String): InputStream?

    // ————————————————— 以下为节点寻址与 UI 便捷方法（规格之外，供媒体库/对账复用） —————————————————

    /** 展示用类型名（日志与设置页说明）；v1.14 起只有 File 一种形态 */
    val kindLabel: String
        get() = "File"

    /** 相对路径 → 节点身份（恒为绝对路径）；不可映射时返回空串 */
    fun nodeFor(relativePath: String): String

    /** 节点身份 → 相对路径；不属于本沙盒时返回 null */
    fun relativeOf(nodePath: String): String?

    /** 父节点身份；已是沙盒根或不属于本存储时返回 null */
    fun parentNode(nodePath: String): String? {
        val rel = relativeOf(nodePath) ?: return null
        if (rel.isEmpty()) return null
        val parentRel = rel.substringBeforeLast('/', "")
        return nodeFor(parentRel)
    }

    /** 节点身份 → 可直接交给 Coil / ExoPlayer / Intent 的 Uri */
    fun uriOfNode(nodePath: String): Uri

    /** 节点是否存在 */
    fun nodeExists(nodePath: String): Boolean

    /** 删除节点并向上清理空目录（UI 删除用） */
    fun deleteNode(nodePath: String): Boolean {
        val rel = relativeOf(nodePath) ?: return false
        if (rel.isEmpty()) return false
        val ok = deleteFile(rel)
        if (ok) cleanEmptyAncestors(rel)
        return ok
    }

    /** 列出某节点下的直接子项（节点可以是沙盒根，也可以是任一目录节点） */
    fun listChildren(nodePath: String): List<StorageFile> {
        val rel = relativeOf(nodePath) ?: return emptyList()
        return listFiles(rel)
    }

    /** 在节点上新建文件并写入（UI/上传用；同名已存在时由调用方自行换名） */
    fun writeNode(nodePath: String, inputStream: InputStream): Boolean {
        val rel = relativeOf(nodePath) ?: return false
        if (rel.isEmpty()) return false
        return writeFile(rel, inputStream)
    }

    /**
     * 递归清理空目录（不含沙盒根自身），自底向上。
     *
     * 必须先把子目录删空、再删父目录——这个顺序由本方法的递归天然保证。
     * @return 实际删除的空目录数
     */
    fun cleanEmptyFolders(relativePath: String = ""): Int {
        var removed = 0
        for (child in listFiles(relativePath)) {
            if (!child.isDirectory || child.name.startsWith(".")) continue
            removed += cleanEmptyFolders(child.relativePath)
            if (listFiles(child.relativePath).isEmpty() && deleteFile(child.relativePath)) removed++
        }
        return removed
    }

    /** 沙盒已用空间（递归求和，注意在 IO 线程调用） */
    fun usedSpace(): Long {
        var total = 0L
        val stack = ArrayDeque<String>()
        stack.addLast("")
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            for (child in listFiles(dir)) {
                if (child.isDirectory) {
                    if (!child.name.startsWith(".")) stack.addLast(child.relativePath)
                } else {
                    total += child.size
                }
            }
        }
        return total
    }

    /** 删除 [relativePath] 后，自底向上清理变空的父目录（到沙盒根为止） */
    fun cleanEmptyAncestors(relativePath: String) {
        var rel = relativePath
        while (true) {
            val parentRel = rel.substringBeforeLast('/', "")
            if (parentRel == rel || parentRel.isEmpty()) return    // 已到沙盒根
            if (listFiles(parentRel).isNotEmpty()) return
            if (!deleteFile(parentRel)) return
            rel = parentRel
        }
    }
}
