package com.hpu.transview.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 媒体索引表：数据库作为媒体库唯一可信索引源。
 * mediaType: 0=视频 1=图片 2=其他；duration 仅视频有意义（毫秒），其余为 0。
 */
@Entity(
    tableName = "media_items",
    indices = [Index(value = ["filePath"], unique = true)]
)
data class MediaItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val fileName: String,
    val mediaType: Int,
    val parentFolder: String,
    val fileSize: Long,
    val lastModified: Long,
    val duration: Long,
    val addedTime: Long
)

/**
 * 播放历史表：外键关联 MediaItem，级联删除（媒体记录删除时历史随之删除）。
 */
@Entity(
    tableName = "playback_history",
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaItemId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("mediaItemId")]
)
data class PlaybackHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaItemId: Long,
    val position: Long,
    val updatedTime: Long
)

/**
 * 上传记录表：仅作历史日志，删除记录不影响本地物理文件。
 * state: 0=等待中 1=上传中 2=成功 3=失败；category: 0=视频 1=图片 2=其他。
 */
@Entity(tableName = "upload_records")
data class UploadRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val fileSize: Long,
    val progress: Int,
    val state: Int,
    val category: Int,
    val time: Long
)
