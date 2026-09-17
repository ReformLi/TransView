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
    version = 1,
    exportSchema = true
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
                    // 封板约定：从 v1 起，任何 schema 变更必须显式写 Migration；
                    // 仅在降级（装了高版本又装回低版本，异常情况）时允许清库重建。
                    // 禁止使用无参数的 fallbackToDestructiveMigration()，
                    // 避免升级时静默清空用户数据。
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { instance = it }
            }
    }
}