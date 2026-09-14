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
 * - 播放状态（PlayerActivity 上报，播放中暂停服务器防"边播边传卡顿"；在途上传顺延至传完才停）
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
 * **访问码（Token）**：见 [tryStart] —— **会话内固定**：进程首次启动服务器时生成一次，
 * 此后停启（熄屏/播放暂停/休眠恢复）与改端口均沿用同一码，进程重启才轮换；
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
     * **会话内固定**：进程首次启动服务器时生成一次，此后停启与改端口均沿用同一码，
     * 进程重启（含开机自启重新拉起）才轮换。不随每次启停轮换的理由：熄屏/播放/休眠
     * 恢复都会走一轮 stop→start，若每次换码，「边看视频边让家人传文件」这类场景会把
     * 手机端反复打回输入访问码界面，体验远差于主流投屏/传输工具的「会话内记住配对」
     * 惯例；而码与 App 进程同生命周期，旧码仍会随进程死亡失效，安全性不打折。
     *
     * 服务器实例持有的是**构造时注入的那一份**，与本字段恒一致（不存在「显示的是新码、
     * 服务器认的是旧码」——沿用期间两者本就是同一个值）。
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
            // 服务器尚未启动，UI 不应展示访问码（码本身会话内固定，起服时重新发布）
            ServerBus.setToken(null)
            prefs = appContext!!.getSharedPreferences("server_policy", Context.MODE_PRIVATE)
            mode = prefs!!.getString("mode", null)
                ?.let { runCatching { ServerMode.valueOf(it) }.getOrNull() }
                ?: ServerMode.SMART
            ServerBus.setMode(mode)
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            // 任何上传事件（开始/结束）都视为活动，重置空闲计时；
            // 最后一个在途上传结束时补一次评估：把因「传输不中断」保护而暂缓的停服归位
            // （见 [apply]）。策略信号统一在主线程读取（与 setPort 同规），post 到主线程再判断。
            scope.launch {
                UploadBus.records.collect {
                    resetIdleTimer()
                    mainHandler.post {
                        if (ServerBus.running.value &&
                            UploadBus.records.value.none { it.state == UploadState.RUNNING } &&
                            !shouldRunNow()
                        ) {
                            evaluate()
                        }
                    }
                }
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
     * 切换会打断正在进行的上传连接，属预期行为；访问码**会话内固定、不随之轮换**，
     * 手机端在电视恢复监听后刷新即可继续（无需重新输入访问码）。
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
            // 停掉旧端口上的监听（存在的话）；访问码会话内固定，不受停启影响
            stopServer()

            var ok = true
            if (wantRun) {
                val started = tryStart(context, newPort)
                if (started != null) {
                    httpServer = started
                } else {
                    // 新端口起不来：尽力用旧端口恢复监听，避免「改端口把服务器改没了」
                    // （访问码会话内固定，恢复监听注入的仍是当前这枚码）
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
            if (httpServer == null) {
                httpServer = tryStart(context, port)
            }
        } else if (httpServer != null) {
            // 传输不中断保护（与空闲休眠的豁免同一条规则）：任何原因的停服
            // （熄屏/播放中/离开上传页）遇到在途上传都顺延，传完最后一个文件才停。
            // 暂缓的停服由 init 里对 UploadBus 的收集在最后一个上传结束时补评估归位。
            if (UploadBus.records.value.any { it.state == UploadState.RUNNING }) return
            stopServer()
        }
        publishState()
    }

    /**
     * 启动监听；端口被占用等失败情况返回 null。
     *
     * 访问码**会话内固定**（见 [token] 注释）：进程内首次启动生成一次，此后（含改端口
     * 停旧起新）沿用同一码；进程重启才轮换。生成顺序依旧关键：**先取码、再起服务，
     * 起成功才提交**——服务器实例拿到的是构造时注入的那一份，启动失败（端口占用）时
     * 不动已有状态，回滚到旧端口时注入的也是同一份码，不存在失配。
     */
    private fun tryStart(context: Context, targetPort: Int): TransHttpServer? {
        val code = token ?: generateToken()
        val started = runCatching {
            TransHttpServer(context, targetPort, code).also { it.start() }
        }.getOrNull() ?: return null
        token = code
        ServerBus.setToken(code)
        return started
    }

    /** 停止监听。访问码保留（会话内固定），下次启动沿用，手机端免重复输入；UI 侧置 null 隐藏码与二维码 */
    private fun stopServer() {
        runCatching { httpServer?.stop() }
        httpServer = null
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
