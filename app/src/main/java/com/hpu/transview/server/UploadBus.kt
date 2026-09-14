package com.hpu.transview.server

import com.hpu.transview.model.UploadRecord
import com.hpu.transview.model.UploadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * 上传事件总线：HTTP 服务器（生产者）→ TV 界面（消费者）。
 * 进程内单例，解耦 server 层与 ui 层。
 */
object UploadBus {

    /** 已结束记录的保留条数（RUNNING 一律保留，不受此限） */
    private const val MAX_RECORDS = 8
    private val idGen = AtomicLong(0)

    private val _records = MutableStateFlow<List<UploadRecord>>(emptyList())
    val records: StateFlow<List<UploadRecord>> = _records.asStateFlow()

    /** 上传开始，返回记录 id */
    fun start(name: String, size: Long): Long {
        val id = idGen.incrementAndGet()
        _records.update { current ->
            val next = listOf(
                UploadRecord(id, name, size, UploadState.RUNNING, System.currentTimeMillis())
            ) + current
            // 只裁剪已结束的记录：ServerController 的空闲休眠判定靠 RUNNING 记录识别
            // 「仍有上传在途」，多台手机并发时把最老的 RUNNING 挤出去会让大文件传输
            // 被 15 分钟休眠误伤（浏览器单标签页串行上传不受影响，多端并发会踩中）
            var keptFinished = 0
            next.filter { record ->
                record.state == UploadState.RUNNING || ++keptFinished <= MAX_RECORDS
            }
        }
        return id
    }

    /** 上传结束（成功/失败） */
    fun finish(id: Long, success: Boolean) {
        _records.update { current ->
            current.map {
                if (it.id == id) it.copy(state = if (success) UploadState.DONE else UploadState.FAILED) else it
            }
        }
    }
}
