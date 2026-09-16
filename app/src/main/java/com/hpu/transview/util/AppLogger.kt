package com.hpu.transview.util

import android.os.Environment
import android.util.Log
import com.hpu.transview.model.Category
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App 运行日志本地化（设置 → 存储与数据 → 「App 调试日志」开关，**默认关闭**）。
 *
 * ## 为什么要有它
 * 电视端没有终端、拿不到 logcat。「崩溃」由 [CrashLogger] 兜住，但对账、服务器、播放器、上传
 * 这些**非崩溃**路径过去完全无据可查。本类把这些日志**常态化异步落盘**，用户可在 TV 上
 * 「其他」页直接翻看 app_log 目录。
 *
 * ## 落盘结构
 * ```
 * <活动沙盒>/TransView/Downloads/app_log/
 *     └── 2026-09-16/                ← 按天分目录（yyyy-MM-dd）
 *             ├── 14-30-05.log       ← 启动会话的首个文件（HH-mm-ss）
 *             ├── 14-30-05_1.log     ← 单文件写满 2MB 后按序号切割
 *             └── 14-30-05_2.log
 * ```
 * 因为位于 `Downloads/` 下，`app_log/` 会**严格按方案 A** 被当作「其他」分类扫描入库
 * （`.log` 既非视频也非图片 → mediaType=OTHER），于是 TV 端「其他」页能直接看到并打开。
 *
 * ## 防卡顿（核心设计）
 * **绝不在调用点写磁盘**。[d]/[i]/[w]/[e] 只做两件事：① 调一次原生 `Log`（保留 logcat
 * 输出，行为与改造前一致）；② 把整行文本 `trySend` 进 [Channel] 无锁队列后立刻返回——
 * 队列满就**丢弃**该行，绝不阻塞业务线程。
 * 真正的写盘在专用 `CoroutineScope(Dispatchers.IO)` 里进行：`BufferedWriter` 缓冲，
 * **凑满 4KB 或空闲满 1 秒才 flush**，严禁每条日志都 flush（否则电视端存储 IO 会被打满）。
 *
 * ## 切割与清理
 * 单文件上限 [MAX_FILE_BYTES]（2MB），写满立即关流、同日目录下按 `_1`/`_2` 递增新建；
 * 引擎启动时清理 [KEEP_DAYS] 天之前的日期目录（**只删 `app_log/` 下的 `yyyy-MM-dd` 目录**，
 * 且必须通过 `FileLocations.isInsideSandbox` 断言，绝不影响 Downloads 下的其他文件）。
 *
 * ## 兜底与边界
 * - 所有内部操作 `runCatching` 包裹：磁盘满 / 权限不足 / IO 异常一律**静默丢弃日志**，
 *   异常绝不冒泡到业务代码（日志绝不能把 App 搞崩）。
 * - **U 盘拔出的降级**：写失败即关流并清缓存，下一轮重新解析落点；同时（限流）触发一次
 *   `FileLocations.refresh()`，活动存储自动降级为内部存储 → 日志无缝续写到内部沙盒。
 * - 开关关闭：`enabled=false` 后调用点零成本返回；消费协程被取消并在 IO 线程 flush/close
 *   文件流；内存队列立即排空。
 * - 写盘强制 `Charsets.UTF_8`，中文日志不乱码。
 *
 * ## 与 CrashLogger 的关系
 * [CrashLogger] **刻意不接入本类**：它运行在「未捕获异常」路径上，硬约束是不触碰
 * `FileLocations`/Room（那可能正是崩溃源头），因此继续直接用原生 `android.util.Log`。
 */
object AppLogger {

    /** 日志根目录名（位于「其他」分类目录下）：`TransView/Downloads/app_log/` */
    private const val DIR_NAME = "app_log"

    /** 沙盒目录名（与 [FileLocations] 保持一致；那边是私有常量，这里独立声明） */
    private const val SANDBOX_DIR_NAME = "TransView"

    /** 单文件大小上限：2MB（电视端文本文件不宜过大，否则读取/渲染会卡） */
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024

    /** 满缓冲阈值：累计 4KB 即 flush */
    private const val FLUSH_BYTES = 4 * 1024

    /** 空闲定时 flush 间隔：1 秒（严禁每条都 flush） */
    private const val FLUSH_INTERVAL_MS = 1000L

    /** 空闲轮询步长：队列空时轻量休眠，不做任何 IO（避免 `Channel.receive` + 超时取消丢元素） */
    private const val IDLE_POLL_MS = 120L

    /** 保留最近 N 天的日期目录 */
    private const val KEEP_DAYS = 7

    /** 队列容量：满了直接丢弃（绝不阻塞调用方） */
    private const val QUEUE_CAPACITY = 4096

    /** 写失败时触发存储重检的限流间隔（`FileLocations.refresh()` 内部有写探针，不能频繁调） */
    private const val RECOVERY_INTERVAL_MS = 30_000L

    /** 日期目录名严格校验（历史清理的二次保险：只认 yyyy-MM-dd） */
    private val DAY_RE = Regex("""\d{4}-\d{2}-\d{2}""")

    /** 开关状态（组合期由 TransViewApp / 设置页写入；调用点只读，避免每行都读 SharedPreferences） */
    @Volatile
    private var enabled = false

    /** 无锁队列（协程 Channel）。调用方只 `trySend`，永不阻塞 */
    private val queue = Channel<String>(QUEUE_CAPACITY)

    /** 消费协程作用域（关闭时置 null） */
    private var scope: CoroutineScope? = null

    /** 时间戳格式化器非线程安全 → 调用点可能来自任意线程，加锁串行化 */
    private val tsLock = Any()
    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** 上次写失败触发的存储重检时间（限流用） */
    @Volatile
    private var lastRecoveryAt = 0L

    val isEnabled: Boolean get() = enabled

    // ————————————————————— 对外 API —————————————————————

    /**
     * 开关（幂等）。**须在 `FileLocations.init` 之后调用**（落点依赖活动存储状态）。
     * App 启动时由 [com.hpu.transview.TransViewApp] 按持久化偏好调用一次，
     * 设置页切换开关时调用一次。
     */
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        runCatching { if (value) start() else stop() }
    }

    fun d(tag: String, msg: String) = write('D', tag, msg, null)

    fun i(tag: String, msg: String) = write('I', tag, msg, null)

    fun w(tag: String, msg: String) = write('W', tag, msg, null)

    fun w(tag: String, msg: String, t: Throwable?) = write('W', tag, msg, t)

    fun e(tag: String, msg: String) = write('E', tag, msg, null)

    fun e(tag: String, msg: String, t: Throwable?) = write('E', tag, msg, t)

    /**
     * 设置页提示用：当前日志目录的人类可读位置
     * （内部存储 = `内部存储/TransView/Downloads/app_log/`；U 盘 = `U 盘（盘名）/TransView/Downloads/app_log/`）。
     */
    fun logDirLabel(): String = runCatching {
        val st = FileLocations.storageState.value
        val rel = "$SANDBOX_DIR_NAME/Downloads/$DIR_NAME/"
        if (st.activeIsRemovable) "U 盘（${st.activeLabel}）/$rel" else "内部存储/$rel"
    }.getOrDefault("内部存储/$SANDBOX_DIR_NAME/Downloads/$DIR_NAME/")

    // ————————————————————— 写入路径（调用方线程） —————————————————————

    private fun write(level: Char, tag: String, msg: String, t: Throwable?) {
        // ① 原生 Log 恒输出：logcat 行为与改造前完全一致（关掉文件日志也不影响 adb logcat）
        runCatching {
            when (level) {
                'D' -> if (t == null) Log.d(tag, msg) else Log.d(tag, msg, t)
                'I' -> if (t == null) Log.i(tag, msg) else Log.i(tag, msg, t)
                'W' -> if (t == null) Log.w(tag, msg) else Log.w(tag, msg, t)
                else -> if (t == null) Log.e(tag, msg) else Log.e(tag, msg, t)
            }
        }
        // ② 文件日志：关闭时零成本返回（不建字符串、不解析路径、不做任何 IO）
        if (!enabled) return
        runCatching {
            val sb = StringBuilder(msg.length + 64)
            sb.append(timestamp()).append(' ').append(level).append('/').append(tag).append(": ").append(msg)
            if (t != null) sb.append('\n').append(Log.getStackTraceString(t))
            // 队列满 → trySend 返回失败，该行**静默丢弃**；绝不在这里阻塞或写盘
            queue.trySend(sb.toString())
        }
    }

    private fun timestamp(): String = synchronized(tsLock) { tsFmt.format(Date()) }

    // ————————————————————— 引擎生命周期 —————————————————————

    @Synchronized
    private fun start() {
        if (scope != null) return
        drainQueue() // 丢掉上一轮会话的残留（避免串写）
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            runCatching { cleanupOldDays() }
            consume(Session())
        }
    }

    @Synchronized
    private fun stop() {
        // 只取消作用域 + 排空队列（纯内存操作，可在调用线程直接做）；
        // 文件流的 flush/close 交给消费协程的 finally —— 那里在 IO 线程上，不阻塞 UI
        runCatching { scope?.cancel() }
        scope = null
        drainQueue()
    }

    /** 排空内存队列（纯内存，无 IO） */
    private fun drainQueue() {
        runCatching { while (queue.tryReceive().isSuccess) { /* 丢弃 */ } }
    }

    /** 一次会话的写盘状态（每轮 start 新建，避免开关快速切换时新旧会话共用字段） */
    private class Session {
        var writer: BufferedWriter? = null
        var dir: File? = null
        var baseDir: File? = null
        var base = ""
        var seq = 0
        var bytes = 0L
        var pending = 0
    }

    // ————————————————————— 消费协程（IO 线程） —————————————————————

    private suspend fun consume(s: Session) {
        s.base = runCatching { SimpleDateFormat("HH-mm-ss", Locale.US).format(Date()) }.getOrDefault("log")
        var lastFlush = System.currentTimeMillis()
        try {
            while (currentCoroutineContext().isActive) {
                val line = runCatching { queue.tryReceive().getOrNull() }.getOrNull()
                if (line == null) {
                    // 空闲：到点就 flush（最多每秒一次），然后轻量休眠
                    if (System.currentTimeMillis() - lastFlush >= FLUSH_INTERVAL_MS) {
                        flush(s)
                        lastFlush = System.currentTimeMillis()
                    }
                    delay(IDLE_POLL_MS)
                    continue
                }
                appendLine(s, line)
                val now = System.currentTimeMillis()
                if (s.pending >= FLUSH_BYTES || now - lastFlush >= FLUSH_INTERVAL_MS) {
                    flush(s)
                    lastFlush = now
                }
            }
        } finally {
            // 被取消（关开关）也会走到这里：在 IO 线程上安全收尾
            close(s)
        }
    }

    private fun appendLine(s: Session, line: String) {
        val w = s.writer ?: openFile(s) ?: return // 打不开（盘拔出/无权限）→ 静默丢弃该行
        val bytes = utf8Len(line) + 1
        val ok = runCatching { w.write(line); w.newLine() }.isSuccess
        if (!ok) {
            onWriteFailure(s)
            return
        }
        s.bytes += bytes
        s.pending += bytes
        if (s.bytes >= MAX_FILE_BYTES) rollOver(s)
    }

    private fun flush(s: Session) {
        val w = s.writer ?: run {
            s.pending = 0
            return
        }
        if (runCatching { w.flush() }.isFailure) onWriteFailure(s) else s.pending = 0
    }

    private fun close(s: Session) {
        runCatching { s.writer?.flush() }
        runCatching { s.writer?.close() }
        s.writer = null
    }

    /** 写满 2MB：关当前流、同日目录下按序号新建 */
    private fun rollOver(s: Session) {
        close(s)
        s.seq += 1
        s.bytes = 0
        s.pending = 0
        openFile(s)
    }

    /**
     * 打开（或新建）当前日志文件。
     * 序号选择：`<base>.log` 已存在且未写满 → 继续追加（引擎在同一秒内重启的情况）；
     * 已写满 → 递增到 `<base>_1.log` / `_2` …（上限 [MAX_SEQ] 防御性兜底）。
     */
    private fun openFile(s: Session): BufferedWriter? {
        val dir = ensureDayDir(s) ?: return null
        var seq = s.seq
        while (seq <= MAX_SEQ) {
            val name = if (seq == 0) "${s.base}.log" else "${s.base}_$seq.log"
            val f = File(dir, name)
            val reusable = !f.isFile || runCatching { f.length() }.getOrDefault(0L) < MAX_FILE_BYTES
            if (reusable) {
                val w = runCatching {
                    BufferedWriter(OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8))
                }.getOrNull() ?: return null
                s.seq = seq
                s.writer = w
                s.bytes = runCatching { f.length() }.getOrDefault(0L)
                s.pending = 0
                return w
            }
            seq += 1
        }
        return null
    }

    /** 当天目录 `app_log/yyyy-MM-dd/`（不存在则建） */
    private fun ensureDayDir(s: Session): File? {
        val base = s.baseDir ?: baseDir(s)
        val day = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
            .getOrDefault("unknown")
        val dir = File(base, day)
        val ok = runCatching { dir.isDirectory || dir.mkdirs() }.getOrDefault(false)
        if (ok) s.dir = dir
        return if (ok) dir else null
    }

    // ————————————————————— 落点解析与降级 —————————————————————

    /** 活动沙盒「其他」分类下的 `app_log/`；解析不到就用内部存储兜底 */
    private fun baseDir(s: Session): File {
        s.baseDir?.let { return it }
        val d = resolveBaseDir() ?: fallbackBaseDir()
        s.baseDir = d
        return d
    }

    private fun resolveBaseDir(): File? = runCatching {
        File(FileLocations.root(Category.OTHER), DIR_NAME).let { if (!it.isDirectory) it.mkdirs(); it }
    }.getOrNull()

    /**
     * 内部存储兜底落点：`<内部存储>/TransView/Downloads/app_log/`。
     * 用于「活动存储是 U 盘但已拔出、而 `FileLocations` 状态尚未刷新」的瞬间，
     * 保证日志引擎**绝不因为拔 U 盘而中断或崩溃**。
     */
    private fun fallbackBaseDir(): File = runCatching {
        File(Environment.getExternalStorageDirectory(), SANDBOX_DIR_NAME)
            .let { File(it, "Downloads") }
            .let { File(it, DIR_NAME) }
            .also { runCatching { it.mkdirs() } }
    }.getOrElse { File(DIR_NAME) }

    /**
     * 写失败（磁盘满 / U 盘拔出 / 权限）→ 关流清缓存，下一行触发重新解析落点；
     * 并（限流 30 秒）触发一次存储状态重检，让活动存储自动降级到内部存储。
     */
    private fun onWriteFailure(s: Session) {
        close(s)
        s.dir = null
        s.baseDir = null
        val now = System.currentTimeMillis()
        if (now - lastRecoveryAt >= RECOVERY_INTERVAL_MS) {
            lastRecoveryAt = now
            runCatching { FileLocations.refresh() }
        }
    }

    // ————————————————————— 历史清理 —————————————————————

    /**
     * 只保留最近 [KEEP_DAYS] 天的日期目录。
     *
 * 双重保险，确保绝不误删：① 目录名必须严格匹配 `yyyy-MM-dd`（`app_log/` 下别的东西一律不碰）；
 * ② 必须通过 `FileLocations.isInsideSandbox` 断言（沙盒外一律不删）。
     * `yyyy-MM-dd` 定长字典序 == 时间序，按名字倒排即可，无需解析日期。
     */
    private fun cleanupOldDays() {
        runCatching {
            val base = resolveBaseDir() ?: return
            val days = base.listFiles { f -> f.isDirectory && DAY_RE.matches(f.name) } ?: return
            if (days.size <= KEEP_DAYS) return
            days.sortedByDescending { it.name }.drop(KEEP_DAYS).forEach { old ->
                if (!DAY_RE.matches(old.name)) return@forEach
                if (!runCatching { FileLocations.isInsideSandbox(old) }.getOrDefault(false)) return@forEach
                runCatching { old.deleteRecursively() }
            }
        }
    }

    // ————————————————————— 工具 —————————————————————

    /** UTF-8 编码后的字节数（不分配临时数组；代理对按 3 字节估算，误差可忽略） */
    private fun utf8Len(s: String): Int {
        var n = 0
        for (c in s) {
            val code = c.code
            n += if (code < 0x80) 1 else if (code < 0x800) 2 else 3
        }
        return n
    }

    /** 序号兜底上限（防御：一个会话在同一秒内写满 1000 个 2MB 文件不现实） */
    private const val MAX_SEQ = 999
}
