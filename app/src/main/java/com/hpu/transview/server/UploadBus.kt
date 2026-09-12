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

    private const val MAX_RECORDS = 8
    private val idGen = AtomicLong(0)

    private val _records = MutableStateFlow<List<UploadRecord>>(emptyList())
    val records: StateFlow<List<UploadRecord>> = _records.asStateFlow()

    /** 上传开始，返回记录 id */
    fun start(name: String, size: Long): Long {
        val id = idGen.incrementAndGet()
        _records.update { current ->
            (listOf(UploadRecord(id, name, size, UploadState.RUNNING, System.currentTimeMillis())) + current)
                .take(MAX_RECORDS)
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
