package com.hpu.transview.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hpu.transview.MainActivity
import com.hpu.transview.R
import com.hpu.transview.model.ServerMode
import com.hpu.transview.server.ServerBus
import com.hpu.transview.server.ServerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 前台服务：作为 ServerController（智能保活策略引擎）的常驻宿主。
 * - 接收 SCREEN_OFF/ON 广播并转发给策略引擎
 * - 通知文案随服务器状态联动（运行中/已暂停/已休眠/已停止），点击回到 App
 */
class ServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> ServerController.setScreenOn(false)
                Intent.ACTION_SCREEN_ON -> ServerController.setScreenOn(true)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat(
            buildNotification(getString(R.string.notif_title), getString(R.string.notif_text))
        )
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenReceiver, filter)
        }
        ServerController.init(this)
        // 服务器状态 → 通知文案联动
        scope.launch {
            combine(
                ServerBus.running, ServerBus.hibernated, ServerBus.mode
            ) { running, hibernated, mode -> Triple(running, hibernated, mode) }
                .collect { (running, hibernated, mode) ->
                    updateNotification(running, hibernated, mode)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        scope.cancel()
        ServerController.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification(running: Boolean, hibernated: Boolean, mode: ServerMode) {
        val title: String
        val text: String
        if (running) {
            title = getString(R.string.notif_title)
            text = getString(R.string.notif_text)
        } else if (hibernated) {
            title = "文件服务器已休眠"
            text = "智能模式：15 分钟无上传自动休眠，在「上传」页点击唤醒"
        } else if (mode == ServerMode.POWER_SAVER) {
            title = "文件服务器已停止"
            text = "省电模式：在「上传」页点击启动"
        } else {
            title = "文件服务器已暂停"
            text = "播放视频或屏幕休眠期间暂停接收，结束后自动恢复"
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_server)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "file_server"
        const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, ServerService::class.java)
            )
        }
    }
}
