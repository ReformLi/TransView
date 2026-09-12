package com.hpu.transview.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import com.hpu.transview.model.Category
import com.hpu.transview.model.FileEntry
import com.hpu.transview.model.SortOrder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 专属沙盒目录策略：App 的全部存储收在 /sdcard/TransView/ 内，
 * 严禁读写/扫描系统公共目录（Movies/Pictures/Download 等），防误扫系统垃圾文件与越界删除。
 */
object FileLocations {

    /** 沙盒总根：/sdcard/TransView */
    val sandboxRoot: File
        get() = File(Environment.getExternalStorageDirectory(), "TransView")

    /** 各分类根目录（均在沙盒内），首次访问自动创建 */
    fun root(category: Category): File =
        File(
            sandboxRoot,
            when (category) {
                Category.VIDEO -> "Movies"
                Category.IMAGE -> "Pictures"
                Category.OTHER -> "Downloads"
            }
        ).apply { mkdirs() }

    /** 三个分类根目录（对账/清理范围仅限沙盒内） */
    fun allRoots(): List<File> = Category.entries.map { root(it) }

    /** 判断某目录是否是分类根目录本身（清理空文件夹时不得删除根目录） */
    fun isRoot(dir: File): Boolean =
        allRoots().any { it.absolutePath == dir.absolutePath }

    /**
     * 路径安全校验：删除/清理等破坏性操作前必须调用。
     * 规范化路径必须位于沙盒 /sdcard/TransView 之内，防止越界误删系统文件。
     */
    fun isInsideSandbox(file: File): Boolean = runCatching {
        val sandbox = sandboxRoot.canonicalPath + File.separator
        file.canonicalPath.startsWith(sandbox)
    }.getOrDefault(false)
}

val VIDEO_EXTS = setOf(
    "mp4", "mkv", "avi", "mov", "flv", "wmv", "m4v", "ts", "webm", "3gp", "mpg", "mpeg", "rmvb", "rm"
)
val IMAGE_EXTS = setOf(
    "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "tiff"
)

fun File.isVideoFile(): Boolean = extension.lowercase() in VIDEO_EXTS
fun File.isImageFile(): Boolean = extension.lowercase() in IMAGE_EXTS

/** 自然排序比较：EP2 < EP10，用于自动连播 / 图片切换的先后顺序 */
fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var i2 = i
            while (i2 < a.length && a[i2].isDigit()) i2++
            var j2 = j
            while (j2 < b.length && b[j2].isDigit()) j2++
            val na = a.substring(i, i2).trimStart('0')
            val nb = b.substring(j, j2).trimStart('0')
            val cmp = na.length.compareTo(nb.length).takeIf { it != 0 } ?: na.compareTo(nb)
            if (cmp != 0) return cmp
            i = i2
            j = j2
        } else {
            val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (cmp != 0) return cmp
            i++
            j++
        }
    }
    return (a.length - i).compareTo(b.length - j)
}

object FileUtils {

    // ————— 文件树遍历（SyncManager 对账用） —————

    /**
     * 递归遍历媒体根目录，返回所有非隐藏文件（不含隐藏文件/目录）。
     * 收集视频/图片/其他三类全部文件，具体类型由调用方经 MediaType.fromFile 判定入库。
     */
    fun listMediaFilesRecursively(root: File): List<File> {
        val result = ArrayList<File>()
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (child.name.startsWith(".")) continue
                if (child.isDirectory) {
                    stack.addLast(child)
                } else if (child.isFile) {
                    result.add(child)
                }
            }
        }
        return result
    }

    /** 收集三个媒体根目录下的全部媒体文件 */
    fun listAllMediaFiles(): List<File> =
        FileLocations.allRoots().flatMap { listMediaFilesRecursively(it) }

    // ————— 空文件夹清理 —————

    /**
     * 删除目录内的空文件夹（不含根目录本身）：自底向上——先递归子目录，
     * 子目录删空后若父目录也变空则继续向上删。
     * 越界保护：root 必须位于 /sdcard/TransView 沙盒内，否则拒绝执行。
     * @return 实际删除的空文件夹数量
     */
    fun cleanEmptyFolders(root: File): Int {
        if (!FileLocations.isInsideSandbox(root)) return 0
        var removed = 0
        val children = root.listFiles() ?: return 0
        for (child in children) {
            if (!child.isDirectory || child.name.startsWith(".")) continue
            removed += cleanEmptyFolders(child)
            // 子级处理完后，若该目录已空则删除（当前层判断，递归返回时父层自然继续判断）
            if (child.isDirectory && child.listFiles()?.isEmpty() == true) {
                if (child.delete()) removed++
            }
        }
        return removed
    }

    /**
     * 删除一个文件或文件夹后，若其父目录变空则向上递归删除空目录，
     * 直到遇到非空目录或分类根目录为止；不会越过 /sdcard/TransView 沙盒边界。
     * @return 一路删掉的空父目录数量（不含 start 本身）
     */
    fun deleteEmptyAncestors(start: File): Int {
        if (!FileLocations.isInsideSandbox(start)) return 0
        var removed = 0
        var dir: File? = start.parentFile ?: return 0
        while (dir != null && dir.isDirectory && !FileLocations.isRoot(dir)) {
            val entries = dir.listFiles() ?: break
            if (entries.isNotEmpty()) break
            if (!dir.delete()) break
            removed++
            dir = dir.parentFile
        }
        return removed
    }

    // ————— App 内主动删除 —————

    /**
     * 物理删除单个媒体文件；成功后若父目录变空则连带删除空目录。
     * 越界保护：目标必须在 /sdcard/TransView 沙盒内，否则直接失败。
     * @return true=物理删除成功；false=删除失败（调用方不得删数据库记录）
     */
    fun deletePhysicalFile(file: File): Boolean {
        if (!FileLocations.isInsideSandbox(file)) return false
        if (!file.exists()) return true // 文件本就不存在，视为成功
        return runCatching {
            if (!file.delete()) return false
            deleteEmptyAncestors(file)
            true
        }.getOrDefault(false)
    }

    // ————— 媒体时长提取（入库用，带缓存交给调用方管理） —————

    /** 提取视频时长（毫秒）；失败或非视频返回 0。须在 IO 线程调用 */
    fun extractVideoDuration(file: File): Long = runCatching {
        android.media.MediaMetadataRetriever().use { r ->
            r.setDataSource(file.absolutePath)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        }
    }.getOrDefault(0L)

    /** 列出目录内属于该分类的文件与全部子文件夹（文件夹在前） */
    fun listEntries(dir: File, category: Category, order: SortOrder): List<FileEntry> {
        val all = dir.listFiles()?.toList() ?: return emptyList()
        val visible = all.filter { !it.name.startsWith(".") }
        val (dirs, files) = visible.partition { it.isDirectory }
        val matched = when (category) {
            Category.VIDEO -> files.filter { it.isVideoFile() }
            Category.IMAGE -> files.filter { it.isImageFile() }
            Category.OTHER -> files.filter { !it.isVideoFile() && !it.isImageFile() }
        }
        val cmp: Comparator<FileEntry> = when (order) {
            SortOrder.NAME_ASC -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortOrder.NAME_DESC -> compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortOrder.TIME_DESC -> compareByDescending { it.lastModified }
            SortOrder.TIME_ASC -> compareBy { it.lastModified }
        }
        fun toEntries(list: List<File>) = list.map {
            FileEntry(it, it.name, it.isDirectory, if (it.isDirectory) 0L else it.length(), it.lastModified())
        }
        return toEntries(dirs).sortedWith(cmp) + toEntries(matched).sortedWith(cmp)
    }

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1fKB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1fMB", mb)
        return String.format(Locale.US, "%.2fGB", mb / 1024.0)
    }

    fun formatDate(millis: Long): String = dateFmt.format(Date(millis))

    /** 毫秒 → mm:ss 或 h:mm:ss */
    fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    /** 按扩展名猜测 MIME 类型 */
    fun mimeFor(file: File): String = when (file.extension.lowercase()) {
        "apk" -> "application/vnd.android.package-archive"
        "pdf" -> "application/pdf"
        "txt", "log" -> "text/plain"
        "json" -> "application/json"
        "html", "htm" -> "text/html"
        "xml" -> "text/xml"
        "zip" -> "application/zip"
        "rar" -> "application/vnd.rar"
        "7z" -> "application/x-7z-compressed"
        "epub" -> "application/epub+zip"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "wav" -> "audio/x-wav"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        else -> "application/octet-stream"
    }

    /** 调用系统应用打开「其他」分类文件（APK→安装器 / PDF→阅读器…）
     *  经 IntentUtils 启动：隐式 ACTION_VIEW 在部分 ROM 上会因 Instrumentation hook 静默失败 */
    fun openExternal(context: Context, file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeFor(file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            IntentUtils.startSafely(context, intent, "无法打开此文件类型，请安装对应应用")
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开文件：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
