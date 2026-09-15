package com.hpu.transview.data.sync

import android.content.Context
import com.hpu.transview.data.MediaRepository
import com.hpu.transview.data.MediaType
import com.hpu.transview.data.UploadRecordRepository
import com.hpu.transview.model.UploadState
import com.hpu.transview.server.ServerBus
import com.hpu.transview.server.UploadBus
import com.hpu.transview.util.FileLocations
import com.hpu.transview.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
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

/** 对账进度（供 UI 展示扫描状态） */
sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val step: String) : SyncState
    data class Done(val result: SyncResult) : SyncState
}

/**
 * 数据库与物理文件一致性对账引擎（单例）。
 *
 * 三步（全部在 Dispatchers.IO，严禁阻塞主线程）：
 * 0. 清理解压工作区：物理删除两个沙盒（内部存储 + 在位U盘）`.temp_unzip/` 下的全部残留
 *    （压缩包解压途中断电 / 进程被杀会留下半个工作目录，不清理会一直占着空间）；
 * 1. 清理空文件夹：递归扫描三个媒体根目录，物理删除空文件夹，子删父空继续向上；
 * 2. 同步外部删除：数据库有索引但物理文件不存在 → 删记录（播放历史经外键级联删除）；
 * 3. 同步新增/变更：物理文件无记录 → 提取信息入库；有记录但 lastModified/fileSize 变化 → 更新。
 *
 * ## 降级模式防误删（关键）
 * 对账开始先经 [FileLocations.refresh] 重检活动存储。**降级模式**（首选U盘但U盘已拔出，
 * 活动存储=内部存储）下**绝对禁止**执行第 2 步：数据库里U盘的历史记录此刻全部"物理不存在"，
 * 一刀删会把U盘全部索引连同播放历史清空——U盘只是被拔出，不是被删除。降级期间只执行
 * 清理临时目录 / 清空文件夹 / 扫描内部存储新增，U盘记录原样保留；U盘插回后记录自动重新生效。
 * 降级期间写入内部存储的文件不搬运、不丢失：记录保留在库中，媒体库按活动沙盒过滤展示，
 * U盘再次拔出时它们会重新出现。
 *
 * 完成后经 syncState StateFlow 通知 UI；由 App 启动（Application）、U盘插拔（ServerService
 * 去抖重检后）与手动刷新触发。
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
     */
    suspend fun sync(): SyncResult = syncMutex.withLock {
        val startAt = System.currentTimeMillis()
        // 对账开始前重检存储状态：外接盘插拔广播（去抖 2s）可能与本轮对账竞争，以最新状态为准。
        // 首选是外接盘但当前不在位 → degraded=true：本轮跳过「同步外部删除」（防误删，见类注释）。
        // 重检含文件系统探测（列卷 + canWrite），包进 IO——调用方（前台服务）可能在主线程。
        val storageState = withContext(Dispatchers.IO) { FileLocations.refresh() }
        val degraded = storageState.degraded
        _syncState.value = SyncState.Running("正在清理临时文件")

        // 0. 清理解压工作区残留（断电 / 强杀后可能留下半个工作目录）。
        //    全部在位卷沙盒的临时目录都兜底清理——上一模式留下的残留也要清，谁在位清谁。
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

        // 1. 清理空文件夹（只扫活动沙盒）
        _syncState.value = SyncState.Running("正在清理空文件夹")
        val removedFolders = withContext(Dispatchers.IO) {
            FileLocations.allRoots().sumOf { FileUtils.cleanEmptyFolders(it) }
        }

        // 2. 同步外部删除（防"有索引无文件"）。
        //    降级模式（首选外接盘但已拔出）绝对禁止执行：外接盘的历史记录此刻全部"物理不存在"，
        //    执行等于把该盘全部索引连同播放历史清空。记录保留，媒体库展示侧按活动沙盒过滤，
        //    外接盘插回后原样生效。
        var deletedMissing = 0
        if (degraded) {
            _syncState.value = SyncState.Running(
                "${storageState.preferredLabel}已断开，跳过删除核对（历史记录已保留）"
            )
        } else {
            _syncState.value = SyncState.Running("正在核对已有索引")
            val dbPaths = mediaRepository.getAllPaths()
            val missing = withContext(Dispatchers.IO) {
                dbPaths.filter { path -> !File(path).exists() }
            }
            mediaRepository.deleteByPaths(missing)
            deletedMissing = missing.size
        }

        // 3. 同步新增 / 变更（防"有文件无索引"；只扫活动沙盒）
        _syncState.value = SyncState.Running("正在扫描媒体文件")
        data class DbInfo(val fileSize: Long, val lastModified: Long, val parentFolder: String)
        val dbIndex: Map<String, DbInfo> = withContext(Dispatchers.IO) {
            // 全量字段再取一次（deleteByPaths 之后）
            mediaRepository.getAll().associate {
                it.filePath to DbInfo(it.fileSize, it.lastModified, it.parentFolder)
            }
        }
        val physicalFiles = withContext(Dispatchers.IO) { FileUtils.listAllMediaFiles() }

        var inserted = 0
        var updated = 0
        for (file in physicalFiles) {
            val path = file.absolutePath
            val existing = dbIndex[path]
            val changed = existing == null ||
                existing.fileSize != file.length() ||
                existing.lastModified != file.lastModified() ||
                existing.parentFolder != (file.parentFile?.absolutePath ?: "")
            if (changed) {
                upsertFile(file)
                if (existing == null) inserted++ else updated++
            }
        }

        // 清理时长缓存中已不在数据库的条目（以库内记录为准而非本轮扫描结果：
        // 降级模式下U盘记录未扫描但仍在库中，缓存保留待插回后复用，免于重新提取）
        durationCache.keys.removeAll { it !in dbIndex }
        val result = SyncResult(
            removedEmptyFolders = removedFolders,
            deletedMissing = deletedMissing,
            inserted = inserted,
            updated = updated,
            durationMs = System.currentTimeMillis() - startAt,
            degraded = degraded
        )
        _syncState.value = SyncState.Done(result)
        return result
    }

    /**
     * 上报物理文件已丢失（播放器 FileNotFoundException 兜底）：删索引并触发 UI 刷新。
     *
     * 防误删：仅当路径位于任一已知沙盒（内部存储 / 在位U盘）内才允许删除——
     * 正在播放U盘视频时拔出U盘会触发播放错误，此刻U盘路径已不在任何沙盒内，
     * 若照删会连同播放历史（外键级联）一起清空；U盘插回后记录应原样恢复。
     */
    suspend fun reportMissingFile(path: String): Boolean =
        withContext(Dispatchers.IO) {
            val file = File(path)
            if (!FileLocations.isInsideSandbox(file)) return@withContext false
            durationCache.remove(path)
            mediaRepository.deleteByPath(path)
        }.also {
            // Room 的 observeByType Flow 会自动推送，UI 监听即可刷新
        }

    private suspend fun upsertFile(file: File) {
        val type = MediaType.fromFile(file)
        val duration = if (type == MediaType.VIDEO) {
            durationCache.getOrPut(file.absolutePath) {
                FileUtils.extractVideoDuration(file)
            }
        } else 0L
        mediaRepository.upsert(
            filePath = file.absolutePath,
            fileName = file.name,
            mediaType = type,
            parentFolder = file.parentFile?.absolutePath ?: "",
            fileSize = file.length(),
            lastModified = file.lastModified(),
            duration = duration
        )
    }

    companion object {
        /** 在途解压工作区的保护窗口：最近该时长内仍有写入的工作目录不对账清理 */
        private const val ACTIVE_WORKSPACE_GRACE_MS = 10 * 60 * 1000L

        @Volatile
        private var instance: SyncManager? = null

        fun getInstance(context: Context): SyncManager =
            instance ?: synchronized(this) {
                instance ?: SyncManager(context).also { instance = it }
            }
    }
}
