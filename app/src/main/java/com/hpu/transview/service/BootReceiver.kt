package com.hpu.transview.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hpu.transview.ui.settings.SettingsStore

/**
 * 开机自启（设置 → 服务器与网络 → 开机自启）。
 *
 * 系统开机完成（`BOOT_COMPLETED`）且用户已开启该开关时，拉起 [ServerService]
 * 常驻前台服务；是否真正开始监听仍由 ServerController 的保活策略决定
 * （极速=立即监听；智能=立即监听、屏幕灭/播放时暂停；省电=等上传页手动启动）。
 *
 * 边界说明：
 * - 应用首次安装后必须先被手动打开过一次，否则处于 stopped 状态收不到该广播（Android 3.1+ 系统限制）；
 * - Android 15（API 35）起禁止 `dataSync` 类型前台服务从 BOOT_COMPLETED 启动
 *   （抛 `ForegroundServiceStartNotAllowedException`）。因此 [ServerService] 在 API 34+ 上改用
 *   `specialUse` 类型声明前台服务（该类型不在受限清单内），此处无需特殊分支。
 * - 注意：该异常是在**服务内部** `startForeground()` 时抛出的，不是 `startForegroundService()` 的
 *   调用点——所以这里的 `runCatching` 兜不住它，真正的兜底在 ServerService.onCreate 里。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        // Application.onCreate 已初始化过，这里再调一次是幂等保护（进程可能由广播直接拉起）
        SettingsStore.init(context)
        if (!SettingsStore.bootAutostart) return
        Log.i(TAG, "开机自启已开启，拉起文件服务器前台服务")
        runCatching { ServerService.start(context) }
            .onFailure { Log.w(TAG, "开机自启启动前台服务失败（系统限制？）：${it.message}") }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
