package com.hpu.transview.server

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.hpu.transview.model.ServerMode
import com.hpu.transview.model.UploadState
import com.hpu.transview.ui.settings.SettingsStore
import com.hpu.transview.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * 服务器智能保活策略引擎（单一状态机）。
 *
 * 信号源：
 * - 屏幕开关（SCREEN_OFF/ON 广播，由 ServerService 转发）
 * - 播放状态（PlayerActivity 上报，播放中暂停服务器防"边播边传卡顿"）
 * - 上传页可见性（MainScreen 上报，省电模式离开页面即停）
 * - 上传活动（UploadBus 事件，重置空闲计时）
 * - 手动唤醒（上传页按钮）
 *
 * 模式（SharedPreferences 持久化，默认智能）：
 * - TURBO       极速：恒运行
 * - SMART       智能：未休眠 && 屏幕亮 && 非播放；15 分钟无上传自动休眠
 * - POWER_SAVER 省电：仅上传页内手动启动，离开上传页即停
 *
 * 对外唯一输出口是 ServerBus（running / hibernated / mode StateFlow），
 * UI 与前台服务通知只依赖总线，不持有引擎引用。
 */
object ServerController {

    /** 智能模式空闲休眠时长 */
    private const val IDLE_TIMEOUT_MS = 15 * 60 * 1000L

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null
    private var httpServer: TransHttpServer? = null
    private var initialized = false

    /** 当前监听端口：初始值来自设置项，运行中可经 setPort 切换 */
    private var port = SettingsStore.serverPort

    // —— 策略信号 ——
    private var mode = ServerMode.SMART
    private var screenOn = true
    private var playing = false
    private var uploadPageVisible = true
    private var manualWake = false
    private var hibernated = false

    /** 服务器启停串行执行器：评估与应用解耦，UI 线程零阻塞 */
    private val serverExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val idleRunnable: Runnable = Runnable {
        if (mode != ServerMode.SMART) return@Runnable
        val inFlight = UploadBus.records.value.any { it.state == UploadState.RUNNING }
        if (inFlight) {
            // 仍有上传进行中（如单个大文件传输超 15 分钟），顺延计时，绝不中断传输
            mainHandler.postDelayed(idleRunnable, IDLE_TIMEOUT_MS)
        } else {
            hibernated = true
            evaluate()
        }
    }

    /** 由 ServerService.onCreate 调用；幂等 */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            appContext = context.applicationContext
            // 端口/设备名等服务器配置统一由 SettingsStore 持久化（设置页写入），
            // 这里主动 init 一次，保证后台拉起（如开机自启）时也能读到用户配置
            SettingsStore.init(appContext!!)
            port = SettingsStore.serverPort
            ServerBus.setPort(port)
            prefs = appContext!!.getSharedPreferences("server_policy", Context.MODE_PRIVATE)
            mode = prefs!!.getString("mode", null)
                ?.let { runCatching { ServerMode.valueOf(it) }.getOrNull() }
                ?: ServerMode.SMART
            ServerBus.setMode(mode)
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            // 任何上传事件（开始/结束）都视为活动，重置空闲计时
            scope.launch {
                UploadBus.records.collect { resetIdleTimer() }
            }
        }
        evaluate()
    }

    /** 切换保活模式（设置对话框），立即生效并持久化 */
    fun setMode(newMode: ServerMode) {
        mode = newMode
        prefs?.edit()?.putString("mode", newMode.name)?.apply()
        ServerBus.setMode(newMode)
        if (newMode != ServerMode.SMART) {
            hibernated = false
            mainHandler.removeCallbacks(idleRunnable)
        }
        if (newMode == ServerMode.POWER_SAVER) manualWake = false
        evaluate()
    }

    /** 屏幕开关（ServerService 广播转发） */
    fun setScreenOn(on: Boolean) {
        if (screenOn == on) return
        screenOn = on
        evaluate()
    }

    /** 播放状态（PlayerActivity 上报） */
    fun setPlaying(p: Boolean) {
        if (playing == p) return
        playing = p
        evaluate()
    }

    /** 上传页可见性（MainScreen 上报；省电模式离开页面即停） */
    fun setUploadPageVisible(visible: Boolean) {
        if (uploadPageVisible == visible) return
        uploadPageVisible = visible
        if (!visible) manualWake = false
        evaluate()
    }

    /** 手动唤醒 / 启动（上传页按钮，<1 秒恢复监听） */
    fun wake() {
        when (mode) {
            ServerMode.TURBO -> {}
            ServerMode.SMART -> {
                hibernated = false
                evaluate()
            }
            ServerMode.POWER_SAVER -> {
                manualWake = true
                evaluate()
            }
        }
    }

    /** 由 ServerService.onDestroy 调用 */
    fun release() {
        mainHandler.removeCallbacks(idleRunnable)
        scope.cancel()
        serverExecutor.execute {
            runCatching { httpServer?.stop() }
            httpServer = null
            ServerBus.update(false, hibernated)
        }
        initialized = false
    }

    /**
     * 切换监听端口（设置页），立即生效并持久化。
     *
     * NanoHTTPD 的端口在构造时固定，必须**停旧起新**。整个过程在串行执行器里跑，
     * 结果经 [onResult]（主线程）回调：
     * - 成功：更新端口 + 写回设置项 + 刷新 [ServerBus]，上传页二维码/地址随即跟着变；
     * - 失败（几乎只有端口被占用）：用旧端口重新拉起，设置项保持不变，由 UI 提示用户。
     * 切换会打断正在进行的上传连接，属预期行为。
     */
    fun setPort(newPort: Int, onResult: (Boolean) -> Unit) {
        if (newPort !in Constants.PORT_RANGE) {
            mainHandler.post { onResult(false) }
            return
        }
        if (newPort == port) {
            mainHandler.post { onResult(true) }
            return
        }
        val previous = port
        // 与 evaluate() 一致：策略信号在主线程读取后传入执行器，避免跨线程读成员
        val wantRun = shouldRunNow()
        serverExecutor.execute {
            val context = appContext
            if (context == null) {
                mainHandler.post { onResult(false) }
                return@execute
            }
            // 停掉旧端口上的监听（存在的话）
            runCatching { httpServer?.stop() }
            httpServer = null

            var ok = true
            if (wantRun) {
                val started = startOn(context, newPort)
                if (started != null) {
                    httpServer = started
                } else {
                    // 新端口起不来：尽力用旧端口恢复监听，避免「改端口把服务器改没了」
                    ok = false
                    httpServer = startOn(context, previous)
                }
            }
            if (ok) {
                port = newPort
                SettingsStore.serverPort = newPort
                ServerBus.setPort(newPort)
            }
            publishState()
            mainHandler.post { onResult(ok) }
        }
    }

    private fun shouldRunNow(): Boolean = when (mode) {
        ServerMode.TURBO -> true
        ServerMode.SMART -> !hibernated && screenOn && !playing
        ServerMode.POWER_SAVER -> uploadPageVisible && manualWake
    }

    private fun evaluate() {
        val shouldRun = shouldRunNow()
        serverExecutor.execute { apply(shouldRun) }
    }

    /** 串行应用目标状态（后台执行器内），并同步总线与空闲计时 */
    private fun apply(run: Boolean) {
        val context = appContext ?: return
        if (run) {
            if (httpServer == null) {
                httpServer = startOn(context, port)
            }
        } else if (httpServer != null) {
            runCatching { httpServer?.stop() }
            httpServer = null
        }
        publishState()
    }

    /** 在指定端口上启动监听；端口被占用等失败情况返回 null（调用方决定回滚或保持停止） */
    private fun startOn(context: Context, targetPort: Int): TransHttpServer? =
        runCatching { TransHttpServer(context, targetPort).also { it.start() } }.getOrNull()

    /** 把当前启停状态同步到总线，并重排空闲休眠计时 */
    private fun publishState() {
        val running = httpServer != null
        ServerBus.update(running, hibernated)
        mainHandler.post {
            mainHandler.removeCallbacks(idleRunnable)
            if (running && mode == ServerMode.SMART) {
                mainHandler.postDelayed(idleRunnable, IDLE_TIMEOUT_MS)
            }
        }
    }

    private fun resetIdleTimer() {
        mainHandler.post {
            mainHandler.removeCallbacks(idleRunnable)
            if (ServerBus.running.value && mode == ServerMode.SMART) {
                mainHandler.postDelayed(idleRunnable, IDLE_TIMEOUT_MS)
            }
        }
    }
}
