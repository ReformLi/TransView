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

    /**
     * 当前访问码（6 位，A-Z + 0-9）。
     *
     * 生命周期与监听绑定：服务器**每次启动**（首次启动、休眠/暂停恢复、改端口重启）都会轮换一个新值，
     * 停止时置 null。上传页据此重画二维码（`http://ip:port/?token=xxxxxx`）并在屏幕上显示访问码。
     *
     * 唯一权威值在 [ServerController] 里（由它生成并注入服务器实例），总线只负责广播给 UI ——
     * 与 [port] / [ServerMode] 同套路（UI 只依赖总线，不持有引擎引用）。
     */
    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()

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

    fun setToken(token: String?) {
        _token.value = token
    }
}
