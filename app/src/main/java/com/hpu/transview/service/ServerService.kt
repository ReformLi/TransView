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
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hpu.transview.MainActivity
import com.hpu.transview.R
import com.hpu.transview.data.sync.SyncManager
import com.hpu.transview.model.ServerMode
import com.hpu.transview.server.ServerBus
import com.hpu.transview.server.ServerController
import com.hpu.transview.util.FileLocations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 前台服务：作为 ServerController（智能保活策略引擎）的常驻宿主。
 * - 接收 SCREEN_OFF/ON 广播并转发给策略引擎
 * - 接收 U盘插拔广播（去抖）→ 重检活动存储（降级/恢复）→ 重新对账
 * - 通知文案随服务器状态联动（运行中/已暂停/已休眠/已停止），点击回到 App
 */
class ServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 前台服务是否成功建立；未建立时 onCreate 早退，onDestroy 也不再释放控制器 */
    private var foregroundReady = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> ServerController.setScreenOn(false)
                Intent.ACTION_SCREEN_ON -> ServerController.setScreenOn(true)
            }
        }
    }

    /**
     * U盘插拔监听：ATTACHED/DETACHED 广播只作触发信号，去抖后统一重检
     * [FileLocations]（活动存储降级/恢复），再重新对账媒体库。
     */
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> scheduleUsbRefresh()
            }
        }
    }

    /** 去抖任务句柄：插拔瞬间系统会连发多个广播，只保留最后一次 */
    private var usbRefreshJob: Job? = null

    /**
     * 去抖重检（2 秒收口）：
     * - 插入时U盘卷（/storage/XXXX-XXXX）挂载完成需要时间，立即检测会漏；
     * - 拔出时多个广播连续到达，收口成一次；
     * - 活动存储切换（降级/恢复）后媒体库内容随之变化，需要重新对账
     *   （SyncManager 内部按当前活动存储决定是否跳过「同步外部删除」，互斥防重入）。
     */
    private fun scheduleUsbRefresh() {
        usbRefreshJob?.cancel()
        usbRefreshJob = scope.launch {
            delay(USB_REFRESH_DEBOUNCE_MS)
            // 重检含文件系统探测（/storage 列卷 + canWrite），放 IO 线程
            withContext(Dispatchers.IO) { FileLocations.refresh() }
            runCatching { SyncManager.getInstance(applicationContext).sync() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notif = buildNotification(getString(R.string.notif_title), getString(R.string.notif_text))
        foregroundReady = startForegroundCompat(notif)
        if (!foregroundReady) {
            // 前台服务被系统拒绝（典型：Android 15+ 从 BOOT_COMPLETED 启动受限类型）。
            // 必须在这里自行收摊：若让异常冒到 ActivityThread，进程会 FATAL 崩溃，
            // 而 START_STICKY 又会让 AMS 反复重启服务 → 开机崩溃循环（实测踩过）。
            stopSelf()
            return
        }
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
        // U盘插拔广播（系统广播，NOT_EXPORTED 亦可接收）：降级/恢复的运行期触发源
        val usbFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, usbFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, usbFilter)
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
        usbRefreshJob?.cancel()
        if (foregroundReady) {
            runCatching { unregisterReceiver(screenReceiver) }
            runCatching { unregisterReceiver(usbReceiver) }
            ServerController.release()
        }
        scope.cancel()
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
            text = "智能模式：15 分钟无上传自动休眠，亮屏或打开应用自动唤醒"
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

    /**
     * 建立前台服务；失败返回 false（调用方负责 stopSelf）。
     *
     * 类型按版本选择（见 Manifest 中 service 的注释）：
     * - API 34+ → `specialUse`：Android 15 起禁止 `dataSync` 从 BOOT_COMPLETED 启动，且
     *   `dataSync` 在 Android 15 上有 6 小时/天的时长上限，都不适合常驻的局域网服务器；
     * - API 29~33 → `dataSync`：这些版本没有 `specialUse`，也不存在上述限制；
     * - API < 29 → 不带类型。
     */
    private fun startForegroundCompat(notification: Notification): Boolean = try {
        when {
            Build.VERSION.SDK_INT >= 34 -> startForeground(
                NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            Build.VERSION.SDK_INT >= 29 -> startForeground(
                NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            else -> startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (t: Throwable) {
        Log.e(TAG, "startForeground 失败，前台服务无法建立，主动停止", t)
        false
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
        private const val TAG = "ServerService"
        const val CHANNEL_ID = "file_server"
        const val NOTIFICATION_ID = 1001

        /** U盘插拔重检去抖窗口：等U盘卷挂载稳定，也收口连续广播 */
        private const val USB_REFRESH_DEBOUNCE_MS = 2_000L

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, ServerService::class.java)
            )
        }
    }
}
