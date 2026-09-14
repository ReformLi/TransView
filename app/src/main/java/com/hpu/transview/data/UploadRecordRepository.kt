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
 *
 * 防死数据两道闸：
 * - [insert] 后自动裁剪：表只保留最近 [MAX_RECORDS] 条（UI 最多显示 200 条，
 *   200 名之外的行永远不会被查询到，留着只会无限膨胀）；
 * - [reapZombieRunning]：进程被杀后残留的「上传中」记录统一标失败（见方法注释）。
 */
class UploadRecordRepository(context: Context) {

    /** 表内保留条数上限（UI observeRecent 只取 200，多留余量供翻查） */
    private companion object {
        const val MAX_RECORDS = 500
    }

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
        val id = dao.insert(
            UploadRecordEntity(
                fileName = fileName,
                fileSize = fileSize,
                progress = progress,
                state = state,
                category = category,
                time = System.currentTimeMillis()
            )
        )
        dao.trimTo(MAX_RECORDS)
        id
    }

    suspend fun updateState(id: Long, state: Int, progress: Int) =
        withContext(Dispatchers.IO) {
            dao.updateState(id, state, progress)
        }

    /** 仅在记录仍处「上传中」时回写进度（进度监视专用）：迟到的进度回写不得覆盖最终状态 */
    suspend fun updateProgressIfRunning(id: Long, progress: Int) =
        withContext(Dispatchers.IO) {
            dao.updateProgressIfRunning(id, progress, UploadStateCode.RUNNING)
        }

    /** 删除单条记录（本地文件保留） */
    suspend fun deleteById(id: Long) = withContext(Dispatchers.IO) { dao.deleteById(id) }

    suspend fun clearAll() = withContext(Dispatchers.IO) { dao.clearAll() }

    /**
     * 清扫僵尸「上传中」记录（统一标失败）。进程被杀 / 断电时，请求线程的兜底收尾
     * 没机会执行，DB 里会留下永远停在「上传中 xx%」的记录；新进程启动后不可能还有
     * 上个生命周期的请求在跑，App 启动对账（SyncManager）时调用一次即可归位。
     * @return 被修正的记录数
     */
    suspend fun reapZombieRunning(): Int = withContext(Dispatchers.IO) {
        dao.updateStateByState(UploadStateCode.RUNNING, UploadStateCode.FAILED)
    }
}
