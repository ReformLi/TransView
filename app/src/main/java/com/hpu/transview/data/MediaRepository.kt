package com.hpu.transview.data

import android.content.Context
import com.hpu.transview.data.db.AppDatabase
import com.hpu.transview.data.db.MediaItemDao
import com.hpu.transview.data.db.MediaItemEntity
import com.hpu.transview.model.Category
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
     * @return 数据库 id
     */
    suspend fun upsert(
        filePath: String,
        fileName: String,
        mediaType: Int,
        parentFolder: String,
        fileSize: Long,
        lastModified: Long,
        duration: Long
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
            addedTime = existing?.addedTime ?: now
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

    suspend fun deleteByPaths(paths: List<String>) = withContext(Dispatchers.IO) {
        if (paths.isNotEmpty()) dao.deleteByPaths(paths)
    }
}
