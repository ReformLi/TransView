package com.hpu.transview

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.memory.MemoryCache
import com.hpu.transview.data.sync.SyncManager
import com.hpu.transview.server.TransHttpServer
import com.hpu.transview.ui.settings.SettingsStore
import com.hpu.transview.util.AppLogger
import com.hpu.transview.util.CrashLogger
import com.hpu.transview.util.FileLocations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 应用入口：配置 Coil 图片加载器（含视频首帧解码）；启动即触发数据库-文件对账 */
class TransViewApp : Application(), ImageLoaderFactory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        private const val TAG = "TransViewApp"
    }

    override fun onCreate() {
        super.onCreate()
        // 崩溃日志落盘：电视端拿不到 logcat，「一闪就退出」只能靠这份文件定位。
        // 放在最前面，越早装越不会漏掉启动期的崩溃。失败**先把异常记下**，
        // 等下面日志引擎启动后再补写 —— 此刻 AppLogger 尚未启用，直接写只会进 logcat。
        val crashLoggerError = runCatching { CrashLogger.install(this) }.exceptionOrNull()
        // 设置项存储尽早初始化：端口 / 设备名 / 开机自启等配置在后台拉起（BootReceiver）
        // 与服务器启动时都要读取，不能依赖某个页面先组合（幂等，重复调用无副作用）
        SettingsStore.init(this)
        // 存储状态首次检测（须在 SettingsStore 之后：首选存储是设置项）。
        // 覆盖边界场景：App 在降级期间被关闭，下次启动时若U盘仍不在位 → 继续降级；
        // U盘已插回 → 状态自动恢复为U盘。此后的插拔由 ServerService 的广播监听接管。
        runCatching { FileLocations.init(this) }
        // 清掉上一轮生命周期遗留的上传临时文件（`Android/data/<包名>/files/upload_tmp/`）。
        // 进程刚起 ⇒ 本进程内不可能有在途上传，遗留的必是死文件，故保护窗口取 0。
        // 必须有人清：该目录不在媒体沙盒内、Android 11+ 也对文件管理器不可见，
        // 否则「上传途中断电 / 进程被杀」留下的半个大文件会永久占着磁盘且无人可见。
        val orphanTemps = runCatching {
            TransHttpServer.purgeOrphanUploadTemps(this, skipActiveWithinMs = 0L)
        }.getOrDefault(0)
        // App 调试日志（默认关）：按持久化偏好决定是否启动异步落盘引擎。
        // 必须放在 FileLocations.init **之后** —— 日志落点 = 活动沙盒的 Downloads/app_log/，
        // 依赖活动存储状态（U 盘 / 内部存储）已检测完成；否则首次会落到兜底路径。
        runCatching { AppLogger.setEnabled(SettingsStore.appLogEnabled) }
        // 详细日志（默认关）：开启后额外落盘 V 级。同样须在 FileLocations.init 之后
        runCatching { AppLogger.setDetailed(SettingsStore.detailedLogEnabled) }
        AppLogger.i(
            TAG,
            "App 启动：v${BuildConfig.VERSION_NAME}，" +
                "日志=${if (AppLogger.isEnabled) "开" else "关"}" +
                (if (AppLogger.isEnabled && AppLogger.isDetailed) "（详细）" else "")
        )
        // 启动期兜底项的落盘记录（刻意放在引擎启动**之后**，保证写进文件而不止 logcat）
        crashLoggerError?.let { AppLogger.w(TAG, "崩溃日志安装失败，崩溃兜底可能不可用", it) }
        if (orphanTemps > 0) AppLogger.d(TAG, "启动清理上传残留：$orphanTemps 个")
        // 启动对账（IO 线程，绝不阻塞主线程）；正在同步时 sync() 内部互斥直接返回
        appScope.launch {
            runCatching { SyncManager.getInstance(this@TransViewApp).sync() }
                .onFailure { AppLogger.w(TAG, "启动对账失败", it) }
        }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            // 限制并发解码数：滚动时若图片/视频帧解码全速并发，CPU 被占满会掉帧；
            // 限到 2 路既不会卡主线程，缩略图也能陆续补齐（已解码的走内存缓存，回滚不重解）。
            .dispatcher(Dispatchers.IO.limitedParallelism(2))
            // 关闭 crossfade：列表快速滚动时每张新图的淡入过渡在低端盒子上会造成可见卡顿，
            // 改为占位色瞬时切换，视觉更跟手（与「上传页」纯文本列表的丝滑观感一致）。
            .crossfade(false)
            .build()
}
