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
 * ## 两级开关与三档等级（v1.31）
 * 设置页有两个开关，语义**正交**：
 * - 「App 调试日志」= [setEnabled]，总闸。关闭时调用点零成本返回（不建字符串、不解析路径）；
 * - 「详细日志」= [setDetailed]，**只在总闸开启时有意义**。开启后额外落盘 V 级。
 *
 * 调用点据此选级别（这套划分是「常规模式日志量可控」的关键）：
 * | 级别 | 常规模式 | 典型内容 | 频率约束 |
 * | --- | --- | --- | --- |
 * | `I` / `W` / `E` | ✅ 落盘 | 状态跃迁（服务器启停、上传开始与结果、对账统计）与一切失败 | 每次事件一条 |
 * | `D` | ✅ 落盘 | 较细的诊断信息（卷扫描结果、单次删除失败） | 每次操作一条 |
 * | `V` | ❌ 仅详细模式 | UI 操作轨迹、逐次存储探测、逐文件索引 | **可高频**，故默认关闭 |
 *
 * **硬约束：高频路径一律用 V，且不得记「每字节 / 每毫秒」级事件。** [write] 走的是
 * 有界 Channel，队列满即丢弃——高频打点不会阻塞业务，但会把队列打满并**挤掉真正有用的
 * 日志**（丢弃是静默的，事后无从察觉）。例如套接字输入流的 `read()`、上传进度轮询，
 * 都**不**打点。
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
 * ## 切割与清理（两道保险）
 * 单文件上限 [MAX_FILE_BYTES]（2MB），写满立即关流、同日目录下按 `_1`/`_2` 递增新建。
 *
 * 引擎启动时执行 [cleanupOldDays]，按顺序过两道闸：
 * 1. **天数**：删掉 [KEEP_DAYS] 天之前的日期目录；
 * 2. **总量配额**：[MAX_TOTAL_BYTES]（50MB）。v1.31 接入全链路埋点后日志量上升了一个数量级，
 *    只按天数兜底可能出现「一天写出几百 MB、7 天累计数 GB」的失控，故再加总量闸——
 *    从**最旧**的日期目录开始删，直到总量落进配额内（至少保留一天，绝不清空到没得看）。
 *
 * 两道闸都**只删 `app_log/` 下严格匹配 `yyyy-MM-dd` 的目录**，且必须通过
 * `FileLocations.isInsideSandbox` 断言，绝不影响 Downloads 下的其他文件。
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

    /**
     * `app_log/` 总量配额：50MB。
     *
     * 与 [KEEP_DAYS] 是**两道独立的闸**（先过天数、再过总量）。v1.31 接入全链路埋点后
     * 日均日志量上升一个数量级，只按天数兜底可能出现「单日几百 MB、7 天累计数 GB」，
     * 而日志落在用户可见的沙盒里（`TransView/Downloads/app_log/`），失控会真的占用户空间。
     * 超配额时从**最旧**的日期目录开始删，并**至少保留一天**（否则排查时可能一条都看不到）。
     */
    private const val MAX_TOTAL_BYTES = 50L * 1024 * 1024

    /** 总量闸至少要保留的日期目录数（1 = 永远留住最新的那天） */
    private const val MIN_KEPT_DAYS = 1

    /** 队列容量：满了直接丢弃（绝不阻塞调用方） */
    private const val QUEUE_CAPACITY = 4096

    /** 写失败时触发存储重检的限流间隔（`FileLocations.refresh()` 内部有写探针，不能频繁调） */
    private const val RECOVERY_INTERVAL_MS = 30_000L

    /** 日期目录名严格校验（历史清理的二次保险：只认 yyyy-MM-dd） */
    private val DAY_RE = Regex("""\d{4}-\d{2}-\d{2}""")

    /** 开关状态（组合期由 TransViewApp / 设置页写入；调用点只读，避免每行都读 SharedPreferences） */
    @Volatile
    private var enabled = false

    /**
     * 详细模式（设置页「详细日志」）：开启后**额外**落盘 V 级。
     *
     * 不影响 D/I/W/E —— 常规模式已经覆盖状态跃迁与全部失败；V 级放的是可高频的诊断细节
     * （UI 操作轨迹、逐次存储探测、逐文件索引），默认关闭以免把队列和磁盘都占满。
     * 只在 [enabled] 为真时有意义。
     */
    @Volatile
    private var detailed = false

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

    /** 详细模式是否开启（仅当 [isEnabled] 为真时对落盘有影响） */
    val isDetailed: Boolean get() = detailed

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

    /**
     * 详细模式开关（幂等）。与 [setEnabled] **正交**：只在总闸开启时有意义，
     * 关掉总闸时本标志保持不动（下次开总闸仍按原偏好生效）。
     *
     * 只翻转一个 `@Volatile` 标志，**不需要**重启写盘引擎 —— [write] 每次落盘前实时判定，
     * 因此切换立即生效。
     */
    fun setDetailed(value: Boolean) {
        detailed = value
    }

    fun v(tag: String, msg: String) = write('V', tag, msg, null)

    fun v(tag: String, msg: String, t: Throwable?) = write('V', tag, msg, t)

    fun d(tag: String, msg: String) = write('D', tag, msg, null)

    fun d(tag: String, msg: String, t: Throwable?) = write('D', tag, msg, t)

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
                'V' -> if (t == null) Log.v(tag, msg) else Log.v(tag, msg, t)
                'D' -> if (t == null) Log.d(tag, msg) else Log.d(tag, msg, t)
                'I' -> if (t == null) Log.i(tag, msg) else Log.i(tag, msg, t)
                'W' -> if (t == null) Log.w(tag, msg) else Log.w(tag, msg, t)
                else -> if (t == null) Log.e(tag, msg) else Log.e(tag, msg, t)
            }
        }
        // ② 文件日志：关闭时零成本返回（不建字符串、不解析路径、不做任何 IO）
        if (!enabled) return
        // ③ V 级只在详细模式落盘。判定放在 trySend **之前**：常规模式下高频 V 调用点
        //    连 StringBuilder 都不建（调用方的 msg 是字面量拼接，此处不再额外分配）
        if (level == 'V' && !detailed) return
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
     * 启动时清理历史日志：先过 [KEEP_DAYS] 天，再过 [MAX_TOTAL_BYTES] 总量配额。
     *
     * 全程 `runCatching`；单个目录删不掉不影响其余（清理失败远不如写日志失败严重，
     * 最坏情况只是多占些空间，不该因此打断引擎启动）。
     */
    private fun cleanupOldDays() {
        runCatching {
            val base = resolveBaseDir() ?: return
            // 第一道闸：天数。yyyy-MM-dd 定长字典序 == 时间序，按名字倒排即可，无需解析日期
            dayDirs(base)?.drop(KEEP_DAYS)?.forEach { deleteDayDir(it) }
            // 第二道闸：总量配额（v1.31 接入全链路埋点后加入，见 MAX_TOTAL_BYTES）
            enforceTotalQuota(base)
        }
    }

    /** `app_log/` 下严格匹配 `yyyy-MM-dd` 的子目录，按时间倒序（最新在前） */
    private fun dayDirs(base: File): List<File>? =
        base.listFiles { f -> f.isDirectory && DAY_RE.matches(f.name) }
            ?.sortedByDescending { it.name }

    /**
     * 删除一个日期目录，返回是否成功。
     *
     * 双重保险，确保绝不误删：① 目录名必须严格匹配 `yyyy-MM-dd`（`app_log/` 下别的东西一律不碰）；
     * ② 必须通过 `FileLocations.isInsideSandbox` 断言（沙盒外一律不删）。
     */
    private fun deleteDayDir(dir: File): Boolean {
        if (!DAY_RE.matches(dir.name)) return false
        if (!runCatching { FileLocations.isInsideSandbox(dir) }.getOrDefault(false)) return false
        return runCatching { dir.deleteRecursively() }.getOrDefault(false)
    }

    /**
     * 总量闸：从**最旧**的日期目录开始删，直到总量落进 [MAX_TOTAL_BYTES]。
     *
     * **至少保留 [MIN_KEPT_DAYS] 天** —— 超配额时宁可略微超出，也不能把日志清到一条不剩
     * （否则用户打开「其他」页发现目录是空的，等于白开日志）。
     * `app_log/` 根下的散文件（正常不该有）也计入总量，避免它们把配额悄悄吃掉。
     */
    private fun enforceTotalQuota(base: File) {
        runCatching {
            val kept = dayDirs(base)?.toMutableList() ?: return
            val stray = base.listFiles { f -> f.isFile }?.sumOf { it.length() } ?: 0L
            var total = stray + kept.sumOf { dirSize(it) }
            while (total > MAX_TOTAL_BYTES && kept.size > MIN_KEPT_DAYS) {
                val oldest = kept.removeAt(kept.lastIndex)
                val size = dirSize(oldest)
                if (deleteDayDir(oldest)) total -= size else break
            }
        }
    }

    /** 目录总字节数（含子目录；读不到的按 0 计，绝不影响清理主流程） */
    private fun dirSize(dir: File): Long = runCatching {
        dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

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
