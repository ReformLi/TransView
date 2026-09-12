package com.hpu.transview.data

import android.content.Context
import com.hpu.transview.data.db.AppDatabase
import com.hpu.transview.data.db.MediaItemDao
import com.hpu.transview.data.db.MediaItemEntity
import com.hpu.transview.data.db.PlaybackHistoryDao
import com.hpu.transview.data.db.PlaybackHistoryEntity
import com.hpu.transview.data.db.PlaybackProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/** 续播信息（保持播放器调用面兼容） */
data class ResumeInfo(val position: Long, val duration: Long)

/**
 * 播放历史仓储：对外仍以「文件路径」为键（播放器不感知外键），
 * 内部桥接到 MediaItem + PlaybackHistory（mediaItemId 外键，级联删除）。
 */
class PlaybackRepository(context: Context) {

    private val historyDao: PlaybackHistoryDao =
        AppDatabase.getInstance(context).playbackHistoryDao()
    private val mediaDao: MediaItemDao =
        AppDatabase.getInstance(context).mediaItemDao()

    /** 全部播放进度流（媒体库网格进度条用），经 Room Flow 实时刷新 */
    fun observeAllProgress(): Flow<List<PlaybackProgress>> = historyDao.observeAllProgress()

    /** 查询续播信息；duration 取媒体索引值 */
    suspend fun get(path: String): ResumeInfo? = withContext(Dispatchers.IO) {
        val item = mediaDao.getByPath(path) ?: return@withContext null
        val history = historyDao.getByPath(path) ?: return@withContext null
        ResumeInfo(
            position = history.position,
            duration = item.duration
        )
    }

    /** 保存进度；接近片尾视为已看完，直接清掉历史 */
    suspend fun save(path: String, title: String, position: Long, duration: Long) {
        withContext(Dispatchers.IO) {
            if (duration > 0 && position >= duration - 5_000) {
                deleteInternal(path)
                return@withContext
            }
            if (position <= 1_000) return@withContext

            // 确保媒体索引存在（播放器保存进度时 SyncManager 可能尚未入库）
            val itemId = ensureMediaItem(path, title, duration)
            if (itemId <= 0) return@withContext

            val existing = historyDao.getByPath(path)
            val entity = PlaybackHistoryEntity(
                id = existing?.id ?: 0,
                mediaItemId = itemId,
                position = position,
                updatedTime = System.currentTimeMillis()
            )
            if (existing == null) historyDao.insert(entity) else historyDao.update(entity)
        }
    }

    /** 清除续播历史（媒体索引保留） */
    suspend fun clear(path: String) = withContext(Dispatchers.IO) { deleteInternal(path) }

    private suspend fun deleteInternal(path: String) {
        val item = mediaDao.getByPath(path) ?: return
        historyDao.deleteByMediaItemId(item.id)
    }

    /** 播放器首次保存时自动补建媒体索引（若 SyncManager 尚未同步到该文件） */
    private suspend fun ensureMediaItem(path: String, title: String, duration: Long): Long {
        mediaDao.getByPath(path)?.let { return it.id }
        val file = File(path)
        if (!file.isFile) return 0
        val entity = MediaItemEntity(
            filePath = path,
            fileName = title.ifBlank { file.name },
            mediaType = MediaType.fromFile(file),
            parentFolder = file.parentFile?.absolutePath ?: "",
            fileSize = file.length(),
            lastModified = file.lastModified(),
            duration = duration,
            addedTime = System.currentTimeMillis()
        )
        return mediaDao.insert(entity)
    }
}
