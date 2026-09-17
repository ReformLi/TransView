package com.hpu.transview.data

import android.content.Context
import com.hpu.transview.data.db.AppDatabase
import com.hpu.transview.data.db.MediaItemDao
import com.hpu.transview.data.db.MediaItemEntity
import com.hpu.transview.model.Category
import com.hpu.transview.util.FileLocations
import com.hpu.transview.util.nameIsImageFile
import com.hpu.transview.util.nameIsVideoFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/** 媒体类型常量（数据库存储值） */
object MediaType {
    const val VIDEO = 0
    const val IMAGE = 1
    const val OTHER = 2

    fun fromCategory(category: Category): Int = when (category) {
        Category.VIDEO -> VIDEO
        Category.IMAGE -> IMAGE
        Category.OTHER -> OTHER
    }

    fun toCategory(type: Int): Category = when (type) {
        VIDEO -> Category.VIDEO
        IMAGE -> Category.IMAGE
        else -> Category.OTHER
    }

    fun fromFileName(name: String): Int = when {
        nameIsVideoFile(name) -> VIDEO
        nameIsImageFile(name) -> IMAGE
        else -> OTHER
    }

    fun fromFile(file: File): Int = fromFileName(file.name)
}

/** 媒体索引仓储：UI / SyncManager / 播放器与 Room 的唯一通道 */
class MediaRepository(context: Context) {

    private val dao: MediaItemDao = AppDatabase.getInstance(context).mediaItemDao()

    suspend fun getByPath(path: String): MediaItemEntity? =
        withContext(Dispatchers.IO) { dao.getByPath(path) }

    suspend fun getAll(): List<MediaItemEntity> =
        withContext(Dispatchers.IO) { dao.getAll() }

    suspend fun getAllPaths(): List<String> =
        withContext(Dispatchers.IO) { dao.getAllPaths() }

    fun observeByType(type: Int): Flow<List<MediaItemEntity>> = dao.observeByType(type)

    fun observeByCategory(category: Category): Flow<List<MediaItemEntity>> =
        dao.observeByType(MediaType.fromCategory(category))

    /**
     * 插入或更新（以 filePath 唯一索引为准）：物理文件的信息以调用方扫描结果为准整体覆盖。
     *
     * [addedTime] 仅在**首次插入**时采用（更新时保留原值，排序稳定不跳）：
     * 对账扫描传**文件系统的 lastModified**，使「按时间排序」对「手动拷入沙盒」的文件也成立
     * （这类文件没有上传时间可用）。
     * @return 数据库 id
     */
    suspend fun upsert(
        filePath: String,
        fileName: String,
        mediaType: Int,
        parentFolder: String,
        fileSize: Long,
        lastModified: Long,
        duration: Long,
        addedTime: Long = System.currentTimeMillis()
    ): Long = withContext(Dispatchers.IO) {
        val existing = dao.getByPath(filePath)
        val now = System.currentTimeMillis()
        val entity = MediaItemEntity(
            id = existing?.id ?: 0,
            filePath = filePath,
            fileName = fileName,
            mediaType = mediaType,
            parentFolder = parentFolder,
            fileSize = fileSize,
            lastModified = lastModified,
            duration = if (duration > 0) duration else existing?.duration ?: 0L,
            // 更新保留原 addedTime；首插用调用方传入的 addedTime（对账=文件系统 mtime），缺省=当前时间
            addedTime = existing?.addedTime ?: addedTime.let { if (it > 0) it else now }
        )
        if (existing == null) {
            dao.insert(entity)
            // IGNORE 冲突（并发插入同 path）时查回已有 id
            dao.getByPath(filePath)?.id ?: 0L
        } else {
            dao.update(entity)
            entity.id
        }
    }

    /** 按路径删除索引（播放历史级联删除）。@return 是否确实删除了记录 */
    suspend fun deleteByPath(path: String): Boolean = withContext(Dispatchers.IO) {
        val existing = dao.getByPath(path) ?: return@withContext false
        dao.deleteByPath(path)
        true
    }

    /**
     * 按路径批量删除索引（播放历史经外键级联删除）。
     *
     * **必须分批**：Room 把 `filePath IN (:paths)` 展开成**等量**的 `?` 绑定参数，
     * 条目一多就会撞上 SQLite 的宿主参数上限（旧版 SQLite 只有 999）。超限时整批语句直接抛异常，
     * 而调用方（对账「同步外部删除」）在 `try/catch` 里把它兜成「对账异常，已兜底结束」——
     * 表现就是**一次删掉上千个文件后，媒体库里的僵尸卡片永远清不掉且毫无提示**。
     * 每批 [SQL_BIND_CHUNK] 条留足余量，超大批量也只是一次多循环。
     */
    suspend fun deleteByPaths(paths: List<String>) = withContext(Dispatchers.IO) {
        paths.chunked(SQL_BIND_CHUNK).forEach { dao.deleteByPaths(it) }
    }

    /**
     * 「不属于任何当前已知卷」的索引路径 —— 那块存储已经彻底不在设备上了
     * （盘被永久移除 / 换了盘符 / 换了盘），索引再也无法生效。
     *
     * ## 与对账判据的区别（关键）
     * 对账走 [FileLocations.existsForPath]，对「读不到」一律判**存在**（防拔盘误删，见其注释）；
     * 本方法是给**用户主动清理**用的，判据换成「连所属卷都不在设备的卷列表里」——
     * 比「卷在位但不可用（已拔出的首选盘）」更严格：后者插回就能复活，**不在此列**。
     *
     * 这类记录在媒体库里看不到（展示按活动沙盒过滤），对账又永不删，因此**前端原本没有任何
     * 出口能清掉它们**，只能一直躺在库里。
     */
    suspend fun orphanIndexPaths(): List<String> = withContext(Dispatchers.IO) {
        val roots = FileLocations.storageState.value.volumes.map { it.root.absolutePath }
        dao.getAllPaths().filter { path ->
            roots.none { root -> path == root || path.startsWith("$root${File.separator}") }
        }
    }

    /** 不可达索引的条数（设置页展示用） */
    suspend fun countOrphanIndexes(): Int = orphanIndexPaths().size

    /**
     * 删除全部不可达索引（播放历史经外键级联删除）。@return 实际删除的条数
     */
    suspend fun purgeOrphanIndexes(): Int = withContext(Dispatchers.IO) {
        val paths = orphanIndexPaths()
        paths.chunked(SQL_BIND_CHUNK).forEach { dao.deleteByPaths(it) }
        paths.size
    }

    private companion object {
        /** 单条 SQL 的绑定参数上限保险值（SQLite 旧上限 999；取一半留余量） */
        const val SQL_BIND_CHUNK = 400
    }
}
