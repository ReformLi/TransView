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
import java.security.SecureRandom
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
 * 对外唯一输出口是 ServerBus（running / hibernated / mode / port / token StateFlow），
 * UI 与前台服务通知只依赖总线，不持有引擎引用。
 *
 * **访问码（Token）**：见 [tryStart] —— 与监听同生命周期，起则轮换、停则销毁，
 * 生成后注入 [TransHttpServer] 实例，服务器据此拦截未授权的上传请求。
 */
object ServerController {

    /** 智能模式空闲休眠时长 */
    private const val IDLE_TIMEOUT_MS = 15 * 60 * 1000L

    /** 访问码字符集：大写字母 + 数字（需求固定，不做易混字符剔除） */
    private const val TOKEN_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    /** 访问码长度固定 6 位 */
    private const val TOKEN_LENGTH = 6

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null
    private var httpServer: TransHttpServer? = null
    private var initialized = false

    /** 当前监听端口：初始值来自设置项，运行中可经 setPort 切换 */
    private var port = SettingsStore.serverPort

    /**
     * 当前访问码（内存状态，不持久化）。
     *
     * 每次 [tryStart] 成功都轮换；[stopServer] 置 null。服务器实例持有的是**构造时注入的那一份**，
     * 因此本字段始终与实际在跑的监听一致（不会出现「显示的是新码、服务器认的是旧码」）。
     */
    private var token: String? = null

    /** 访问码用密码学随机源：局域网门槛虽低，也没必要让码可被线性预测 */
    private val secureRandom = SecureRandom()

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
            // 服务器尚未启动，此时不应存在有效访问码
            ServerBus.setToken(null)
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
            stopServer()
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
     * 切换会打断正在进行的上传连接，属预期行为；由于是「停旧起新」，**访问码也会随之轮换**
     * （用户需重新扫码或重新输入），与「服务器每次启动轮换」的约定一致。
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
            // 停掉旧端口上的监听（存在的话），并销毁旧访问码
            stopServer()

            var ok = true
            if (wantRun) {
                val started = tryStart(context, newPort)
                if (started != null) {
                    httpServer = started
                } else {
                    // 新端口起不来：尽力用旧端口恢复监听，避免「改端口把服务器改没了」
                    // （此时访问码由 tryStart 重新生成一次，与服务实例一一对应）
                    ok = false
                    httpServer = tryStart(context, previous)
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
            // 从休眠 / 暂停 / 熄屏中恢复也走这里 → 视为「一次启动」，访问码随之轮换
            if (httpServer == null) {
                httpServer = tryStart(context, port)
            }
        } else if (httpServer != null) {
            stopServer()
        }
        publishState()
    }

    /**
     * 启动监听 + 轮换访问码；端口被占用等失败情况返回 null。
     *
     * 生成顺序很关键：**先建码、再起服务，起成功才提交**。这样：
     * ① 服务器实例拿到的是构造时注入的那一份，后续 `httpServer` 不会再变，不存在「运行中换码」的竞态；
     * ② 启动失败（端口占用）时不动已有状态，回滚到旧端口也能拿到一份与实例匹配的新码。
     */
    private fun tryStart(context: Context, targetPort: Int): TransHttpServer? {
        val fresh = generateToken()
        val started = runCatching {
            TransHttpServer(context, targetPort, fresh).also { it.start() }
        }.getOrNull() ?: return null
        token = fresh
        ServerBus.setToken(fresh)
        return started
    }

    /** 停止监听并销毁访问码（码与监听同生命周期：停则失效，下次启动重新生成） */
    private fun stopServer() {
        runCatching { httpServer?.stop() }
        httpServer = null
        token = null
        ServerBus.setToken(null)
    }

    /** 生成一个 6 位访问码：字符集 A-Z + 0-9，密码学随机源 */
    private fun generateToken(): String {
        val sb = StringBuilder(TOKEN_LENGTH)
        repeat(TOKEN_LENGTH) { sb.append(TOKEN_CHARS[secureRandom.nextInt(TOKEN_CHARS.length)]) }
        return sb.toString()
    }

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
