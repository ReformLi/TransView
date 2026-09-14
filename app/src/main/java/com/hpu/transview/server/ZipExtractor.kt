package com.hpu.transview.server

import android.content.Context
import android.os.Build
import com.hpu.transview.model.Category
import com.hpu.transview.ui.settings.SettingsStore
import com.hpu.transview.util.FileLocations
import com.hpu.transview.util.FileUtils
import com.hpu.transview.util.IMAGE_EXTS
import com.hpu.transview.util.VIDEO_EXTS
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.util.zip.ZipFile

/**
 * 上传压缩包的**自动解压流水线**（仅视频 / 图片分类触发）。
 *
 * 流程：
 * 1. 把 NanoHTTPD 落地的临时文件暂存到工作区 `.temp_unzip/u<ns>/archive/<原名>`；
 * 2. 读 ZIP 中央目录拿到元数据，**只统计当前分类命中的条目**，换算预估解压体积；
 * 3. 空间校验：剩余空间 < 预估体积 × 1.2 → 拒绝解压（原包保留到 Downloads）；
 * 4. 流式解压命中的条目到 `.temp_unzip/u<ns>/files/`（保持压缩包内的目录结构），
 *    非本分类的条目**直接跳过不落盘**；解压途中累计字节数超预算立即中止（防伪造元数据的膨胀包）；
 * 5. 命中文件按 [UploadStorage.save] 搬进分类目录（同名自动加 (1)(2) 后缀、不覆盖）；
 * 6. 收尾清空工作区；若「保留原压缩包」开启，则先把原包搬进 Downloads 再清。
 *
 * 兜底原则：**任何失败路径都不丢用户的压缩包** —— 一个目标文件都没找到、空间不足、
 * ZIP 损坏、归位失败，统一把原包移入 `/sdcard/TransView/Downloads/` 并返回提示文案。
 *
 * 内存：全程固定 64KiB 缓冲区流式读写（[BUFFER_SIZE]），不整体载入内存；
 * 用 [ZipFile] 而非 [java.util.zip.ZipInputStream] 的原因见 [openZip]。
 */
class ZipExtractor(private val context: Context) {

    private val storage = UploadStorage(context)

    /** 解压结果类别（同时作为网页端 JSON 里的 `unzip` 字段值） */
    enum class Kind { EXTRACTED, NO_TARGET, NO_SPACE, FAILED }

    /**
     * @param kind        结果类别
     * @param movedFiles  已归位到分类目录的文件（供媒体索引入库）
     * @param keptZipPath 原压缩包被保留时的最终路径；已按设置删除时为 null
     * @param message     面向用户的提示（网页端 toast + 电视端 Toast），必定非空
     */
    data class Outcome(
        val kind: Kind,
        val movedFiles: List<File>,
        val keptZipPath: String?,
        val message: String
    )

    /** ZIP 元数据预估（仅统计匹配当前分类的条目） */
    private data class Estimate(val count: Int, val bytes: Long)

    /** 一次流式解压的产出 */
    private class ExtractReport(val files: List<File>, val budgetExceeded: Boolean)

    /** 解压途中超出空间预算（元数据不可信时的运行时兜底） */
    private class BudgetExceeded : IOException("存储空间不足")

    /**
     * 处理一次「压缩包自动解压」上传。
     *
     * @param uploadedTemp NanoHTTPD 刚落地的临时文件（会被移动到工作区，结束时不再存在）
     * @param zipName      用户看到的原始文件名（用于保留原包时的命名）
     * @param category     上传时选择的分类（VIDEO / IMAGE）
     */
    fun process(uploadedTemp: File, zipName: String, category: Category): Outcome {
        val workspace = File(FileLocations.tempUnzipDir, "u" + System.nanoTime())
        if (!workspace.mkdirs() && !workspace.isDirectory) {
            // 工作区都建不出来：不动用户文件，按失败处理（原包留在 NanoHTTPD 临时目录由它清理前
            // 会先被 storage.save 之外的路径丢弃 —— 这里显式说明，避免误以为文件已保留）
            return Outcome(
                Kind.FAILED, emptyList(), null,
                "解压工作目录创建失败，压缩包未能保存"
            )
        }

        val archiveDir = File(workspace, "archive")
        val destRoot = File(workspace, "files")
        val archive = File(archiveDir, UploadStorage.sanitizeFileName(zipName))
        if (!moveInto(uploadedTemp, archive)) {
            purge(workspace)
            return Outcome(Kind.FAILED, emptyList(), null, "压缩包暂存失败，请重试")
        }

        try {
            // ——— 1. 元数据预估 + 空间校验 ———
            val estimate = runCatching { estimateMatching(archive, category) }.getOrElse {
                // 后缀是 .zip 但内容不是有效 ZIP（损坏 / 改名）
                val kept = keepArchive(archive)
                return Outcome(Kind.FAILED, emptyList(), kept, "压缩包无法解析" + keptTail(kept))
            }
            val available = runCatching { workspace.usableSpace }.getOrDefault(0L)
            val needed = (estimate.bytes * SPACE_FACTOR).toLong()
            if (available <= 0 || needed > available) {
                val kept = keepArchive(archive)
                return Outcome(
                    Kind.NO_SPACE, emptyList(), kept,
                    "存储空间不足，已拒绝解压（约需 ${FileUtils.formatSize(needed)}，" +
                        "可用 ${FileUtils.formatSize(available)}）" + keptTail(kept)
                )
            }
            if (estimate.count == 0) {
                val kept = keepArchive(archive)
                return Outcome(
                    Kind.NO_TARGET, emptyList(), kept,
                    "压缩包中未找到${categoryLabel(category)}文件" + keptTail(kept)
                )
            }

            // ——— 2. 流式解压到工作区（只写本分类的条目） ———
            val report = extract(archive, category, destRoot, available)
            if (report.budgetExceeded) {
                val kept = keepArchive(archive)
                return Outcome(
                    Kind.NO_SPACE, emptyList(), kept,
                    "存储空间不足，已中止解压" + keptTail(kept)
                )
            }
            if (report.files.isEmpty()) {
                val kept = keepArchive(archive)
                return Outcome(
                    Kind.NO_TARGET, emptyList(), kept,
                    "压缩包中未找到${categoryLabel(category)}文件" + keptTail(kept)
                )
            }

            // ——— 3. 按压缩包内目录结构归位到分类目录 ———
            val destBase = destRoot.canonicalPath + File.separator
            val moved = ArrayList<File>(report.files.size)
            for (file in report.files) {
                val relDir = runCatching {
                    val parent = file.parentFile ?: return@runCatching ""
                    // 双保险：只接受解压工作区内的相对目录（Zip Slip 已在校验阶段拦截）
                    if (!parent.canonicalPath.startsWith(destBase)) return@runCatching ""
                    parent.canonicalPath.removePrefix(destBase)
                }.getOrDefault("")
                storage.save(file, file.name, relDir, category).onSuccess { moved += it }
            }
            if (moved.isEmpty()) {
                val kept = keepArchive(archive)
                return Outcome(
                    Kind.FAILED, emptyList(), kept,
                    "解压出的文件无法归位" + keptTail(kept)
                )
            }

            // ——— 4. 收尾：原包按设置保留或删除（保留则放入 Downloads） ———
            val kept = if (SettingsStore.keepOriginalZip) keepArchive(archive) else null
            val tail = if (kept != null) "，原压缩包已保留在「其他」分类" else "，原压缩包已删除"
            return Outcome(
                Kind.EXTRACTED, moved, kept,
                "已从压缩包解压 ${moved.size} 个${categoryLabel(category)}文件$tail"
            )
        } catch (e: Exception) {
            val kept = runCatching { keepArchive(archive) }.getOrNull()
            return Outcome(
                Kind.FAILED, emptyList(), kept,
                "解压失败：${e.message ?: "未知错误"}" + keptTail(kept)
            )
        } finally {
            purge(workspace)
        }
    }

    // ————————————————— 元数据 / 解压 —————————————————

    /** 只统计当前分类命中的条目：数量 + 预估解压字节数（读中央目录，不解压） */
    private fun estimateMatching(zip: File, category: Category): Estimate {
        openZip(zip).use { zf ->
            var count = 0
            var bytes = 0L
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !matches(entry.name, category)) continue
                count++
                val size = entry.size
                // 少数压缩工具不写未压缩大小（-1），退化为按压缩体积估算，运行时还有预算兜底
                bytes += if (size > 0) size else entry.compressedSize.coerceAtLeast(0L)
            }
            return Estimate(count, bytes)
        }
    }

    /** 流式解压：只写匹配条目，保持包内目录结构；累计字节超预算即刻中止 */
    private fun extract(
        zip: File,
        category: Category,
        destRoot: File,
        budgetBytes: Long
    ): ExtractReport {
        val written = ArrayList<File>()
        var total = 0L
        try {
            openZip(zip).use { zf ->
                val buffer = ByteArray(BUFFER_SIZE)
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !matches(entry.name, category)) continue
                    val rel = safeRelativePath(entry.name) ?: continue
                    val target = File(destRoot, rel)
                    // Zip Slip 防护：规范化后的落点必须仍在解压工作区内，否则丢弃该条目
                    if (!target.canonicalPath.startsWith(destRoot.canonicalPath + File.separator)) continue
                    target.parentFile?.mkdirs()
                    zf.getInputStream(entry).use { input ->
                        FileOutputStream(target).use { raw ->
                            BufferedOutputStream(raw, BUFFER_SIZE).use { output ->
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n <= 0) break
                                    total += n
                                    if (total > budgetBytes) throw BudgetExceeded()
                                    output.write(buffer, 0, n)
                                }
                            }
                        }
                    }
                    written += target
                }
            }
        } catch (e: BudgetExceeded) {
            return ExtractReport(written, true)
        }
        return ExtractReport(written, false)
    }

    /** 条目名是否属于该分类（按扩展名判定，与媒体库过滤规则一致） */
    private fun matches(entryName: String, category: Category): Boolean {
        val ext = entryName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return false
        return when (category) {
            Category.VIDEO -> ext in VIDEO_EXTS
            Category.IMAGE -> ext in IMAGE_EXTS
            Category.OTHER -> ext !in VIDEO_EXTS && ext !in IMAGE_EXTS
        }
    }

    /**
     * 打开 ZIP。
     *
     * 用 [ZipFile]（中央目录随机访问）而不是 `ZipInputStream` 的两个原因：
     * ① 预估解压体积必须读中央目录里记录的未压缩大小 —— 用流式读取就得把整个包解一遍才能算出，
     *    既慢又毫无意义；
     * ② 中文压缩包兼容 —— Windows 资源管理器压缩的文件用 GBK 编码条目名且不置 UTF-8 标志位，
     *    而 `ZipInputStream` 固定按 UTF-8 解码（遇非法字节直接抛 ZipException），
     *    整包会解不出来；`ZipFile` 可以指定字符集。
     * 读取仍是**逐条目流式**的（[ZipFile.getInputStream] 边解压边读），内存占用由调用方的
     * 64KiB 缓冲区决定，不随文件大小增长。
     */
    private fun openZip(file: File): ZipFile {
        if (!canChooseCharset) return ZipFile(file) // API < 24 无法指定字符集，只能用默认
        val utf8 = runCatching { ZipFile(file, Charsets.UTF_8) }.getOrNull()
        if (utf8 != null && !looksGarbled(utf8)) return utf8
        // 走到这里有两类情况：① UTF-8 解码出替换字符（宽松解码器）；② 直接抛异常（严格解码器）。
        // 两者都判为 GBK 压缩包，用 GBK 重开一次。
        val gbk = runCatching { ZipFile(file, Charset.forName(CHARSET_GBK)) }.getOrNull()
        if (gbk == null) return utf8 ?: ZipFile(file) // GBK 也不行：退回 UTF-8 结果 / 交由调用方抛
        if (utf8 != null) runCatching { utf8.close() }
        return gbk
    }

    /** 条目名里出现替换字符 U+FFFD → 说明按 UTF-8 解出来的名字是坏的 */
    private fun looksGarbled(zf: ZipFile): Boolean = runCatching {
        val entries = zf.entries()
        var scanned = 0
        while (entries.hasMoreElements() && scanned < GARBLE_SCAN_LIMIT) {
            if (entries.nextElement().name.contains('\uFFFD')) return@runCatching true
            scanned++
        }
        false
    }.getOrDefault(false)

    private val canChooseCharset: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

    /**
     * 条目名 → 工作区内的安全相对路径。
     * 复用 [UploadStorage.sanitizeRelativePath]：剔除 `..`、`.`、空段与隐藏段，
     * 再额外过滤 Windows 盘符（`C:`）这类异常段，最后一段按文件名消毒规则处理。
     */
    private fun safeRelativePath(entryName: String): String? {
        val segments = UploadStorage.sanitizeRelativePath(entryName).filter { !it.contains(':') }
        if (segments.isEmpty()) return null
        val fileName = UploadStorage.sanitizeFileName(segments.last())
        return (segments.dropLast(1) + fileName).joinToString("/")
    }

    // ————————————————— 文件搬运 / 兜底 —————————————————

    /**
     * 把压缩包搬进 Downloads（「其他」分类的家）并返回最终路径。
     * 同名时经 [UploadStorage.save] 自动加 (1)(2) 后缀，不会覆盖已有文件。
     */
    private fun keepArchive(archive: File): String? =
        storage.save(archive, archive.name, "", Category.OTHER)
            .map { it.absolutePath }
            .getOrElse { null }

    /**
     * 移动文件。工作区与分类目录同在 /sdcard 同一卷，`renameTo` 即为改名（零拷贝）；
     * 万一跨卷失败则退化为 copy + delete。刻意不用 `java.nio.file.Files`：那套 API
     * 在 Android 上要求 API 26+，本 App 的 minSdk 是 21。
     */
    private fun moveInto(src: File, dst: File): Boolean = runCatching {
        dst.parentFile?.mkdirs()
        if (src.renameTo(dst)) return@runCatching dst.isFile
        src.copyTo(dst, overwrite = true)
        src.delete()
        dst.isFile
    }.getOrDefault(false)

    /**
     * 删除本次解压的工作目录（含 `archive/`、`files/` 与目录自身）。
     *
     * 只删自己这次的 `u<ns>` 目录、不动 `.temp_unzip` 其余内容 —— NanoHTTPD 是多工作线程模型，
     * 两台手机可能同时在解压，扫掉整个工作区会把别人的活干掉。
     * 删除失败（占用/权限）留下的残留由 SyncManager 每次对账兜底清空。
     */
    private fun purge(workspace: File) {
        if (!FileLocations.isInsideSandbox(workspace)) return
        runCatching { workspace.deleteRecursively() }
    }

    private fun categoryLabel(category: Category): String = when (category) {
        Category.VIDEO -> "视频"
        Category.IMAGE -> "图片"
        Category.OTHER -> "文件"
    }

    /** 提示语尾巴：按「原包到底有没有被保住」如实措辞，不能一律说「已保留」 */
    private fun keptTail(keptPath: String?): String =
        if (keptPath != null) "，已保留原压缩包" else "，且原压缩包未能保留"

    private companion object {
        /** 流式缓冲区 64KiB：解压全程内存占用与之同量级（远低于 20MB 要求） */
        const val BUFFER_SIZE = 64 * 1024

        /** 空间安全系数：预估解压体积 × 1.2 作为准入线 */
        const val SPACE_FACTOR = 1.2

        const val CHARSET_GBK = "GBK"

        /** 乱码探测最多看多少个条目名 */
        const val GARBLE_SCAN_LIMIT = 64
    }
}
