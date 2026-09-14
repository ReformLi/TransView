package com.hpu.transview.server

import com.hpu.transview.model.ServerMode
import com.hpu.transview.ui.settings.SettingsStore
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

    /**
     * 当前监听端口（设置页改端口后立即更新）。
     *
     * 上传页的二维码与地址文本依赖它：改端口后若仍显示旧端口，手机会连到已关闭的端口，
     * 表现为「上传页看着正常但扫码打不开」。
     */
    private val _port = MutableStateFlow(SettingsStore.serverPort)
    val port: StateFlow<Int> = _port.asStateFlow()

    fun update(running: Boolean, hibernated: Boolean) {
        _running.value = running
        _hibernated.value = hibernated
    }

    fun setMode(mode: ServerMode) {
        _mode.value = mode
    }

    fun setPort(port: Int) {
        _port.value = port
    }
}
