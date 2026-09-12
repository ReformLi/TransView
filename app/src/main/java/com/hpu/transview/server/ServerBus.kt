package com.hpu.transview.server

import com.hpu.transview.model.ServerMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 服务器状态总线：ServerController（生产者）→ UI / 前台服务通知（消费者） */
object ServerBus {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** 智能模式 15 分钟无上传后的休眠标记（区别于播放/熄屏导致的暂停） */
    private val _hibernated = MutableStateFlow(false)
    val hibernated: StateFlow<Boolean> = _hibernated.asStateFlow()

    private val _mode = MutableStateFlow(ServerMode.SMART)
    val mode: StateFlow<ServerMode> = _mode.asStateFlow()

    fun update(running: Boolean, hibernated: Boolean) {
        _running.value = running
        _hibernated.value = hibernated
    }

    fun setMode(mode: ServerMode) {
        _mode.value = mode
    }
}
