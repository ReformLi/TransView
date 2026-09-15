package com.hpu.transview

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.memory.MemoryCache
import com.hpu.transview.data.sync.SyncManager
import com.hpu.transview.ui.settings.SettingsStore
import com.hpu.transview.util.CrashLogger
import com.hpu.transview.util.FileLocations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 应用入口：配置 Coil 图片加载器（含视频首帧解码）；启动即触发数据库-文件对账 */
class TransViewApp : Application(), ImageLoaderFactory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // 崩溃日志落盘：电视端拿不到 logcat，「一闪就退出」只能靠这份文件定位。
        // 放在最前面，越早装越不会漏掉启动期的崩溃。
        runCatching { CrashLogger.install(this) }
        // 设置项存储尽早初始化：端口 / 设备名 / 开机自启等配置在后台拉起（BootReceiver）
        // 与服务器启动时都要读取，不能依赖某个页面先组合（幂等，重复调用无副作用）
        SettingsStore.init(this)
        // 存储状态首次检测（须在 SettingsStore 之后：首选存储是设置项）。
        // 覆盖边界场景：App 在降级期间被关闭，下次启动时若U盘仍不在位 → 继续降级；
        // U盘已插回 → 状态自动恢复为U盘。此后的插拔由 ServerService 的广播监听接管。
        runCatching { FileLocations.init(this) }
        // 启动对账（IO 线程，绝不阻塞主线程）；正在同步时 sync() 内部互斥直接返回
        appScope.launch {
            runCatching { SyncManager.getInstance(this@TransViewApp).sync() }
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
            .crossfade(true)
            .build()
}
