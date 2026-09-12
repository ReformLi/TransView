package com.hpu.transview.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        MediaItemEntity::class,
        PlaybackHistoryEntity::class,
        UploadRecordEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun mediaItemDao(): MediaItemDao
    abstract fun playbackHistoryDao(): PlaybackHistoryDao
    abstract fun uploadRecordDao(): UploadRecordDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "transview.db"
                )
                    // v1（path 主键的旧播放历史）→ v2（MediaItem 外键体系）schema 不兼容；
                    // 开发期直接重建，播放历史会清空一次
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
