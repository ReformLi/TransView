package com.hpu.transview.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.widget.Toast
import androidx.core.content.FileProvider
import com.hpu.transview.model.Category
import com.hpu.transview.model.FileEntry
import com.hpu.transview.model.SortOrder
import com.hpu.transview.ui.settings.SettingsStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 专属沙盒目录策略：App 的全部存储收在沙盒目录（内部存储为 /sdcard/TransView，外接盘为
 * /storage/XXXX-XXXX/TransView）内，严禁读写/扫描系统公共目录（Movies/Pictures/Download 等），
 * 防误扫系统垃圾文件与越界删除。
 *
 * ## 存储卷、首选存储与活动存储（自动降级与恢复）
 * - **存储卷**（[StorageVolume]）：App 启动/插拔时动态扫描当前在位的所有可写卷（内部主卷 +
 *   外接盘）。外接盘（U盘/SD卡）经 StorageManager 取卷标（盘名），无外接盘时仅内部存储可选。
 * - **首选存储**（[SettingsStore.preferredStoragePath]）：用户在设置里从「当前在位卷」中选择的
 *   存储位置，按卷根路径持久化，默认内部存储。
 * - **活动存储**（[StorageState.activeRoot]）：当前实际用于读写的沙盒根。
 *   首选卷在位 → 活动=首选卷（正常模式）；首选是外接盘但已拔出 → 活动自动降级为内部存储
 *   （降级模式，历史记录全部保留）；外接盘插回 → 自动恢复为所选盘。
 * - 上传/解压/媒体库浏览/对账等全部读写路径都经 [sandboxRoot]（=活动存储）自动跟随切换，
 *   降级期间的上传直接写入内部存储沙盒，不中断。
 *
 * 状态变化经 [storageState] StateFlow 推送 UI；运行期降级/恢复经 [storageEvents] 通知
 * （MainScreen 收集后 Toast 提示，带盘名）。重检触发点：App 启动（TransViewApp）、外接盘插拔
 * 广播（ServerService 转发，带去抖）、设置页切换首选存储、每次对账开始前（SyncManager）。
 */
object FileLocations {

    /** 沙盒目录名（各存储卷上同构：/TransView） */
    private const val SANDBOX_DIR_NAME = "TransView"

    /** 存储卷（设置页「存储位置」的可选项；卷根是各卷挂载点，沙盒=卷根/TransView） */
    data class StorageVolume(
        /** 卷根目录：内部=/storage/emulated/0；外接=/storage/XXXX-XXXX */
        val root: File,
        /** 盘名：内部恒为「内部存储」；外接取卷标/描述（如 "SanDisk"），取不到用目录名兜底 */
        val label: String,
        /** 是否外接可移动盘（U盘/SD卡）；false=内部主卷 */
        val isRemovable: Boolean
    ) {
        /** 该卷上的沙盒根（TransView 目录；激活时由 [refresh] 自动创建） */
        val sandbox: File get() = File(root, SANDBOX_DIR_NAME)
    }

    /** 当前存储状态快照（不可变；[refresh] 时整体替换） */
    data class StorageState(
        /** 当前在位的全部存储卷（含内部主卷；设置页「存储位置」选项即此列表） */
        val volumes: List<StorageVolume>,
        /** 首选存储卷根路径（设置项持久化） */
        val preferredRoot: String,
        /** 首选存储盘名（降级提示用；首选卷不在位时仍显示最后一次选择的盘名） */
        val preferredLabel: String,
        /** 活动沙盒根（实际读写位置） */
        val activeRoot: File,
        /** 活动存储盘名（上传页状态行 / 设置页显示） */
        val activeLabel: String,
        /** 活动存储是否外接可移动盘 */
        val activeIsRemovable: Boolean
    ) {
        /** 降级模式：首选是外接盘但当前不在位，正在使用内部存储 */
        val degraded: Boolean
            get() = volumes.none { it.root.absolutePath == preferredRoot } &&
                preferredRoot != Environment.getExternalStorageDirectory().absolutePath

        /** 状态文案（设置页「当前存储状态」用） */
        val modeLabel: String
            get() = when {
                degraded -> "降级模式（${preferredLabel}已断开，正在使用内部存储）"
                activeIsRemovable -> "正常模式（${activeLabel}）"
                else -> "正常模式（内部存储）"
            }
    }

    /** 运行期存储切换事件（一次性，UI 收集后 Toast；设置页主动切换不产生事件，由设置页自行提示） */
    sealed interface StorageEvent {
        /** 外接盘拔出：活动存储已自动降级为内部存储 */
        data class UsbDetached(val label: String) : StorageEvent

        /** 外接盘插回：活动存储已自动恢复为所选盘 */
        data class UsbAttached(val label: String) : StorageEvent
    }

    /** 内部存储沙盒根：/sdcard/TransView（降级模式与默认模式的落点） */
    val internalRoot: File
        get() = File(Environment.getExternalStorageDirectory(), SANDBOX_DIR_NAME)

    @Volatile
    private var appContext: Context? = null

    /** 初始快照：按「首选=内部存储、仅内部卷」推导，首次 [refresh]/[init] 前的兜底（防御性默认） */
    @Volatile
    private var state: StorageState =
        StorageState(
            volumes = listOf(
                StorageVolume(Environment.getExternalStorageDirectory(), "内部存储", isRemovable = false)
            ),
            preferredRoot = Environment.getExternalStorageDirectory().absolutePath,
            preferredLabel = "内部存储",
            activeRoot = internalRoot,
            activeLabel = "内部存储",
            activeIsRemovable = false
        )

    @Volatile
    private var initialized = false

    private val _storageState = MutableStateFlow(state)
    val storageState: StateFlow<StorageState> = _storageState.asStateFlow()

    private val _storageEvents = MutableSharedFlow<StorageEvent>(extraBufferCapacity = 8)
    val storageEvents: SharedFlow<StorageEvent> = _storageEvents.asSharedFlow()

    /** App 启动时调用（TransViewApp.onCreate，须在 SettingsStore.init 之后）：做首次状态检测 */
    fun init(context: Context): StorageState {
        appContext = context.applicationContext
        return refresh()
    }

    /**
     * 获取当前活动媒体根目录（沙盒根）。上传落盘（UploadStorage）、解压、媒体库浏览
     * 等全部读写都基于它：首选卷在位 → <卷根>/TransView；否则（首选外接盘已拔出或默认内部）
     * 内部存储沙盒。降级模式下返回内部存储沙盒目录（自动创建），确保上传不中断。
     */
    fun getMediaRootDir(context: Context): File {
        appContext = context.applicationContext
        ensureRefreshed()
        return state.activeRoot
    }

    /**
     * 重新检测存储状态（幂等、线程安全）。触发点：App 启动、外接盘插拔广播（去抖后）、
     * 设置页切换首选存储、对账开始前。状态变化时更新 [storageState] 并按跃迁发出事件：
     * - 外接盘正常 → 降级（首选盘拔出）：[StorageEvent.UsbDetached]
     * - 降级 → 外接盘正常（首选盘插回）：[StorageEvent.UsbAttached]
     * @return 最新的状态快照
     */
    @Synchronized
    fun refresh(): StorageState {
        val context = appContext
        val volumes = if (context != null) scanVolumes(context) else emptyList()
        val preferredPath = SettingsStore.preferredStoragePath
        val storedLabel = SettingsStore.preferredStorageLabel

        // 内部卷恒存在（兜底）：正常模式 / 降级模式的活动落点
        val internalVolume = volumes.firstOrNull { !it.isRemovable }
            ?: StorageVolume(Environment.getExternalStorageDirectory(), "内部存储", isRemovable = false)
        // 首选卷在位 → 活动=首选卷；否则（外接盘已拔出 / 无此卷）→ 内部
        val preferredVolume = volumes.firstOrNull { it.root.absolutePath == preferredPath }
        val activeVolume = preferredVolume ?: internalVolume
        val activeRoot = activeVolume.sandbox
        // 活动沙盒必须存在且可用：降级进入内部存储 / 首次选中外接盘时自动创建沙盒目录
        runCatching { activeRoot.mkdirs() }

        val preferredLabel = preferredVolume?.label ?: storedLabel
        val newState = StorageState(
            volumes = volumes,
            preferredRoot = preferredPath,
            preferredLabel = preferredLabel,
            activeRoot = activeRoot,
            activeLabel = activeVolume.label,
            activeIsRemovable = activeVolume.isRemovable
        )

        val previous = state
        state = newState
        initialized = true
        _storageState.value = newState

        // 仅「运行期」的降级/恢复才发事件（首次检测、设置页主动切换不发，避免开机误弹 Toast）
        if (previous.activeIsRemovable && newState.degraded) {
            _storageEvents.tryEmit(StorageEvent.UsbDetached(previous.activeLabel))
        } else if (previous.degraded && newState.activeIsRemovable) {
            _storageEvents.tryEmit(StorageEvent.UsbAttached(newState.activeLabel))
        }
        return newState
    }

    /** 首次访问前兜底刷新（正常时序下 TransViewApp.onCreate 已 init 过，这里不会命中） */
    private fun ensureRefreshed() {
        if (!initialized) refresh()
    }

    /**
     * 扫描当前在位的所有存储卷（含内部主卷），返回列表即设置页「存储位置」的全部可选项：
     * - 内部主卷：label 恒为「内部存储」；
     * - 外接盘（U盘/SD卡）：API 24+ 经 StorageManager 取卷标/描述（盘名），仅保留已挂载可写的卷；
     *   API 21-23 降级扫描 /storage 目录（排除 emulated/self 系统别名），盘名用卷目录名兜底。
     */
    private fun scanVolumes(context: Context): List<StorageVolume> {
        val out = LinkedHashMap<String, StorageVolume>()
        val internal = Environment.getExternalStorageDirectory()
        out[internal.absolutePath] = StorageVolume(internal, "内部存储", isRemovable = false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            sm.storageVolumes.forEach { vol ->
                if (vol.isPrimary || !vol.isRemovable) return@forEach
                val dir = vol.directory ?: return@forEach
                if (!dir.isDirectory || !dir.canWrite()) return@forEach
                val label = vol.getDescription(context).takeIf { it.isNotBlank() } ?: dir.name
                out[dir.absolutePath] = StorageVolume(dir, label, isRemovable = true)
            }
        } else {
            File("/storage").listFiles()?.forEach { dir ->
                if (!dir.isDirectory) return@forEach
                if (dir.name == "emulated" || dir.name == "self") return@forEach
                if (!dir.canWrite()) return@forEach
                out[dir.absolutePath] = StorageVolume(dir, dir.name, isRemovable = true)
            }
        }
        return out.values.toList()
    }

    /** 活动沙盒根：/sdcard/TransView 或 /storage/XXXX-XXXX/TransView（所有读写的统一入口） */
    val sandboxRoot: File
        get() = state.activeRoot

    /** 各分类根目录（均在活动沙盒内），首次访问自动创建 */
    fun root(category: Category): File =
        File(
            sandboxRoot,
            when (category) {
                Category.VIDEO -> "Movies"
                Category.IMAGE -> "Pictures"
                Category.OTHER -> "Downloads"
            }
        ).apply { mkdirs() }

    /**
     * 压缩包解压工作区：<活动沙盒>/.temp_unzip
     *
     * 上传的 .zip 先落到这里解压，命中分类的文件再搬进分类目录；无论成功失败都会整目录清理。
     * 目录名以 `.` 开头 → 媒体扫描（listMediaFilesRecursively / listEntries）默认跳过隐藏项，
     * 解压中途不会污染媒体库；SyncManager 每次对账还会兜底物理清空（防断电后残留）。
     * 跟随活动存储：降级期间解压的临时目录在内部存储，对账时两个沙盒的临时目录都会清（见
     * [allTempUnzipDirs]）。
     */
    val tempUnzipDir: File
        get() = File(sandboxRoot, ".temp_unzip")

    /** 全部在位卷的沙盒解压临时目录，供对账兜底清理（谁在位清谁） */
    fun allTempUnzipDirs(): List<File> = sandboxRoots().map { File(it, ".temp_unzip") }

    /** 三个分类根目录（对账/清理范围仅限活动沙盒内） */
    fun allRoots(): List<File> = Category.entries.map { root(it) }

    /** 判断某目录是否是分类根目录本身（清理空文件夹时不得删除根目录） */
    fun isRoot(dir: File): Boolean =
        allRoots().any { it.absolutePath == dir.absolutePath }

    /**
     * 路径安全校验：删除/清理等破坏性操作前必须调用。
     * 规范化路径必须位于**任一已知沙盒**（内部存储或U盘的 TransView）之内，
     * 防止越界误删系统文件。多沙盒判定的原因：降级与恢复会让活动沙盒来回切换，
     * 只认活动沙盒会拒绝「切到另一块沙盒上仍合法」的清理请求（如对账清理上一模式的
     * 解压残留），只认固定目录则会在U盘模式漏掉U盘自身的保护。
     */
    fun isInsideSandbox(file: File): Boolean = runCatching {
        val path = file.canonicalPath
        sandboxRoots().any { root -> path.startsWith(root.canonicalPath + File.separator) }
    }.getOrDefault(false)

    /** 全部已知沙盒根：当前在位各卷的 TransView（含内部存储；去重后） */
    private fun sandboxRoots(): List<File> =
        state.volumes.map { it.sandbox }.distinctBy { it.absolutePath }
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
     * 清空一个目录下的全部内容（递归），目录本身保留。
     * 用于压缩包解压工作区（`/sdcard/TransView/.temp_unzip`）的残留清理：
     * 解压中途断电 / 进程被杀会留下半个工作目录，SyncManager 每次对账兜底清掉。
     * 越界保护：目标必须在 /sdcard/TransView 沙盒内，否则拒绝执行。
     * @param skipActiveWithinMs 大于 0 时，目录树内最近该毫秒数内仍有写入的子目录视为
     *   「可能正在使用」（如仍在进行的解压工作区），跳过不删——手动对账可能恰好撞上
     *   在途解压，现在清掉会把整包文件连同原压缩包一起误删。
     * @return 实际删除的顶层条目数
     */
    fun purgeDirectory(dir: File, skipActiveWithinMs: Long = 0L): Int {
        if (!FileLocations.isInsideSandbox(dir)) return 0
        val children = dir.listFiles() ?: return 0
        val activeCutoff = System.currentTimeMillis() - skipActiveWithinMs
        var removed = 0
        for (child in children) {
            if (skipActiveWithinMs > 0 && newestModifiedUnder(child) >= activeCutoff) continue
            runCatching {
                if (child.isDirectory) child.deleteRecursively() else child.delete()
            }
            if (!child.exists()) removed++
        }
        return removed
    }

    /** 目录树内最新的修改时间（含自身；普通文件即自身 mtime），供在途工作区判定 */
    private fun newestModifiedUnder(file: File): Long {
        var newest = file.lastModified()
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                val t = newestModifiedUnder(child)
                if (t > newest) newest = t
            }
        }
        return newest
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
