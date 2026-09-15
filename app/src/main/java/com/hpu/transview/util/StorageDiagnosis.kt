package com.hpu.transview.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import com.hpu.transview.BuildConfig
import com.hpu.transview.model.Category
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 存储诊断日志导出（设置 → 存储与数据 → 导出存储诊断日志）。
 *
 * 用途：电视上没有终端、看不到 logcat，外接存储（U 盘/SD 卡）"插了却不出现"时无法定位。
 * 本工具把判定链路的全部原始证据写进一个 txt，用户用文件管理器或拷到 U 盘拿到电脑上看。
 *
 * ## 落盘位置
 * 活动沙盒的「其他」分类目录（[FileLocations.root] 的 [Category.OTHER]）→ `/TransView/Downloads/`。
 * **刻意与「其他」分类同路径**：该目录在对账（SyncManager.listAllMediaFiles）的扫描范围内，
 * 导出后触发一次对账即被索引进 media_items，于是能在「其他」标签里直接看到并打开这个 txt；
 * U 盘模式下则天然落在 U 盘上，拔下来插电脑即可查看（这正是排障所需的取数通路）。
 *
 * ## 为什么记这么细
 * 卷能否被 App 用起来要过三道闸门：**卷存在**（渠道 A/C）→ **路径对 App 可见**（渠道 B/D/E）
 * → **可写**（写探针 + 沙盒落点检查）。v1.14 起候选列表由 `FileLocations.getWritableDevices`
 * 扫描 `/storage/` + 写探针生成，任何一环不通过该盘就不出现在「存储位置」里（设置页只显示
 * "内部存储"），所以日志必须逐层留证据，最后再用【判定说明】把"这块盘为什么没进列表"直接翻译成人话。
 *
 * ## 线程约定
 * 全程文件系统 IO，**[export] 不做线程切换，必须在 `Dispatchers.IO` 调用**。
 */
object StorageDiagnosis {

    /** 诊断日志文件名（固定名，每次导出覆盖写） */
    const val FILE_NAME = "storage_diagnosis.txt"

    /** 写探针文件名：在每个候选目录内创建后立即删除（隐藏文件，减少对用户的干扰） */
    private const val PROBE_NAME = ".transview_write_test"

    /** 章节分隔线 */
    private const val SEP = "------------------------"

    /** 沙盒目录名（与 [FileLocations] 保持一致；那边是 private，这里独立声明只为读日志时直观） */
    private const val SANDBOX_NAME = "TransView"

    /**
     * 诊断日志目标文件：活动沙盒「其他」分类目录下
     * （内部存储模式 = `/storage/emulated/0/TransView/Downloads/storage_diagnosis.txt`，
     * U 盘模式 = `/storage/XXXX-XXXX/TransView/Downloads/storage_diagnosis.txt`）。
     */
    fun targetFile(): File = File(FileLocations.root(Category.OTHER), FILE_NAME)

    /**
     * 扫描并导出诊断日志（**阻塞，须在 IO 线程调用**）。
     *
     * 写入约定：目录不存在先 `mkdirs()`；文件已存在直接**覆盖**（不追加）；UTF-8 编码；
     * `BufferedWriter` 逐行写（不先在内存拼整份文本）。任一渠道内部抛异常都会被单独捕获、
     * 把异常文本写进对应章节，**不中断其余渠道**。
     *
     * @return 实际写入的文件
     * @throws Exception 目标目录无法创建 / 文件不可写（权限、卷已拔出等）→ 调用方提示「导出失败」
     */
    fun export(context: Context): File {
        // 先把存储状态刷成最新（卷扫描 + canWrite 探测）：用户点这一项的本意就是"现在立刻看"，
        // 用旧快照会让【最终结果】与【判定说明】对不上当下真机状态
        runCatching { FileLocations.refresh() }
        val file = targetFile()
        file.parentFile?.mkdirs()
        BufferedWriter(OutputStreamWriter(FileOutputStream(file, false), Charsets.UTF_8)).use { w ->
            writeAll(context, w)
        }
        return file
    }

    // ————————————————————— 组装 —————————————————————

    private fun writeAll(context: Context, w: BufferedWriter) {
        // 写探针的探测目标（所有"可能是存储卷的目录"）；沙盒落点检查只挑真正的卷根
        val candidates = LinkedHashSet<File>()
        val volumeRoots = LinkedHashSet<File>()

        header(context, w)
        channelA(context, w, candidates, volumeRoots)
        channelB(w, candidates, volumeRoots)
        channelC(w, candidates)
        channelD(context, w, candidates, volumeRoots)
        channelE(w)
        writeProbe(w, candidates)
        sandboxProbe(w, volumeRoots)
        finalResult(w)
        verdict(context, w)
        w.line()
        w.line("========== 诊断日志结束 ==========")
    }

    private fun header(context: Context, w: BufferedWriter) {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        w.line("========== TransView 存储诊断日志 ==========")
        w.line("导出时间：$now")
        w.line("设备型号：${Build.MANUFACTURER} ${Build.MODEL}")
        w.line("Android 版本：${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        // minSdk/targetSdk 只在 API 24+ 可读；读不到就省略，不能让整行崩掉
        val sdks = runCatching {
            "    minSdk=${context.applicationInfo.minSdkVersion}  " +
                "targetSdk=${context.applicationInfo.targetSdkVersion}"
        }.getOrDefault("")
        w.line("App：${context.packageName} v${BuildConfig.VERSION_NAME}$sdks")
        w.line("（本文件由「设置 → 存储与数据 → 导出存储诊断日志」生成，用于排查外接存储识别/写入问题）")
    }

    /** 渠道 A：StorageManager.getStorageVolumes()（系统视角的卷清单，含隐藏卷） */
    private fun channelA(
        context: Context,
        w: BufferedWriter,
        candidates: MutableSet<File>,
        volumeRoots: MutableSet<File>
    ) {
        section(w, "【渠道 A】StorageManager.getStorageVolumes()")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            w.line("  该渠道不可用：getStorageVolumes() 需要 Android 7.0（API 24）及以上，当前 API ${Build.VERSION.SDK_INT}")
            w.line("  （旧版本会降级扫描 /storage 目录，见渠道 B）")
            return
        }
        val sm = runCatching {
            context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        }.getOrNull()
        if (sm == null) {
            w.line("  失败：getSystemService(STORAGE_SERVICE) 返回 null")
            return
        }
        val volumes = runCatching { sm.storageVolumes }.getOrElse {
            w.line("  异常：调用 getStorageVolumes() 抛出 ${describe(it)}")
            return
        }
        if (volumes.isEmpty()) {
            w.line("  该渠道无任何存储卷")
            return
        }
        w.line("  共 ${volumes.size} 个存储卷：")
        volumes.forEachIndexed { i, vol ->
            w.line("  [$i]")
            val uuid = runCatching { vol.uuid }.getOrNull()
            w.line("    uuid            : ${uuid ?: "null（内部主卷恒为 null）"}")
            w.line(
                "    description     : " +
                    runCatching { vol.getDescription(context) }.getOrElse { "异常：${describe(it)}" }
            )
            val dir = runCatching { vol.directory }.getOrNull()
            w.line("    getDirectory()  : ${dir?.absolutePath ?: "null（该卷对 App 无可见路径）"}")
            w.line("    反射 getPath()  : ${reflectPath(vol)}")
            w.line("    isRemovable     : ${runCatching { vol.isRemovable }.getOrDefault(false)}")
            w.line("    isPrimary       : ${runCatching { vol.isPrimary }.getOrDefault(false)}")
            w.line("    getState()      : ${runCatching { vol.state }.getOrDefault("?")}")
            if (dir != null) {
                candidates += dir
                volumeRoots += dir
            }
        }
    }

    /**
     * 反射 `StorageVolume.getPath()`（@hide API，仅调试用）。
     * 该方法在 API 29+ 已被系统移除、且受隐藏 API 限制，失败是**正常现象**——
     * 这里把失败原因原样写出来，避免用户误以为代码有 bug。
     */
    private fun reflectPath(vol: StorageVolume): String = runCatching {
        val m = StorageVolume::class.java.getDeclaredMethod("getPath")
        m.isAccessible = true
        (m.invoke(vol) as? String) ?: "null"
    }.getOrElse { "调用失败（${describe(it)}）——API 29+ 已移除该方法，属正常现象" }

    /** 渠道 B：/storage/ 目录扫描（App 视角的挂载点，最能反映"路径是否可见"） */
    private fun channelB(
        w: BufferedWriter,
        candidates: MutableSet<File>,
        volumeRoots: MutableSet<File>
    ) {
        section(w, "【渠道 B】/storage/ 目录扫描")
        val root = File("/storage")
        w.line("  " + statLine(root))
        val children = root.listFiles()
        if (children == null) {
            // Android 11+ 起第三方应用读不到 /storage 本身（属系统行为，非故障），
            // 此时**不能提前 return** —— 下面的挂载表兜底正是这种情况下的唯一证据来源
            w.line("    （无法列出 /storage：Android 11+ 起第三方应用读不到该目录，属系统行为）")
        } else {
            children.sortedBy { it.name }.forEach { d ->
                val note = when (d.name) {
                    "emulated" -> "  ← 系统目录：内部存储的别名入口"
                    "self" -> "  ← 系统别名：指向当前用户"
                    "encrypted" -> "  ← 系统目录：加密存储入口"
                    else -> ""
                }
                w.line("    " + statLine(d, note))
                candidates += d
                // 卷根候选：排除系统别名（emulated/self），emulated 下面的 0 才是内部存储真身
                if (d.name != "emulated" && d.name != "self") volumeRoots += d
                if (d.name == "emulated" || d.name == "encrypted") {
                    d.listFiles()?.sortedBy { it.name }?.forEach { sub ->
                        val subNote =
                            if (sub.absolutePath == Environment.getExternalStorageDirectory().absolutePath) {
                                "  ← 内部存储根（= /sdcard）"
                            } else ""
                        w.line("        " + statLine(sub, subNote))
                        candidates += sub
                        if (d.name == "emulated") volumeRoots += sub
                    }
                }
            }
        }
        mountTable(w)
    }

    /**
     * 渠道 B 兜底：`/proc/mounts` 里的存储相关挂载点。
     *
     * 必要性：Android 11+ 起第三方应用**列不出 `/storage` 根目录**（渠道 B 会直接返回"无法列出"），
     * 而 `/proc/mounts` 是世界可读的，能拿到"U 盘到底有没有被系统挂载、挂在哪、什么文件系统"
     * 这条最关键的证据——真机上没有它，渠道 B 等于空白。
     */
    private fun mountTable(w: BufferedWriter) {
        w.line()
        w.line("  ── 兜底：/proc/mounts 存储相关挂载点（不受分区存储限制）──")
        val lines = runCatching { File("/proc/mounts").readLines() }.getOrElse {
            w.line("    读取 /proc/mounts 失败：${describe(it)}")
            return
        }
        val keys = listOf("media_rw", "emulated", "sdcard", "vfat", "exfat", "fuse", "/mnt/", "/storage/")
        val printed = LinkedHashSet<String>()
        lines.forEach { line ->
            val parts = line.split(" ")
            if (parts.size < 3) return@forEach
            val mountPoint = parts[1]
            val fsType = parts[2]
            if (keys.none { mountPoint.contains(it) || fsType.contains(it) }) return@forEach
            if (!printed.add(mountPoint)) return@forEach
            w.line("    ${statLine(File(mountPoint))} | fs=$fsType | device=${parts[0]}")
        }
        if (printed.isEmpty()) w.line("    （无存储相关挂载点）")
    }

    /** 渠道 C：/mnt/ 系列（含 /mnt/usb、/mnt/media_rw —— 后者是 root-only 的原始挂载点） */
    private fun channelC(w: BufferedWriter, candidates: MutableSet<File>) {
        section(w, "【渠道 C】/mnt/ 目录扫描（含 /mnt/usb、/mnt/media_rw）")
        listOf("/mnt", "/mnt/usb", "/mnt/media_rw").forEach { path ->
            val base = File(path)
            w.line("  " + statLine(base))
            if (!base.exists()) {
                w.line("    （目录不存在）")
                return@forEach
            }
            val kids = base.listFiles()
            if (kids == null) {
                w.line("    （列出失败：无访问权限，mediarw 类挂载点通常 0700 仅 root 可见）")
                return@forEach
            }
            if (kids.isEmpty()) {
                w.line("    （空目录）")
                return@forEach
            }
            kids.sortedBy { it.name }.forEach { d ->
                w.line("    " + statLine(d))
                candidates += d
            }
        }
    }

    /** 渠道 D：getExternalFilesDirs(null) —— 零权限的 App 专属外接目录（Android/data/<包名>/files） */
    private fun channelD(
        context: Context,
        w: BufferedWriter,
        candidates: MutableSet<File>,
        volumeRoots: MutableSet<File>
    ) {
        section(w, "【渠道 D】getExternalFilesDirs(null)（App 专属目录，无需任何权限）")
        val dirs = runCatching { context.getExternalFilesDirs(null) }.getOrElse {
            w.line("  异常：调用抛出 ${describe(it)}")
            return
        }
        w.line("  返回 ${dirs.size} 个槽位：")
        dirs.forEachIndexed { i, f ->
            if (f == null) {
                w.line("  [$i] null（该槽位当前无已挂载存储）")
                return@forEachIndexed
            }
            w.line("  [$i] " + statLine(f))
            candidates += f
            // 向上 4 级：<卷根>/Android/data/<包名>/files → <卷根>
            val volRoot = f.parentFile?.parentFile?.parentFile?.parentFile
            if (volRoot != null) {
                w.line("      卷根：${volRoot.absolutePath} | ${statFields(volRoot)}")
                volumeRoots += volRoot
            }
        }
    }

    /** 渠道 E：SECONDARY_STORAGE 等环境变量（旧系统信号；为 null 不代表没有外接盘） */
    private fun channelE(w: BufferedWriter) {
        section(w, "【渠道 E】SECONDARY_STORAGE 环境变量")
        w.line("  SECONDARY_STORAGE       = ${System.getenv("SECONDARY_STORAGE") ?: "null"}")
        w.line("  EXTERNAL_STORAGE        = ${System.getenv("EXTERNAL_STORAGE") ?: "null"}")
        w.line("  EMULATED_STORAGE_TARGET = ${System.getenv("EMULATED_STORAGE_TARGET") ?: "null"}")
        w.line("  说明：SECONDARY_STORAGE 是 Android 6.0 及以下的多存储卷信号，")
        w.line("        值为 null 不代表没有外接盘（Android 7.0+ 起系统不再设置该变量）。")
    }

    /**
     * 写探针：在每个候选目录里创建 [PROBE_NAME] 再删除。
     *
     * 注意：这会对 `/sdcard` 根等沙盒之外的目录做一次"创建后立即删除"，是**本诊断功能的
     * 明确需求**（否则无法区分"路径可见"与"路径可写"），属于沙盒约定的一次性例外，
     * 探针文件是隐藏文件、写后即删，不会留下残余。
     */
    private fun writeProbe(w: BufferedWriter, candidates: Set<File>) {
        section(w, "【写探针测试】在各候选目录创建并立即删除 $PROBE_NAME")
        val dirs = candidates.filter { it.isDirectory }.sortedBy { it.absolutePath }
        if (dirs.isEmpty()) {
            w.line("  无候选目录可探测")
            return
        }
        w.line("  共探测 ${dirs.size} 个目录：")
        dirs.forEach { d ->
            w.line("  ${d.absolutePath} | ${probe(d)}")
        }
    }

    /** 沙盒落点检查：各卷根下能否建/写 `TransView/`（App 实际读写落点，直接回答"U 盘能不能用"） */
    private fun sandboxProbe(w: BufferedWriter, volumeRoots: Set<File>) {
        section(w, "【沙盒落点检查】各候选卷根下的 $SANDBOX_NAME 目录（App 的实际读写落点）")
        if (volumeRoots.isEmpty()) {
            w.line("  无候选卷根")
            return
        }
        volumeRoots.sortedBy { it.absolutePath }.forEach { root ->
            val sandbox = File(root, SANDBOX_NAME)
            val existed = sandbox.exists()
            val created = runCatching { if (existed) true else sandbox.mkdirs() }.getOrDefault(false)
            val probeResult = if (sandbox.isDirectory) probe(sandbox) else "跳过（目录不存在）"
            w.line("  ${sandbox.absolutePath}")
            w.line(
                "      目录：exists=${sandbox.exists()} canRead=${sandbox.canRead()} " +
                    "canWrite=${sandbox.canWrite()}  " +
                    (if (existed) "mkdirs=已存在" else "mkdirs=$created")
            )
            w.line("      写探针：$probeResult")
        }
    }

    /** 最终结果：App 认定的候选卷（= 设置页「存储位置」列表，即 `FileLocations.storageState.volumes`） */
    private fun finalResult(w: BufferedWriter) {
        section(w, "【最终结果】App 认定可写的存储设备（= 设置页「存储位置」列表）")
        val st = FileLocations.storageState.value
        w.line("  活动存储：${st.activeLabel}  →  ${st.activeStorage.getRootPath()}")
        w.line("  存储类型：${st.activeStorage.kindLabel}（v1.14 起恒为 File，即 java.io.File）")
        w.line("  存储状态：${st.modeLabel}")
        w.line("  首选存储：${st.preferredRoot}（${st.preferredLabel}）")
        w.line("")
        w.line("  扫描到的设备（${st.volumes.size} 个）：")
        st.volumes.forEach { v ->
            val usable = runCatching { FileUtils.formatSize(v.usableBytes) }.getOrDefault("?")
            val total = runCatching { FileUtils.formatSize(v.totalBytes) }.getOrDefault("?")
            w.line(
                "    ${v.label} | ${v.root.absolutePath} | 可用 $usable / 总量 $total | " +
                    "可移动=${v.isRemovable} 可写=${v.available}"
            )
        }
        if (st.volumes.none { it.isRemovable }) {
            w.line("")
            w.line("  未检测到任何外接存储设备（U 盘若已插上，请看渠道 B：/storage 下的候选目录与写探针结果）")
        }
    }

    /**
     * 判定说明：先说清 v1.14 的候选来源（扫 `/storage/` + 写探针），再逐条列出
     * `StorageManager` 上报的卷 —— 后者现在**只用来取盘名**，不再参与过滤；
     * 「系统有卷、但 `getDirectory()` 为 null」正是电视 ROM 的典型症状，这段可直接印证。
     */
    private fun verdict(context: Context, w: BufferedWriter) {
        section(w, "【判定说明】候选设备怎么来的 / 某块盘为什么没进列表")
        w.line("  候选规则（FileLocations.getWritableDevices，v1.14）：")
        w.line("    ① 内部存储恒在首位（Environment.getExternalStorageDirectory()）")
        w.line("    ② 扫 /storage/ 子目录，排除 emulated / self / encrypted 等系统别名")
        w.line("    ③ 对每个候选目录做**写探针**（建 + 删 .transview_write_test），通过才列入")
        w.line("    ④ 盘名优先取 StorageManager 的卷描述（按 UUID 匹配），取不到就用挂载点目录名")
        w.line("  ⚠️ 已**不再**拿 getDirectory() / canWrite() 当闸门 —— 电视 ROM 常把 U 盘挂到")
        w.line("     /mnt/media_rw/XXXX-XXXX（root-only）导致 getDirectory()=null，")
        w.line("     而 App 可见的 /storage/XXXX-XXXX 其实可读写；第 ③ 步的探针才是唯一判据。")
        w.line("")
        w.line("  以下为 StorageManager 上报的卷（仅供参考，本版不再据此过滤）：")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            w.line("    （当前 API < 24，无 StorageManager 卷接口，盘名退化为目录名）")
            return
        }
        val sm = runCatching {
            context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        }.getOrNull() ?: return
        val volumes = runCatching { sm.storageVolumes }.getOrNull() ?: return
        volumes.forEachIndexed { i, vol ->
            w.line("  [$i] uuid=${runCatching { vol.uuid }.getOrNull() ?: "null"}")
            val primary = runCatching { vol.isPrimary }.getOrDefault(false)
            val removable = runCatching { vol.isRemovable }.getOrDefault(false)
            val dir = runCatching { vol.directory }.getOrNull()
            w.line(
                "      isPrimary=$primary isRemovable=$removable " +
                    "getDirectory=${dir?.absolutePath ?: "null"}"
            )
        }
    }

    // ————————————————————— 工具 —————————————————————

    /** 章节分隔：空行 + `------------------------` + 标题 + `------------------------` */
    private fun section(w: BufferedWriter, title: String) {
        w.line()
        w.line(SEP)
        w.line(title)
        w.line(SEP)
    }

    private fun BufferedWriter.line(text: String = "") {
        write(text)
        newLine()
    }

    /** `路径 | exists=… | canRead=… | canWrite=…` */
    private fun statLine(f: File, note: String = ""): String =
        "${f.absolutePath} | ${statFields(f)}$note"

    private fun statFields(f: File): String =
        "exists=${f.exists()} | canRead=${f.canRead()} | canWrite=${f.canWrite()}"

    /** 创建并删除探针文件，返回「通过」或「失败（异常…）」 */
    private fun probe(dir: File): String = try {
        val f = File(dir, PROBE_NAME)
        FileOutputStream(f).use { it.write("transview-probe".toByteArray(Charsets.UTF_8)) }
        val len = f.length()
        val deleted = f.delete()
        when {
            len <= 0L -> "失败（open 成功但写入长度为 0）"
            !deleted -> "通过（注意：探针文件删除失败，请手动删除 $PROBE_NAME）"
            else -> "通过"
        }
    } catch (t: Throwable) {
        runCatching { File(dir, PROBE_NAME).delete() }
        "失败（${describe(t)}）" + if (isPermissionDenied(t)) "  ← 权限不足，App 无法写该路径" else ""
    }

    private fun isPermissionDenied(t: Throwable): Boolean {
        val msg = (t.message ?: "") + " " + (t.cause?.message ?: "")
        return t is SecurityException || msg.contains("EACCES") || msg.contains("Permission denied")
    }

    private fun describe(t: Throwable): String {
        val msg = t.message
        return if (msg.isNullOrBlank()) t.javaClass.name else "${t.javaClass.name}: $msg"
    }
}
