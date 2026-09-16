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

    @Query("SELECT * FROM media_items")
    suspend fun getAll(): List<MediaItemEntity>

    @Query("SELECT filePath FROM media_items")
    suspend fun getAllPaths(): List<String>

    @Query("SELECT * FROM media_items WHERE mediaType = :type")
    fun observeByType(type: Int): Flow<List<MediaItemEntity>>

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

    /** 清空全部播放历史（媒体索引保留） */
    @Query("DELETE FROM playback_history")
    suspend fun clearAll()
}

@Dao
interface UploadRecordDao {

    /**
     * 列表排序：进行中（等待 0 / 上传中 1）置顶，成功 2 / 失败 3 沉底；
     * 组内按上传时间倒序（新的在上），同秒以 id 稳定排序。
     * (state <= 1) 为 1/0 布尔值，DESC 让进行中分组排最前。
     */
    @Query(
        "SELECT * FROM upload_records " +
            "ORDER BY (state <= 1) DESC, time DESC, id DESC LIMIT :limit"
    )
    fun observeRecent(limit: Int = 200): Flow<List<UploadRecordEntity>>

    @Insert
    suspend fun insert(record: UploadRecordEntity): Long

    /** 直接落状态与进度（不经读-改-写，避免并发覆盖整条记录） */
    @Query("UPDATE upload_records SET state = :state, progress = :progress WHERE id = :id")
    suspend fun updateState(id: Long, state: Int, progress: Int)

    /** 仅在记录仍处「上传中」时回写进度：迟到的进度监视不得覆盖已写入的最终状态（成功/失败） */
    @Query("UPDATE upload_records SET progress = :progress WHERE id = :id AND state = :runningState")
    suspend fun updateProgressIfRunning(id: Long, progress: Int, runningState: Int)

    /**
     * 把所有「上传中」记录批量改为指定状态（失败）。进程被杀 / 断电后，上一生命周期
     * 的在途上传记录会永久卡在「上传中 xx%」（请求线程的兜底收尾没机会跑）；
     * 新进程启动后不可能还有上个生命周期的请求活着，对账时统一标失败。
     * @return 影响行数
     */
    @Query("UPDATE upload_records SET state = :toState WHERE state = :fromState")
    suspend fun updateStateByState(fromState: Int, toState: Int): Int

    /** 只保留最近 [keep] 条（time 同秒时以 id 稳定排序），其余删除——历史日志防无限增长 */
    @Query(
        "DELETE FROM upload_records WHERE id NOT IN " +
            "(SELECT id FROM upload_records ORDER BY time DESC, id DESC LIMIT :keep)"
    )
    suspend fun trimTo(keep: Int)

    /** 仅删除历史日志，物理文件保留 */
    @Query("DELETE FROM upload_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM upload_records")
    suspend fun clearAll()
}
