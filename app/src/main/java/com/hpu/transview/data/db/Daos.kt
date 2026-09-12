package com.hpu.transview.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaItemDao {

    @Query("SELECT * FROM media_items WHERE filePath = :path LIMIT 1")
    suspend fun getByPath(path: String): MediaItemEntity?

    @Query("SELECT * FROM media_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): MediaItemEntity?

    @Query("SELECT * FROM media_items")
    suspend fun getAll(): List<MediaItemEntity>

    @Query("SELECT filePath FROM media_items")
    suspend fun getAllPaths(): List<String>

    @Query("SELECT * FROM media_items WHERE mediaType = :type")
    fun observeByType(type: Int): Flow<List<MediaItemEntity>>

    @Query("SELECT COUNT(*) FROM media_items")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: MediaItemEntity): Long

    @Update
    suspend fun update(item: MediaItemEntity)

    /** 按 path 删除，playback_history 经外键级联删除 */
    @Query("DELETE FROM media_items WHERE filePath = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM media_items WHERE filePath IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)
}

/**
 * 播放进度投影：媒体库网格卡片绘制「观看进度条」用。
 * position/duration 单位毫秒；duration<=0 表示时长未知，前端应跳过绘制。
 */
data class PlaybackProgress(
    val filePath: String,
    val position: Long,
    val duration: Long
)

@Dao
interface PlaybackHistoryDao {

    @Query(
        "SELECT playback_history.* FROM playback_history " +
            "JOIN media_items ON media_items.id = playback_history.mediaItemId " +
            "WHERE media_items.filePath = :path LIMIT 1"
    )
    suspend fun getByPath(path: String): PlaybackHistoryEntity?

    @Query(
        "SELECT position FROM playback_history " +
            "JOIN media_items ON media_items.id = playback_history.mediaItemId " +
            "WHERE media_items.filePath = :path LIMIT 1"
    )
    suspend fun getPositionByPath(path: String): Long?

    /** 全部播放进度（含时长），供媒体库网格画进度条；进度变化自动推送 */
    @Query(
        "SELECT media_items.filePath AS filePath, playback_history.position AS position, " +
            "media_items.duration AS duration " +
            "FROM playback_history JOIN media_items ON media_items.id = playback_history.mediaItemId"
    )
    fun observeAllProgress(): Flow<List<PlaybackProgress>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: PlaybackHistoryEntity): Long

    @Update
    suspend fun update(history: PlaybackHistoryEntity)

    @Query("DELETE FROM playback_history WHERE mediaItemId = :mediaItemId")
    suspend fun deleteByMediaItemId(mediaItemId: Long)
}

@Dao
interface UploadRecordDao {

    @Query("SELECT * FROM upload_records ORDER BY time DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<UploadRecordEntity>>

    @Query("SELECT * FROM upload_records WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): UploadRecordEntity?

    @Insert
    suspend fun insert(record: UploadRecordEntity): Long

    @Update
    suspend fun update(record: UploadRecordEntity)

    /** 仅删除历史日志，物理文件保留 */
    @Query("DELETE FROM upload_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM upload_records")
    suspend fun clearAll()
}
