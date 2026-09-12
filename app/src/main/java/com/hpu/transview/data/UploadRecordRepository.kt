package com.hpu.transview.data

import android.content.Context
import com.hpu.transview.data.db.AppDatabase
import com.hpu.transview.data.db.UploadRecordDao
import com.hpu.transview.data.db.UploadRecordEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** 上传状态常量（数据库存储值） */
object UploadStateCode {
    const val WAITING = 0
    const val RUNNING = 1
    const val SUCCESS = 2
    const val FAILED = 3
}

/**
 * 上传记录仓储：纯历史日志（删除记录不触碰物理文件）。
 * HTTP 服务器收到请求时 insert(等待/上传中)，落盘完成后 update(成功/失败)。
 */
class UploadRecordRepository(context: Context) {

    private val dao: UploadRecordDao = AppDatabase.getInstance(context).uploadRecordDao()

    fun observeRecent(limit: Int = 200): Flow<List<UploadRecordEntity>> =
        dao.observeRecent(limit)

    suspend fun insert(
        fileName: String,
        fileSize: Long,
        category: Int,
        state: Int = UploadStateCode.RUNNING,
        progress: Int = 0
    ): Long = withContext(Dispatchers.IO) {
        dao.insert(
            UploadRecordEntity(
                fileName = fileName,
                fileSize = fileSize,
                progress = progress,
                state = state,
                category = category,
                time = System.currentTimeMillis()
            )
        )
    }

    suspend fun updateState(id: Long, state: Int, progress: Int) =
        withContext(Dispatchers.IO) {
            dao.getById(id)?.let {
                dao.update(it.copy(state = state, progress = progress))
            }
        }

    /** 删除单条记录（本地文件保留） */
    suspend fun deleteById(id: Long) = withContext(Dispatchers.IO) { dao.deleteById(id) }

    suspend fun clearAll() = withContext(Dispatchers.IO) { dao.clearAll() }
}
