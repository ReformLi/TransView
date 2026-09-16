package com.hpu.transview.data.sync

import android.content.Context
import android.util.Log
import com.hpu.transview.data.MediaRepository
import com.hpu.transview.data.MediaType
import com.hpu.transview.data.UploadRecordRepository
import com.hpu.transview.model.Category
import com.hpu.transview.model.UploadState
import com.hpu.transview.server.ServerBus
import com.hpu.transview.server.UploadBus
import com.hpu.transview.storage.StorageFile
import com.hpu.transview.util.FileLocations
import com.hpu.transview.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** 一次对账的结果统计 */
data class SyncResult(
    val removedEmptyFolders: Int,
    val deletedMissing: Int,
    val inserted: Int,
    val updated: Int,
    val durationMs: Long,
    /** 本轮对账是否处于降级模式（首选U盘但已拔出）：此模式跳过了「同步外部删除」 */
    val degraded: Boolean = false
) {
    val changed: Boolean get() = removedEmptyFolders + deletedMissing + inserted + updated > 0
}

/**
 * 对账进度（供 UI 展示扫描状态）。
 *
 * `Running.step` 是人话进度（如「正在扫描：Movies/电视剧（12 项）」），UI 直接显示即可；
 * `SyncComplete` 是**终态**，UI 收到后弹一次「扫描完成」短暂提示（见 LibraryScreen）。
 */
sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val step: String) : SyncState
    data class SyncComplete(val result: SyncResult) : SyncState
}

/**
 * 数据库与物理文件一致性对账引擎（单例）。
 *
 * 五步（全部在 Dispatchers.IO，严禁阻塞主线程）：
 * 0. 清理解压工作区：物理删除两个沙盒（内部存储 + 在位U盘）`.temp_unzip/` 下的全部残留
 *    （压缩包解压途中断电 / 进程被杀会留下半个工作目录，不清理会一直占着空间）；
 * 0.5 清扫僵尸「上传中」记录（见下）；
 * 1. 清理空文件夹：递归扫描三个媒体根目录，物理删除空文件夹，子删父空继续向上；
 * 1.5 清理 v1.12/v1.13 SAF 时代遗留的 `content://` 索引；
 * 2. 同步外部删除：数据库有索引但物理文件不存在 → 删记录（播放历史经外键级联删除）；
 * 3. 同步新增/变更：按**分类目录**扫描并入库（见下「目录即分类」）。
 *
 * ## 目录即分类（方案 A，v1.15）
 * 沙盒三个子目录各自只收**本分类的格式**，扫描时在**列目录阶段**就用 `FileFilter` 过滤掉无关文件：
 * - `Movies/`（视频目录）：只收视频扩展名（.mp4/.mkv/…），入库 `mediaType = 0`；
 *   遇到 .txt/.jpg/.zip 等**直接跳过**（不入库、媒体库不展示，**绝不删物理文件**）。
 * - `Pictures/`（图片目录）：只收图片扩展名，入库 `mediaType = 1`；非图片跳过。
 * - `Downloads/`（其他目录）：只收**非视频且非图片**文件，入库 `mediaType = 2`；视频/图片跳过。
 *
 * 判定统一走 [com.hpu.transview.util.isValidFormatForCategory]（复用既有扩展名工具），
 * SyncManager 里**不另写一套**。入库类型由**目录**决定，而非文件扩展名
 * —— 这正是「目录即分类」：同一扩展名出现在错误目录里也不会被收进来。
 *
 * 手动拷入（U盘/文件管理器/电脑）的文件与上传文件走同一条路径：只写 `media_items`，
 * 不写 `upload_records`、不写 `playback_history`（后者在真正播放时才由 PlaybackRepository 建），
 * 故这里无需额外代码。
 *
 * ## 性能与内存
 * - **FileFilter 列目录**：`dir.listFiles { it.isDirectory || isValidFormatForCategory(it, category) }`
 *   —— 只保留「有效文件 + 全部文件夹」，十万个垃圾文件也不会进数组（旧实现先全量 `listFiles()` 再判断）。
 * - **扫描顺序**：视频 → 图片 → 其他。视频/图片先入库（媒体库尽快出内容），
 *   「其他」不需提取时长，放最后。
 * - **增量**：先查库，`path` 已存在且 `fileSize/lastModified/parentFolder` 未变 → 直接跳过，
 *   不提取时长、不写库。只有「库里没有」或「变了」的才走 upsert。
 * - **边扫边入库**：每命中一个新文件立即 upsert，不等全部扫完（首扫不会对着空列表干等）。
 * - **进度**：扫描期间经 [syncState] 推送 `Running`，完成后推送 `SyncComplete`。
 *
 * ## 降级模式防误删（关键）
 * 对账开始先经 [FileLocations.refresh] 重检活动存储。**降级模式**（首选U盘但U盘已拔出，
 * 活动存储=内部存储）下**绝对禁止**执行第 2 步：数据库里U盘的历史记录此刻全部"物理不存在"，
 * 一刀删会把U盘全部索引连同播放历史清空——U盘只是被拔出，不是被删除。降级期间只执行
 * 清理临时目录 / 清空文件夹 / 扫描内部存储新增，U盘记录原样保留；U盘插回后记录自动重新生效。
 * 降级期间写入内部存储的文件不搬运、不丢失：记录保留在库中，媒体库按活动沙盒过滤展示，
 * U盘再次拔出时它们会重新出现。
 *
 * ## 异常兜底（v1.15）
 * 单个目录读不到（权限 / 拔盘 / ROM 限制）→ `FileUtils.scanCategoryFiles` 内部跳过该目录、继续扫其余；
 * 且 [sync] 整体捕获一切异常，**任何情况下都能正常结束并返回结果**（绝不冒泡到调用方/主线程）。
 *
 * 完成后经 syncState StateFlow 通知 UI；由 App 启动（Application）、U盘插拔（ServerService
 * 去抖重检后）与手动刷新触发。
 *
 * ## 存储抽象（v1.14，纯 File）
 * 遍历（[FileUtils.scanCategoryFiles]，底层 `java.io.File`）与存在性判定
 * （[FileLocations.existsForPath]）全部走活动存储（内部存储与 U 盘同为 `java.io.File`）。
 * 核心不变式：**「读不到」绝不等于「被删了」** —— 路径所属卷当前不可见（U 盘已拔出）时，
 * 存在性一律判为「存在」，绝不删索引。
 */
class SyncManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val mediaRepository = MediaRepository(appContext)
    private val uploadRecordRepository = UploadRecordRepository(appContext)

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    /** 串行化对账：启动触发与手动刷新并发时只跑一轮 */
    private val syncMutex = Mutex()

    /** 视频时长缓存（进程内），避免每次对账重复 MediaMetadataRetriever */
    private val durationCache = ConcurrentHashMap<String, Long>()

    /**
     * 执行一次完整对账。已在进行中时直接返回（不排队不阻塞调用方）。
     *
     * **永不抛出**：任何异常都被兜底成一份「未变更」的结果并照常推送 `SyncComplete`
     * （对账运行在应用启动 / 插拔广播 / 手动刷新等路径上，崩出去会连累调用方）。
     */
    suspend fun sync(): SyncResult = syncMutex.withLock {
        val startAt = System.currentTimeMillis()
        var degraded = false
        var preferredLabel = ""
        var removedEmptyFolders = 0
        var deletedMissing = 0
        var inserted = 0
        var updated = 0
        var dbIndex: Map<String, DbInfo> = emptyMap()

        try {
            // 对账开始前重检存储状态：外接盘插拔广播（去抖 2s）可能与本轮对账竞争，以最新状态为准。
            // 首选是外接盘但当前不可用（已拔出 / 写探针失败）→ degraded=true：本轮跳过「同步外部删除」
            // （防误删，见类注释）。重检含文件系统扫描与写探针，包进 IO——调用方可能在主线程。
            val storageState = withContext(Dispatchers.IO) { FileLocations.refresh() }
            degraded = storageState.degraded
            preferredLabel = storageState.preferredLabel
            val storage = storageState.activeStorage
            _syncState.value = SyncState.Running("正在清理临时文件")

            // 0. 清理解压工作区残留（断电 / 强杀后可能留下半个工作目录）。
            //    全部 File 卷沙盒的临时目录都兜底清理——上一模式留下的残留也要清，谁在位清谁。
            //    v1.14 起解压工作区恒在活动沙盒内（见 FileLocations.tempUnzipDir）。
            //    跳过最近仍在写入的工作区：手动对账可能撞上正在进行的
            //    解压（或多台手机并发），一刀清掉会把在途压缩包连同已解出的文件一起误删；
            //    这些工作区在空闲 10 分钟后会被下一轮对账兜底清掉。
            withContext(Dispatchers.IO) {
                FileLocations.allTempUnzipDirs().forEach { dir ->
                    FileUtils.purgeDirectory(dir, skipActiveWithinMs = ACTIVE_WORKSPACE_GRACE_MS)
                }
            }

            // 0.5 清扫僵尸「上传中」记录：进程被杀 / 断电时请求线程的兜底收尾没机会执行，
            //     DB 会残留永远停在「上传中 xx%」的死记录。仅在「服务器未运行 或 本进程无在途
            //     上传」时执行——手动对账可能撞上活的上传（UploadBus 有 RUNNING），那是活数据不能动。
            //     App 启动对账（Application.onCreate）时服务器必然尚未启动，僵尸必被清扫。
            val hasLiveUpload = ServerBus.running.value &&
                UploadBus.records.value.any { it.state == UploadState.RUNNING }
            if (!hasLiveUpload) {
                uploadRecordRepository.reapZombieRunning()
            }

            // 1. 清理空文件夹（只扫活动存储的三个分类目录）
            _syncState.value = SyncState.Running("正在清理空文件夹")
            removedEmptyFolders = withContext(Dispatchers.IO) {
                Category.entries.sumOf { storage.cleanEmptyFolders(FileLocations.categoryRelative(it)) }
            }

            // 1.5 清理 v1.12/v1.13 SAF 时代遗留的 `content://` 索引（v1.14 已彻底移除 SAF）：
            //     这类记录指向的文档树已永久不可达（existsForPath 对 content:// 恒返回 false），
            //     留着只会让媒体库出现「点不开」的僵尸卡片。**与降级无关**：任何状态下都清，
            //     所以放在降级判断之前。
            val safLegacy = withContext(Dispatchers.IO) {
                mediaRepository.getAllPaths().filter { it.startsWith("content://") }
            }
            if (safLegacy.isNotEmpty()) {
                withContext(Dispatchers.IO) { mediaRepository.deleteByPaths(safLegacy) }
            }

            // 2. 同步外部删除（防"有索引无文件"）。
            //    降级模式（首选外接盘但当前不可用）绝对禁止执行：外接盘的历史记录此刻全部"物理不存在"，
            //    执行等于把该盘全部索引连同播放历史清空。记录保留，媒体库展示侧按活动沙盒过滤，
            //    外接盘插回后原样生效。
            //    存在性判定经 FileLocations.existsForPath：只有该路径所属的卷**当前可用**时才做真实
            //    探测 —— 「读不到」绝不等于「被删了」。
            if (degraded) {
                _syncState.value = SyncState.Running(
                    "${preferredLabel}已断开，跳过删除核对（历史记录已保留）"
                )
            } else {
                _syncState.value = SyncState.Running("正在核对已有索引")
                val dbPaths = mediaRepository.getAllPaths()
                val missing = withContext(Dispatchers.IO) {
                    dbPaths.filter { path -> !FileLocations.existsForPath(path) }
                }
                mediaRepository.deleteByPaths(missing)
                deletedMissing = missing.size
            }

            // 3. 同步新增 / 变更（防"有文件无索引"；只扫活动存储）。
            //    方案 A：按分类目录顺序扫描（视频 → 图片 → 其他），每个目录只收本分类合法格式
            //    （过滤在 FileUtils.scanCategoryFiles 内用 FileFilter 于列目录阶段完成）。
            dbIndex = withContext(Dispatchers.IO) {
                // 全量字段再取一次（deleteByPaths 之后）
                mediaRepository.getAll().associate {
                    it.filePath to DbInfo(it.fileSize, it.lastModified, it.parentFolder)
                }
            }
            for (category in SCAN_ORDER) {
                val files = withContext(Dispatchers.IO) {
                    FileUtils.scanCategoryFiles(category) { dir, count ->
                        val rel = runCatching { storage.relativeOf(dir.absolutePath) }
                            .getOrNull().orEmpty().ifEmpty { dir.name }
                        _syncState.value = SyncState.Running("正在扫描：${rel}（${count} 项）")
                    }
                }
                for (file in files) {
                    val path = file.path
                    val existing = dbIndex[path]
                    val changed = existing == null ||
                        existing.fileSize != file.size ||
                        existing.lastModified != file.lastModified ||
                        existing.parentFolder != file.parentPath
                    if (changed) {
                        upsertFile(file, category)
                        if (existing == null) inserted++ else updated++
                    }
                }
            }

            // 清理时长缓存中已不在数据库的条目（以库内记录为准而非本轮扫描结果：
            // 降级模式下U盘记录未扫描但仍在库中，缓存保留待插回后复用，免于重新提取）
            durationCache.keys.removeAll { it !in dbIndex }
        } catch (t: Throwable) {
            // 对账永不抛出：任何异常（含 FileLocations.refresh 失败、DB 异常）都兜底为
            // 「未变更」的结果，保证 sync() 在任何情况下都能正常结束（见类注释「异常兜底」）。
            Log.w(TAG, "对账过程异常，已兜底结束", t)
        }

        val result = SyncResult(
            removedEmptyFolders = removedEmptyFolders,
            deletedMissing = deletedMissing,
            inserted = inserted,
            updated = updated,
            durationMs = System.currentTimeMillis() - startAt,
            degraded = degraded
        )
        _syncState.value = SyncState.SyncComplete(result)
        return result
    }

    /**
     * 上报物理文件已丢失（播放器起播失败兜底）：删索引并触发 UI 刷新。
     *
     * 防误删：仅当路径属于本 App 管理的沙盒（内部存储或某块在位 U 盘上的 TransView）才允许删除
     * —— 正在播放 U 盘视频时拔出 U 盘会
     * 触发播放错误，此刻该路径已不在任何可达沙盒内，若照删会连同播放历史（外键级联）一起清空；
     * U 盘插回后记录应原样恢复。
     */
    suspend fun reportMissingFile(path: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!FileLocations.isManagedPath(path)) return@withContext false
            durationCache.remove(path)
            mediaRepository.deleteByPath(path)
        }.also {
            // Room 的 observeByType Flow 会自动推送，UI 监听即可刷新
        }

    /**
     * 入库一个物理文件。
     *
     * **mediaType 由目录分类 [category] 决定**（方案 A），不是按文件扩展名猜——
     * 扫描阶段已保证该文件格式与目录匹配。时长只对视频提取（进程内缓存）。
     * `addedTime` 用文件系统的 lastModified，使「按时间排序」对手动拷入的文件也成立。
     */
    private suspend fun upsertFile(file: StorageFile, category: Category) {
        val type = MediaType.fromCategory(category)
        val duration = if (category == Category.VIDEO) {
            durationCache.getOrPut(file.path) {
                FileUtils.extractVideoDuration(appContext, file.path)
            }
        } else 0L
        mediaRepository.upsert(
            filePath = file.path,
            fileName = file.name,
            mediaType = type,
            parentFolder = file.parentPath,
            fileSize = file.size,
            lastModified = file.lastModified,
            duration = duration,
            addedTime = file.lastModified
        )
    }

    /** 单条索引的比对信息（增量对账用） */
    private data class DbInfo(
        val fileSize: Long,
        val lastModified: Long,
        val parentFolder: String
    )

    companion object {
        private const val TAG = "SyncManager"

        /** 在途解压工作区的保护窗口：最近该时长内仍有写入的工作目录不对账清理 */
        private const val ACTIVE_WORKSPACE_GRACE_MS = 10 * 60 * 1000L

        /**
         * 扫描顺序：视频 → 图片 → 其他。
         * 视频/图片先入库，媒体库尽快显示内容并开始提取时长；「其他」无需时长，放最后。
         */
        private val SCAN_ORDER = listOf(Category.VIDEO, Category.IMAGE, Category.OTHER)

        @Volatile
        private var instance: SyncManager? = null

        fun getInstance(context: Context): SyncManager =
            instance ?: synchronized(this) {
                instance ?: SyncManager(context).also { instance = it }
            }
    }
}
