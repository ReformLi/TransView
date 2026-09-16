package com.hpu.transview.util

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.widget.Toast
import androidx.core.content.FileProvider
import com.hpu.transview.model.Category
import com.hpu.transview.model.FileEntry
import com.hpu.transview.model.MediaRef
import com.hpu.transview.model.SortOrder
import com.hpu.transview.storage.FileStorage
import com.hpu.transview.storage.IStorage
import com.hpu.transview.storage.StorageFile
import com.hpu.transview.ui.settings.SettingsStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileFilter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 专属沙盒目录策略：App 的全部存储收在沙盒目录 `TransView/` 内
 * （内部存储 `/storage/emulated/0/TransView`、U 盘/SD 卡 `/storage/XXXX-XXXX/TransView`），
 * 严禁读写/扫描系统公共目录，防误扫系统垃圾与越界删除。
 *
 * ## 存储形态（v1.14：纯 `java.io.File`，已彻底移除 SAF）
 * 上层（上传落盘、媒体库、对账、播放）统一经 [activeStorage]（[FileStorage]）读写，
 * 不再有任何 `ContentResolver` / `content://` 形态 —— 播放与图片加载拿到的都是 `file://` URI。
 *
 * ## U 盘的发现方式（v1.14 重写，[getWritableDevices]）
 * **既不迷信单一 API，也不能靠列目录**：电视 ROM 常把 U 盘挂在 `/mnt/media_rw/XXXX-XXXX`，
 * `StorageVolume.getDirectory()` 返回 null（`StorageManager` 根本不上报可写路径）；
 * 而 `/storage` 的**目录项**在 Android 11+ 对第三方应用一律不可读——即便已获
 * `MANAGE_EXTERNAL_STORAGE`，`File("/storage").listFiles()` 仍恒为 null（API 36 实测），
 * 但**已知路径照样可读写**。因此改为**多来源枚举「候选卷根」**：
 * StorageManager 的 `getDirectory()` 与 uuid 推导路径、`getExternalFilesDirs` 反推卷根、
 * `/proc/mounts` 挂载点；再对每个候选做**写探针**（建 + 删 `.transview_write_test`），
 * 探针通过才认作可写设备并列出。
 * 该路径依赖 `MANAGE_EXTERNAL_STORAGE`（App 首启已引导用户开启「允许管理所有文件」）。
 *
 * ## 首选存储与活动存储（自动降级与恢复）
 * - **存储卷**（[StorageVolume]）：每次 [refresh] 实时扫描出的可写设备（内部存储恒在首位）。
 * - **首选存储**（`SettingsStore.preferredStoragePath`）：用户选定的卷根绝对路径。
 * - **活动存储**（[StorageState.activeStorage]）：当前实际用于读写的存储。
 *   首选卷可用 → 活动 = 首选卷；首选卷**已拔出** → 自动降级为**内部存储**（降级模式，
 *   数据库记录全部保留）；插回 → 自动恢复。
 * - 降级期间的上传直接写入内部存储沙盒，不中断；媒体库按活动存储过滤展示，另一块存储的记录
 *   仍保留在库中，切回即恢复。
 *
 * 状态变化经 [storageState] StateFlow 推送 UI；运行期降级/恢复经 [storageEvents] 通知
 * （MainScreen 收集后 Toast 提示，带盘名）。重检触发点：App 启动（TransViewApp）、外接盘插拔
 * 广播（ServerService 转发，带去抖）、设置页切换首选存储、每次对账开始前（SyncManager）。
 */
object FileLocations {

    /** 沙盒目录名（各存储卷上同构：`/TransView`） */
    private const val SANDBOX_DIR_NAME = "TransView"

    /** 写探针文件名（建完立即删除；沙盒外的唯一一次例外写入，用于判定"能不能写"） */
    const val WRITE_PROBE_NAME = ".transview_write_test"

    private const val TAG = "StorageDebug"

    /** `/storage/` 下不参与扫描的系统目录/别名 */
    private val SYSTEM_STORAGE_DIRS = setOf("emulated", "self", "encrypted", "sdcard0", "runtime")

    /**
     * 存储设备（设置页「存储位置」的可选项）。
     *
     * 统一用卷根目录 [root] 标识，[id] = 卷根绝对路径（持久化与比较都用它）。
     */
    data class StorageVolume(
        /** 盘名：内部恒为「内部存储」；外接优先取系统卷标描述，取不到用挂载点目录名（如 `0000-0000`） */
        val label: String,
        /** 是否外接可移动盘（U盘/SD卡）；false=内部主卷 */
        val isRemovable: Boolean,
        /** 卷根目录（内部=`/storage/emulated/0`，外接=`/storage/XXXX-XXXX`） */
        val root: File,
        /** 当前是否可用（写探针通过）；false 时仅作展示，不可切换 */
        val available: Boolean = true
    ) {
        /** 卷的唯一身份（持久化与比较都用它）= 卷根绝对路径 */
        val id: String get() = root.absolutePath

        /** 该卷上的沙盒根（`TransView` 目录） */
        val sandbox: File get() = File(root, SANDBOX_DIR_NAME)

        /** 展示用路径（设置页副标题 / 信息卡片） */
        val displayPath: String get() = root.absolutePath

        /** 总空间（字节）；取不到返回 0 */
        val totalBytes: Long get() = runCatching { root.totalSpace }.getOrDefault(0L)

        /** 可用空间（字节）；取不到返回 0 */
        val usableBytes: Long get() = runCatching { root.usableSpace }.getOrDefault(0L)
    }

    /** 当前存储状态快照（不可变；[refresh] 时整体替换） */
    data class StorageState(
        /** 当前在位的全部存储卷（含内部主卷；设置页「存储位置」选项即此列表） */
        val volumes: List<StorageVolume>,
        /** 首选存储卷 ID（设置项持久化）= 卷根绝对路径 */
        val preferredRoot: String,
        /** 首选存储盘名（降级提示用；首选卷不在位时仍显示最后一次选择的盘名） */
        val preferredLabel: String,
        /** 首选卷当前是否可用（不可用即降级模式） */
        val preferredAvailable: Boolean,
        /** **活动存储**：所有读写的统一入口 */
        val activeStorage: IStorage,
        /** 活动存储盘名（上传页状态行 / 设置页显示） */
        val activeLabel: String,
        /** 活动存储是否外接可移动盘 */
        val activeIsRemovable: Boolean,
        /** 活动存储的沙盒根（解压工作区/上传落点也用它的父卷） */
        val activeRoot: File,
        /** 活动存储的身份串（可安全作 remember key）= 沙盒根绝对路径 */
        val activeKey: String
    ) {
        /** 降级模式：首选是外接盘但当前不可用（已拔出），正在使用内部存储 */
        val degraded: Boolean
            get() = !preferredAvailable &&
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

    /** 内部存储沙盒根：`/sdcard/TransView`（降级模式与默认模式的落点） */
    val internalRoot: File
        get() = File(Environment.getExternalStorageDirectory(), SANDBOX_DIR_NAME)

    @Volatile
    private var appContext: Context? = null

    /** 初始快照：按「首选=内部存储、仅内部卷」推导，首次 [refresh]/[init] 前的兜底（防御性默认） */
    @Volatile
    private var state: StorageState = buildInitialState()

    @Volatile
    private var initialized = false

    private val _storageState = MutableStateFlow(state)
    val storageState: StateFlow<StorageState> = _storageState.asStateFlow()

    private val _storageEvents = MutableSharedFlow<StorageEvent>(extraBufferCapacity = 8)
    val storageEvents: SharedFlow<StorageEvent> = _storageEvents.asSharedFlow()

    private fun buildInitialState(): StorageState {
        val internal = Environment.getExternalStorageDirectory()
        val root = File(internal, SANDBOX_DIR_NAME)
        return StorageState(
            volumes = listOf(StorageVolume("内部存储", isRemovable = false, root = internal)),
            preferredRoot = internal.absolutePath,
            preferredLabel = "内部存储",
            preferredAvailable = true,
            activeStorage = FileStorage(root),
            activeLabel = "内部存储",
            activeIsRemovable = false,
            activeRoot = root,
            activeKey = root.absolutePath
        )
    }

    /** App 启动时调用（TransViewApp.onCreate，须在 SettingsStore.init 之后）：做首次状态检测 */
    fun init(context: Context): StorageState {
        appContext = context.applicationContext
        return refresh()
    }

    /**
     * 当前活动存储（读写统一入口）。调用方应尽量持有一次并在本次操作内复用
     * （[refresh] 切换存储时会换实例，如 U 盘拔出触发降级时）。
     */
    val activeStorage: IStorage
        get() = state.activeStorage

    /** 带 Context 兜底的取用（首次访问前若未 init，先补一次检测） */
    fun activeStorageOf(context: Context): IStorage {
        appContext = context.applicationContext
        ensureRefreshed()
        return state.activeStorage
    }

    /** 便捷：分类目录的**相对路径**（相对沙盒根），如 VIDEO → `Movies` */
    fun categoryRelative(category: Category): String = when (category) {
        Category.VIDEO -> "Movies"
        Category.IMAGE -> "Pictures"
        Category.OTHER -> "Downloads"
    }

    /** 便捷：分类目录的**节点身份**（即绝对路径） */
    fun categoryNode(category: Category): String = state.activeStorage.nodeFor(categoryRelative(category))

    /**
     * 判断某个存储身份（绝对路径）当前是否真实存在。
     *
     * 语义上必须区分「文件被删了」与「存储当前看不见」，否则「同步外部删除」会误删索引：
     * 只有该路径所属的卷**当前可用**时才做真实探测；卷不可见（U 盘拔出）时**视为存在**，
     * 记录全部保留，插回后自动恢复。
     *
     * `content://` 路径一律返回 false —— 那是 v1.12/v1.13 SAF 时代写入的历史记录，
     * 本版已彻底移除 SAF，这类记录不可达（由 SyncManager 在启动对账时清理）。
     */
    fun existsForPath(path: String): Boolean {
        if (path.startsWith("content://")) return false
        val file = runCatching { File(path) }.getOrNull() ?: return true
        val insideAvailableVolume = state.volumes.any { vol ->
            vol.available && runCatching {
                path == vol.root.absolutePath || path.startsWith(vol.root.absolutePath + File.separator)
            }.getOrDefault(false)
        }
        if (!insideAvailableVolume) return true
        return runCatching { file.exists() }.getOrDefault(false)
    }

    /** 该存储身份是否属于本 App 管理的沙盒（防止对账/播放器兜底误删别人的记录） */
    fun isManagedPath(path: String): Boolean =
        !path.startsWith("content://") &&
            runCatching { isInsideSandbox(File(path)) }.getOrDefault(false)

    /** 存储身份 → 可直接交给 Coil / ExoPlayer / Intent 的 Uri */
    fun uriOf(path: String): Uri = mediaUri(path)

    /** 同一目录下的兄弟条目（播放器的「同目录连播」、图片查看器的「同目录切图」用） */
    fun siblings(nodePath: String): List<StorageFile> {
        val parent = parentNodeOf(nodePath) ?: return emptyList()
        return state.activeStorage.listChildren(parent)
    }

    /** 父节点身份；找不到时返回 null */
    fun parentNodeOf(nodePath: String): String? = state.activeStorage.parentNode(nodePath)

    /**
     * 获取当前活动媒体根目录（沙盒根）。**File 模式专用**：上传落盘（UploadStorage）、
     * 解压工作区等 File 侧逻辑用它确定本地落点；节点级读写请用 [activeStorage]。
     */
    fun getMediaRootDir(context: Context): File {
        appContext = context.applicationContext
        ensureRefreshed()
        return state.activeRoot
    }

    /**
     * 重新检测存储状态（幂等、线程安全）。触发点：App 启动、外接盘插拔广播（去抖后）、
     * 设置页切换首选存储、对账开始前。
     */
    @Synchronized
    fun refresh(): StorageState {
        val context = appContext
        val devices = if (context != null) getWritableDevices(context) else emptyList()
        val preferredId = SettingsStore.preferredStoragePath
        val storedLabel = SettingsStore.preferredStorageLabel

        // 内部卷恒存在（兜底）：正常模式 / 降级模式的活动落点
        val internalVolume = devices.firstOrNull { !it.isRemovable }
            ?: StorageVolume("内部存储", isRemovable = false, root = Environment.getExternalStorageDirectory())

        // 列表 = 扫描出的可写设备 + 「首选但当前不在位」的展示占位项。
        // 后者 available=false（不可切换），只为让用户看到「我选的盘为什么不见了」，
        // 也是「降级模式」在设置页里的可视证据。
        val volumes = buildList {
            addAll(devices)
            if (preferredId.isNotBlank() &&
                devices.none { it.id == preferredId } &&
                preferredId != internalVolume.root.absolutePath
            ) {
                val lost = File(preferredId)
                add(
                    StorageVolume(
                        label = storedLabel.ifBlank { lost.name },
                        isRemovable = true,
                        root = lost,
                        available = false
                    )
                )
            }
        }

        val preferredVolume = volumes.firstOrNull { it.id == preferredId }
        val preferredAvailable = preferredVolume?.available == true
        // 首选不可用（拔出）→ 活动存储自动降级为内部存储（记录全部保留，插回自动恢复）
        val activeVolume = preferredVolume?.takeIf { it.available } ?: internalVolume

        // 活动沙盒必须存在：降级进入内部存储 / 首次选中外接盘时自动创建
        val activeStorage = FileStorage(activeVolume.sandbox)
        runCatching { activeStorage.createDirectory("") }

        val newState = StorageState(
            volumes = volumes,
            preferredRoot = preferredId,
            preferredLabel = preferredVolume?.label ?: storedLabel,
            preferredAvailable = preferredAvailable,
            activeStorage = activeStorage,
            activeLabel = activeVolume.label,
            activeIsRemovable = activeVolume.isRemovable,
            activeRoot = activeStorage.resolve(""),
            activeKey = activeStorage.getRootPath()
        )

        val previous = state
        state = newState
        initialized = true
        _storageState.value = newState

        // 仅「运行期」的降级/恢复才发事件（首次检测、设置页主动切换不发，避免开机误弹 Toast）
        if (previous.activeIsRemovable && newState.degraded) {
            _storageEvents.tryEmit(StorageEvent.UsbDetached(previous.activeLabel))
        } else if (previous.degraded && newState.activeIsRemovable && !newState.degraded) {
            _storageEvents.tryEmit(StorageEvent.UsbAttached(newState.activeLabel))
        }
        return newState
    }

    /** 首次访问前兜底刷新（正常时序下 TransViewApp.onCreate 已 init 过，这里不会命中） */
    private fun ensureRefreshed() {
        if (!initialized) refresh()
    }

    /**
     * 扫描当前**可写**的存储设备（设置页「存储位置」的列表来源）。
     *
     * ## 为什么不能直接扫 `/storage/`（v1.14 实测结论）
     * Android 11+ 起第三方应用**读不到 `/storage` 的目录项**——即便已获 `MANAGE_EXTERNAL_STORAGE`，
     * `File("/storage").listFiles()` 仍恒返回 `null`（模拟器 API 36 实测 `扫描 /storage：-1 项 [读取失败]`）。
     * 但**已知路径照样可读写**（Vidda 上第三方文件管理器能读写 `/storage/0000-0000` 正是这个道理）。
     * 所以策略改为：**先多来源枚举「候选卷根路径」，再逐个做写探针**。
     *
     * ## 候选来源（四路合并、路径去重；任一路失败不影响其它）
     * 1. `StorageManager.storageVolumes` 的 `getDirectory()`（API 30+；**电视 ROM 常返回 null**）；
     * 2. 由卷 `uuid` 拼出的 `/storage/<uuid>` 与 `/mnt/media_rw/<uuid>`——
     *    `getDirectory()` 为 null 时的兜底，Vidda 正是「不上报目录、但路径可读写」；
     * 3. `Context.getExternalFilesDirs(null)` 去掉 `/Android/data/<pkg>/files` 后缀反推卷根
     *    （**不需要任何权限**，且能发现系统承认的每一块可移动卷——最稳的一路）；
     * 4. `/proc/mounts`（退化读 `/proc/self/mounts`）里 `/mnt/media_rw/<id>`、`/storage/<id>`
     *    挂载点——挂载表是 ROM 唯一藏不掉的证据。
     *
     * 之后对每个候选做**写探针**（[probeWritable]，卷根建 `.transview_write_test` 后立即删除）：
     * 这是唯一可靠的判据 —— `File.canWrite()` 在部分 ROM 上对可移动卷恒返回 false 而实际能写，
     * 反向的「`listFiles()` 非空」也不能证明可写。**内部存储恒为第一项**。
     *
     * 盘名：能取到系统卷描述就用「描述 (卷ID)」，否则用「U盘 (卷ID)」（如 `U盘 (0000-0000)`）。
     */
    fun getWritableDevices(context: Context): List<StorageVolume> {
        val internal = Environment.getExternalStorageDirectory()
        val out = LinkedHashMap<String, StorageVolume>()
        out[internal.absolutePath] = StorageVolume("内部存储", isRemovable = false, root = internal)

        // 卷 UUID → 系统描述（仅用于显示盘名）
        val sm = runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) null
            else context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        }.getOrNull()
        val described = LinkedHashMap<String, String>()
        val volumes = runCatching { sm?.storageVolumes }.getOrNull().orEmpty()
        for (vol in volumes) {
            val uuid = runCatching { vol.uuid }.getOrNull() ?: continue
            val desc = runCatching { vol.getDescription(context) }.getOrNull()
            if (!desc.isNullOrBlank()) described[uuid] = desc
        }

        // ——— 候选卷根：路径 → 盘名提示（有序去重；内部存储不入候选）———
        val candidates = LinkedHashMap<String, String?>()
        fun add(path: String?, hint: String? = null) {
            val p = path?.trim()?.trimEnd('/') ?: return
            if (p.isEmpty() || p == internal.absolutePath) return
            if (p.substringAfterLast('/').lowercase() in SYSTEM_STORAGE_DIRS) return
            if (!candidates.containsKey(p)) candidates[p] = hint
        }

        // 来源 1 / 2：StorageManager 的目录与 uuid 推导路径
        for (vol in volumes) {
            val removable = runCatching { vol.isRemovable }.getOrDefault(true)
            if (!removable) continue
            val uuid = runCatching { vol.uuid }.getOrNull()
            val dir = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) vol.directory else null
            }.getOrNull()
            if (dir != null) add(dir.absolutePath, described[uuid])
            if (!uuid.isNullOrBlank()) {
                add("/storage/$uuid", described[uuid])
                add("/mnt/media_rw/$uuid", described[uuid])
            }
        }

        // 来源 3：getExternalFilesDirs → 反推卷根（无需权限，最可靠）
        runCatching { context.getExternalFilesDirs(null) }.getOrNull().orEmpty().forEach { dir ->
            var cur: File? = dir
            while (cur != null && cur.name != "Android") cur = cur.parentFile
            add(cur?.parentFile?.absolutePath)
        }

        // 来源 4：/proc/mounts（挂载点是 ROM 藏不掉的证据）
        for (line in readMounts()) {
            val parts = line.split(' ')
            if (parts.size < 3) continue
            val mountPoint = parts[1]
            if (!mountPoint.startsWith("/mnt/media_rw/") && !mountPoint.startsWith("/storage/")) continue
            val id = mountPoint.trimEnd('/').substringAfterLast('/')
            if (id.isBlank()) continue
            // 优先登记 App 可见的 /storage/<id>（FUSE 视图），原始挂载点随后兜底
            add("/storage/$id", described[id])
            add(mountPoint, described[id])
        }

        AppLogger.d(TAG, "候选卷根：${candidates.keys.joinToString()}")

        // ——— 逐个写探针：通过才算「可写设备」 ———
        for ((path, hint) in candidates) {
            val dir = File(path)
            if (!dir.isDirectory) {
                AppLogger.d(TAG, "候选 $path 跳过：不是目录")
                continue
            }
            val writable = probeWritable(dir)
            AppLogger.d(TAG, "候选 $path：canRead=${dir.canRead()} canWrite=${dir.canWrite()} " +
                "listFiles=${runCatching { dir.listFiles()?.size }.getOrNull()} 写探针=$writable")
            if (!writable) continue
            val label = if (hint.isNullOrBlank()) "U盘 (${dir.name})" else "$hint (${dir.name})"
            out[dir.absolutePath] = StorageVolume(label, isRemovable = true, root = dir)
        }
        AppLogger.d(TAG, "可写设备扫描结果：${out.values.joinToString { "${it.label}(${it.id})" }}")
        return out.values.toList()
    }

    /** 读挂载表：优先 `/proc/mounts`，退化 `/proc/self/mounts`；失败返回空表 */
    private fun readMounts(): List<String> {
        for (path in listOf("/proc/mounts", "/proc/self/mounts")) {
            val lines = runCatching { File(path).readLines() }.getOrNull()
            if (!lines.isNullOrEmpty()) return lines
        }
        return emptyList()
    }

    /**
     * 写探针：在 [dir] 下建一个临时文件再删掉，返回是否成功。
     *
     * 这是判断「这块盘能不能写」唯一可靠的手段 —— `File.canWrite()` 在部分 ROM 上对可移动卷
     * 恒为 false（传统 `WRITE_EXTERNAL_STORAGE` 不覆盖可移动卷根），实际却能写；
     * 反向的「只读到 `listFiles()` 非空」也不能证明可写。
     * 探针会在卷根留下一瞬的文件（建后即删），属沙盒外的一次性例外。
     */
    fun probeWritable(dir: File): Boolean = runCatching {
        if (!dir.isDirectory) return@runCatching false
        val probe = File(dir, WRITE_PROBE_NAME)
        val created = probe.createNewFile()
        val ok = created || probe.isFile
        probe.delete()
        ok
    }.getOrDefault(false)

    /** 活动沙盒根（全部存储形态统一为 File） */
    val sandboxRoot: File
        get() = state.activeRoot

    /**
     * 各分类根目录（本地路径）。**上传/媒体库请改用 [activeStorage]**；
     * 这里仅服务于「必须落到本地 File」的场景（存储权限写探针、解压工作区）。
     */
    fun root(category: Category): File =
        File(sandboxRoot, categoryRelative(category)).apply { mkdirs() }

    /**
     * 压缩包解压工作区：`<活动沙盒>/.temp_unzip`。
     *
     * 上传的 .zip 先落到这里解压，命中分类的文件再搬进分类目录；无论成功失败都会整目录清理。
     * 目录名以 `.` 开头 → 媒体扫描默认跳过隐藏项，解压中途不会污染媒体库；
     * SyncManager 每次对账还会兜底物理清空（防断电后残留）。
     */
    val tempUnzipDir: File
        get() = File(state.activeRoot, ".temp_unzip")

    /** 全部卷的沙盒解压临时目录，供对账兜底清理 */
    fun allTempUnzipDirs(): List<File> =
        sandboxRoots().map { File(it, ".temp_unzip") }

    /** 三个分类根目录的本地 File（对账/清理范围仅限本地沙盒） */
    fun allRoots(): List<File> = Category.entries.map { root(it) }

    /** 判断某目录是否是分类根目录本身（清理空文件夹时不得删除根目录） */
    fun isRoot(dir: File): Boolean =
        allRoots().any { it.absolutePath == dir.absolutePath }

    /**
     * 路径安全校验：删除/清理等破坏性操作前必须调用。
     * 规范化路径必须位于**任一已知沙盒**（内部存储或外接盘上的 `TransView/`）之内，
     * 防止越界误删系统文件。
     */
    fun isInsideSandbox(file: File): Boolean = runCatching {
        val path = file.canonicalPath
        sandboxRoots().any { root -> path.startsWith(root.canonicalPath + File.separator) }
    }.getOrDefault(false)

    /** 全部已知沙盒根：内部存储 + 当前在位的可写卷（去重后） */
    private fun sandboxRoots(): List<File> {
        val roots = ArrayList<File>()
        roots += internalRoot
        state.volumes.forEach { vol -> roots += vol.sandbox }
        return roots.distinctBy { it.absolutePath }
    }
}

val VIDEO_EXTS = setOf(
    "mp4", "mkv", "avi", "mov", "flv", "wmv", "m4v", "ts", "webm", "3gp", "mpg", "mpeg", "rmvb", "rm"
)
val IMAGE_EXTS = setOf(
    "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "tiff"
)

fun File.isVideoFile(): Boolean = extension.lowercase() in VIDEO_EXTS
fun File.isImageFile(): Boolean = extension.lowercase() in IMAGE_EXTS

/** 按文件名判类型（与 [File.isVideoFile] 同规则，供只有名字没有 File 的场景复用） */
fun nameIsVideoFile(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in VIDEO_EXTS

fun nameIsImageFile(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in IMAGE_EXTS

fun FileEntry.isVideoFile(): Boolean = nameIsVideoFile(name)
fun FileEntry.isImageFile(): Boolean = nameIsImageFile(name)

/**
 * **方案 A：目录即分类** —— 某文件是否符合某分类目录的格式要求。
 *
 * 视频目录只收视频、图片目录只收图片、其他目录只收「非视频且非图片」。
 * 判定统一走已有的扩展名工具（[isVideoFile] / [isImageFile]），**不在调用方另写一套**。
 * 不符合的文件一律「不入库、不展示」，但**绝不物理删除**。
 */
fun isValidFormatForCategory(file: File, category: Category): Boolean = when (category) {
    Category.VIDEO -> file.isVideoFile()
    Category.IMAGE -> file.isImageFile()
    Category.OTHER -> !file.isVideoFile() && !file.isImageFile()
}

/** [isValidFormatForCategory] 的名字版本（只有文件名、没有 File 的场景复用） */
fun isValidFormatForCategory(name: String, category: Category): Boolean = when (category) {
    Category.VIDEO -> nameIsVideoFile(name)
    Category.IMAGE -> nameIsImageFile(name)
    Category.OTHER -> !nameIsVideoFile(name) && !nameIsImageFile(name)
}

/** 媒体库卡片 → 可直接交给 Coil 的 Uri（`file://`） */
fun FileEntry.toUri(): Uri = mediaUri(path)

/** 播放/看图列表项 → 可直接交给 Coil / ExoPlayer 的 Uri */
fun MediaRef.toUri(): Uri = mediaUri(path)

/**
 * 存储身份（绝对路径）→ 可交给 Coil、ExoPlayer、MediaMetadataRetriever、Intent 的 Uri。
 *
 * v1.14 起存储只有一种形态（`java.io.File`），所以恒为 `file:///storage/…`——
 * 这是 ExoPlayer 原生支持且开销最小的形式（不走 `ContentResolver`，无 IPC）。
 */
fun mediaUri(path: String): Uri = Uri.fromFile(File(path))

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

/** 按文件名猜测 MIME 类型 */
fun mimeTypeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
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
    "mp4" -> "video/mp4"
    "mkv" -> "video/x-matroska"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    else -> "application/octet-stream"
}

object FileUtils {

    // ————— 文件树遍历 —————

    /**
     * 递归遍历媒体根目录（File），返回所有非隐藏文件。
     * 仅用于 File 侧场景（如本地工作区）；活动存储的遍历请用 [IStorage.listFiles]。
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

    /**
     * 按分类扫描活动沙盒的某个分类目录（**方案 A：目录即分类 + 格式严格过滤**）。
     *
     * 用 `File.listFiles(FileFilter)` 在**列目录阶段**就完成过滤，filter = 「目录 或 本分类合法格式」：
     * 内存里只会出现「有效文件 + 全部文件夹」，十万个无关文件也不会被构造成数组
     * （旧实现是先 `listFiles()` 拿全部再逐个判断，大目录会顶爆内存）。
     *
     * 递归进入子目录，对每一层都执行同一过滤；[onDir] 每进入一个目录回调一次（进度提示用）。
     * 单个目录读取失败（权限 / 拔盘 / ROM 限制）→ 跳过该目录、**继续扫描其余目录**，不抛出。
     *
     * 隐藏项（`.` 开头，含 `.temp_unzip`）一律跳过；**绝不删除任何物理文件**。
     *
     * @return 该分类目录下的全部合法文件（未排序）
     */
    fun scanCategoryFiles(
        category: Category,
        onDir: ((dir: File, validCount: Int) -> Unit)? = null
    ): List<StorageFile> {
        val sandboxRoot = FileLocations.sandboxRoot
        val root = File(sandboxRoot, FileLocations.categoryRelative(category))
        val out = ArrayList<StorageFile>()
        if (!root.isDirectory) return out
        val prefix = sandboxRoot.absolutePath + File.separator
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = runCatching { dir.listFiles(categoryFilter(category)) }.getOrNull() ?: continue
            for (child in children) {
                if (child.isDirectory) stack.addLast(child) else out.add(toStorageFile(child, prefix))
            }
            onDir?.invoke(dir, out.size)
        }
        return out
    }

    /**
     * 该目录（递归）下是否存在至少一个本分类合法文件。
     *
     * 媒体库据此隐藏「全是无效格式」的文件夹卡片（如 `Movies/某某/` 里全是 .txt）：
     * 物理文件夹**保留不删**，只是不生成卡片（没有有效文件可展示）。
     */
    fun hasValidContentIn(dir: File, category: Category): Boolean {
        if (!dir.isDirectory) return false
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val children = runCatching { d.listFiles(categoryFilter(category)) }.getOrNull() ?: continue
            for (child in children) {
                if (child.isDirectory) stack.addLast(child) else return true
            }
        }
        return false
    }

    /** 方案 A 的列目录过滤器：跳过隐藏项，保留「全部目录 + 本分类合法文件」 */
    private fun categoryFilter(category: Category): FileFilter = FileFilter { f ->
        !f.name.startsWith(".") && (f.isDirectory || isValidFormatForCategory(f, category))
    }

    /** File → StorageFile（absolutePath 为身份；relativePath 去掉沙盒根前缀） */
    private fun toStorageFile(file: File, sandboxRootPrefix: String): StorageFile = StorageFile(
        name = file.name,
        path = file.absolutePath,
        relativePath = file.absolutePath.removePrefix(sandboxRootPrefix),
        parentPath = file.parentFile?.absolutePath.orEmpty(),
        isDirectory = false,
        size = file.length(),
        lastModified = file.lastModified()
    )

    // ————— 空文件夹清理 —————

    /**
     * 删除目录内的空文件夹（不含根目录本身）：自底向上——先递归子目录，
     * 子目录删空后若父目录也变空则继续向上删。
     * 越界保护：root 必须位于已知 File 沙盒内，否则拒绝执行。
     * @return 实际删除的空文件夹数量
     */
    fun cleanEmptyFolders(root: File): Int {
        if (!FileLocations.isInsideSandbox(root)) return 0
        var removed = 0
        val children = root.listFiles() ?: return 0
        for (child in children) {
            if (!child.isDirectory || child.name.startsWith(".")) continue
            removed += cleanEmptyFolders(child)
            if (child.isDirectory && child.listFiles()?.isEmpty() == true) {
                if (child.delete()) removed++
            }
        }
        return removed
    }

    /**
     * 清空一个目录下的全部内容（递归），目录本身保留。
     * 用于压缩包解压工作区（`.temp_unzip`）的残留清理：解压中途断电 / 进程被杀会留下
     * 半个工作目录，SyncManager 每次对账兜底清掉。
     * 越界保护：目标必须在已知 File 沙盒内，否则拒绝执行。
     * @param skipActiveWithinMs 大于 0 时，目录树内最近该毫秒数内仍有写入的子目录视为
     *   「可能正在使用」（如仍在进行的解压工作区），跳过不删。
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
     * 直到遇到非空目录或分类根目录为止；不会越过沙盒边界。
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

    // ————— 时长 / 元数据 —————

    /**
     * 提取视频时长（毫秒）；失败或非视频返回 0。须在 IO 线程调用。
     * [path] 是绝对路径，直接用 `setDataSource(path)`（比 URI 版本更快，也不依赖 `ContentResolver`）。
     */
    fun extractVideoDuration(context: Context, path: String): Long = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(path)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }
    }.getOrDefault(0L)

    /** 构造一个已指向 [path] 的 retriever（播放器取缩略帧用） */
    fun retrieverFor(context: Context, path: String): MediaMetadataRetriever =
        MediaMetadataRetriever().apply { setDataSource(path) }

    // ————— 展示格式化 —————

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
    fun mimeFor(file: File): String = mimeTypeOf(file.name)

    /** 按文件名猜测 MIME 类型（原始实现，[mimeTypeOf] 的别名） */
    fun mimeForName(name: String): String = mimeTypeOf(name)

    /**
     * 调用系统应用打开「其他」分类文件（APK→安装器 / PDF→阅读器…）。
     *
     * [path] 为绝对路径：经 FileProvider 暴露（避免 `file://` 在 Android 7+ 触发
     * `FileUriExposedException`）。
     */
    fun openExternal(context: Context, path: String, name: String) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", File(path)
            )
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeForName(name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            IntentUtils.startSafely(context, intent, "无法打开此文件类型，请安装对应应用")
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开文件：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** 列出目录内属于该分类的文件与全部子文件夹（文件夹在前）。**仅 File 侧**，媒体库不再使用 */
    @Deprecated("媒体库已改为 IStorage + Room 驱动，此函数仅保留给本地 File 场景")
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
            FileEntry(
                path = it.absolutePath,
                name = it.name,
                isDirectory = it.isDirectory,
                size = if (it.isDirectory) 0L else it.length(),
                lastModified = it.lastModified(),
                parentPath = it.parentFile?.absolutePath.orEmpty()
            )
        }
        return toEntries(dirs).sortedWith(cmp) + toEntries(matched).sortedWith(cmp)
    }

    /** 删除单个媒体文件（File 侧）：成功后连带清理空父目录。越界保护同 [FileLocations.isInsideSandbox] */
    fun deletePhysicalFile(file: File): Boolean {
        if (!FileLocations.isInsideSandbox(file)) return false
        if (!file.exists()) return true
        return runCatching {
            if (!file.delete()) return false
            deleteEmptyAncestors(file)
            true
        }.getOrDefault(false)
    }
}
