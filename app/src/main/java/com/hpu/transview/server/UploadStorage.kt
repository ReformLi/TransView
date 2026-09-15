package com.hpu.transview.server

import android.content.Context
import android.media.MediaScannerConnection
import com.hpu.transview.model.Category
import com.hpu.transview.util.FileLocations
import java.io.File
import java.io.IOException

/**
 * 上传文件的落盘规则（落点始终跟随 FileLocations 的**活动存储**：首选U盘且在位时写入U盘沙盒；
 * 降级模式（U盘拔出）自动写入内部存储沙盒，上传不中断；U盘插回后恢复写U盘）：
 * - 分类根目录：视频→Movies 图片→Pictures 其他→Download
 * - 文件夹上传：按 relativePath 重建层级，同名文件夹自动合并
 * - 同名文件：追加 (1)(2)… 后缀，绝不覆盖已有文件
 */
class UploadStorage(private val context: Context) {

    /** 保存并返回最终落盘的目标文件（供媒体索引入库） */
    fun save(tempFile: File, rawName: String, rawRelPath: String, category: Category): Result<File> {
        return runCatching {
            val name = sanitizeFileName(rawName)
            val segments = sanitizeRelativePath(rawRelPath)
            // 活动媒体根（FileLocations.getMediaRootDir 的分类子目录）：降级模式=内部存储，正常=首选存储
            var dir = FileLocations.root(category)
            for (seg in segments) {
                dir = File(dir, seg)
                if (!dir.exists() && !dir.mkdirs()) throw IOException("无法创建文件夹：$seg")
            }
            var target = File(dir, name)
            if (target.exists()) target = uniqueTarget(dir, name)
            // 落盘搬运用 renameTo（临时目录与沙盒同在 /sdcard 卷上，即零拷贝改名）；
            // 跨文件系统失败时退化为 copy + delete。
            // 刻意不用 java.nio.file.Files.move/copy：那套 API 要求 API 26+，而本工程
            // minSdk 是 21（低版本会抛 NoClassDefFoundError，全部上传表现为「保存失败」），
            // 与 ZipExtractor.moveInto 同一标准。
            if (!tempFile.renameTo(target)) {
                // renameTo 失败的另一可能是目标极小概率已被并发上传占用（检查与搬运之间存在窗口）：
                // copyTo(overwrite = false) 撞名会抛异常而不是覆盖，这里先换一个不重名的落点再试
                if (target.exists()) target = uniqueTarget(dir, name)
                tempFile.copyTo(target, overwrite = false)
                tempFile.delete()
            }
            scanToMediaStore(target)
            target
        }
    }

    private fun uniqueTarget(dir: File, name: String): File {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = File(dir, "$base($i)$ext")
            if (!candidate.exists()) return candidate
            i++
        }
    }

    private fun scanToMediaStore(file: File) {
        try {
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        } catch (_: Exception) {
            // 扫描失败不影响上传结果
        }
    }

    companion object {

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
                    seg.isNotEmpty() && seg != "." && seg != ".." && !seg.startsWith('.') && seg.length <= 100
                }
                .take(8)
    }
}
